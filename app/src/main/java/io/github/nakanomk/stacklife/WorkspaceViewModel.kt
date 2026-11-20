package io.github.nakanomk.stacklife

import android.database.sqlite.SQLiteException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nakanomk.stacklife.courses.CourseMeeting
import io.github.nakanomk.stacklife.courses.TermSpec
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
    fun terms() = read<Terms>(PlannerQuery.Terms)
    fun week(query: PlannerQuery.Week) = read<CourseWeek>(query)

    suspend fun previewImport(term: TermSpec, meetings: List<CourseMeeting>): PreviewResult {
        val draft = planner.stageCourseImport(term, meetings)
        return try { planner.preview(listOf(PlannerCommand.CourseImport(draft))) }
        finally { planner.discardCourseImport(draft) }
    }
    suspend fun previewRemoval(term: String) = planner.preview(listOf(PlannerCommand.TermRemove(term)))
    fun confirmProposal(id: String): String? = startWrite(notifyOnFailure = false) { commit(id) }
    fun discardProposal(id: String) = planner.discard(id)

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
    private data class Ticket(val id: String, val error: Deferred<String?>)
    private var latest: Ticket? = null

    override fun start(command: PlannerCommand): String? = startWrite(notifyOnFailure = true) {
        when (val preview = planner.preview(listOf(command))) {
            is PreviewResult.Rejected -> preview.message
            is PreviewResult.Ready -> commit(preview.proposalId)
        }
    }

    private suspend fun commit(id: String): String? = try {
        when (val result = planner.commit(id)) {
            is CommitResult.Applied -> null
            is CommitResult.Rejected -> result.message
        }
    } finally {
        planner.discard(id)
    }

    // A receipt tracks the original write, not a command or proposal to replay after rotation.
    private fun startWrite(notifyOnFailure: Boolean, write: suspend () -> String?): String? {
        if (latest?.error?.isCompleted == false) {
            errors.trySend("上一项更改正在保存")
            return null
        }
        val id = UUID.randomUUID().toString()
        val result = viewModelScope.async {
            val error = try {
                write()
            } catch (_: SQLiteException) {
                "未能保存到设备，本次更改没有应用"
            }
            // Ordinary actions report even if their screen is gone; confirmation forms show the receipt inline.
            if (error != null && notifyOnFailure) errors.send(error)
            error
        }
        latest = Ticket(id, result)
        return id
    }

    override suspend fun result(requestId: String, onError: ((String) -> Unit)?): SaveOutcome {
        val ticket = latest
        if (ticket?.id == requestId) {
            val error = ticket.error.await() ?: return SaveOutcome.APPLIED
            onError?.invoke(error)
            return SaveOutcome.REJECTED
        }
        // Process death: the transaction may already have committed. Never re-submit the draft.
        val message = "上次保存的回执不可用，未重新提交。请核对当前结果。"
        if (onError != null) onError(message) else errors.send(message)
        return SaveOutcome.INTERRUPTED
    }
}
