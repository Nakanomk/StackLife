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

internal sealed interface ReadState<out T> {
    data object Loading : ReadState<Nothing>
    data object ReadFailed : ReadState<Nothing>
    data class Loaded<T>(val value: T) : ReadState<T>
}

internal class WorkspaceViewModel(private val planner: PlannerApi) : ViewModel(), WorkspaceActions {
    private inline fun <reified T : QueryResult> read(query: PlannerQuery) = planner.observe(query)
        .map<QueryResult, ReadState<T>> { ReadState.Loaded(it as T) }
        .catch { cause ->
            if (cause !is SQLiteException) throw cause
            emit(ReadState.ReadFailed)
        }

    val workspace = read<Workspace>(PlannerQuery.Workspace)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadState.Loading)

    fun day(query: PlannerQuery.Day) = read<DayPlan>(query)

    /** A hover or editor check never leaves a committable proposal behind. */
    suspend fun checkSlot(command: PlannerCommand.SlotSet): String? = try {
        when (val preview = planner.preview(listOf(command))) {
            is PreviewResult.Rejected -> preview.message
            is PreviewResult.Ready -> { planner.discard(preview.proposalId); null }
        }
    } catch (_: SQLiteException) {
        "无法从设备读取时段，未安排任务"
    }

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
        errors.send("上次保存的回执不可用，未重新提交。请核对当前结果。")
        return SaveOutcome.INTERRUPTED
    }
}
