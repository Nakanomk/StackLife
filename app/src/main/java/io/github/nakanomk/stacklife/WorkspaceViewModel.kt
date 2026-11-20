package io.github.nakanomk.stacklife

import android.database.sqlite.SQLiteException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.SaveOutcome
import io.github.nakanomk.stacklife.workspace.WorkspaceActions
import java.util.UUID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

internal sealed interface WorkspaceState {
    data object Loading : WorkspaceState
    data object ReadFailed : WorkspaceState
    data class Loaded(val workspace: Workspace) : WorkspaceState
}

internal class WorkspaceViewModel(private val planner: PlannerApi) : ViewModel(), WorkspaceActions {
    val state = planner.observe(PlannerQuery.Workspace)
        .map<QueryResult, WorkspaceState> { result ->
            when (result) { is Workspace -> WorkspaceState.Loaded(result) }
        }
        .catch { cause ->
            if (cause !is SQLiteException) throw cause
            emit(WorkspaceState.ReadFailed)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkspaceState.Loading)

    private val errors = Channel<String>(Channel.BUFFERED)
    val notices = errors.receiveAsFlow()
    private data class Ticket(val id: String, val outcome: Deferred<SaveOutcome>)
    private var latest: Ticket? = null

    // Main-thread UI entry. Rotation can stop awaiting this write, but cannot rerun or cancel it.
    override fun start(command: PlannerCommand): String? {
        if (latest?.outcome?.isCompleted == false) {
            errors.trySend("上一项更改正在保存")
            return null
        }
        val id = UUID.randomUUID().toString()
        val outcome = viewModelScope.async {
            var pending: String? = null
            val error = try {
                when (val plan = planner.preview(listOf(command))) {
                    is PreviewResult.Rejected -> plan.message
                    is PreviewResult.Ready -> {
                        pending = plan.proposalId
                        when (val result = planner.commit(plan.proposalId)) {
                            is CommitResult.Applied -> null
                            is CommitResult.Rejected -> result.message
                        }
                    }
                }
            } catch (_: SQLiteException) {
                "未能保存到设备，本次更改没有应用"
            } finally {
                pending?.let(planner::discard)
            }
            if (error == null) SaveOutcome.APPLIED else {
                errors.send(error)
                SaveOutcome.REJECTED
            }
        }
        latest = Ticket(id, outcome)
        return id
    }

    override suspend fun result(requestId: String): SaveOutcome {
        val ticket = latest
        if (ticket?.id == requestId) return ticket.outcome.await()
        // Process death: the transaction may already have committed. Never re-submit the draft.
        errors.send("上次保存的回执不可用，未重新提交。请在工作台核对结果。")
        return SaveOutcome.INTERRUPTED
    }
}
