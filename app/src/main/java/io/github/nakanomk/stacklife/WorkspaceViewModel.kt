package io.github.nakanomk.stacklife

import io.github.nakanomk.stacklife.i18n.uiText

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

internal class WorkspaceViewModel(val planner: PlannerApi) : ViewModel(), WorkspaceActions {
    internal inline fun <reified T : QueryResult> read(query: PlannerQuery) = planner.observe(query)
        .map<QueryResult, ReadState<T>> { ReadState.Loaded(it as T) }
        .catch { cause ->
            if (cause !is SQLiteException) throw cause
            emit(ReadState.ReadFailed)
        }

    val workspace = read<Workspace>(PlannerQuery.Workspace)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadState.Loading)
    val settings = read<PolicyView>(PlannerQuery.Settings)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadState.Loading)

    fun day(query: PlannerQuery.Day) = read<DayPlan>(query)
    fun terms() = read<Terms>(PlannerQuery.Terms)
    fun week(query: PlannerQuery.Week) = read<CourseWeek>(query)
    fun courseWeeks(query: PlannerQuery.CourseWeeks) = read<CourseWeeksView>(query)
    fun graphs() = read<GraphList>(PlannerQuery.Graphs)
    fun graph(id: String) = read<GraphDetail>(PlannerQuery.Graph(id))
    fun goal(graphId: String, goalId: String) = read<GoalOrder>(PlannerQuery.Goal(graphId, goalId))
    suspend fun preview(command: PlannerCommand) = planner.preview(listOf(command))

    suspend fun previewImport(term: TermSpec, meetings: List<CourseMeeting>, source: String): PreviewResult {
        val draft = planner.stageCourseImport(term, meetings, source)
        return try { planner.preview(listOf(PlannerCommand.CourseImport(draft))) }
        finally { planner.discardCourseImport(draft) }
    }
    suspend fun previewRemoval(term: String, source: String) = planner.preview(listOf(PlannerCommand.TermRemove(term, source)))
    fun confirmProposal(id: String): String? = startWrite(notifyOnFailure = false) { commit(id) }
    fun discardProposal(id: String) = planner.discard(id)

    fun startReviewed(commands: List<PlannerCommand>, expectedRevision: Long): String? = startWrite(notifyOnFailure = false) {
        when (val preview = planner.preview(commands)) {
            is PreviewResult.Rejected -> preview.message
            is PreviewResult.Ready -> if (preview.baseRevision != expectedRevision) {
                planner.discard(preview.proposalId)
                uiText("Your schedule changed. Review the new order before generating it again.", "日程已变更，请核对最新顺序后再生成。")
            } else commit(preview.proposalId).also { error -> if (error == null) refs = preview.refs }
        }
    }

    fun reviewedRef(requestId: String, ref: String): String? = latest?.takeIf { it.id == requestId }?.refs?.get(ref)

    /** A hover or editor check never leaves a committable proposal behind. */
    suspend fun checkSchedule(command: PlannerCommand): String? = try {
        when (val preview = planner.preview(listOf(command))) {
            is PreviewResult.Rejected -> preview.message
            is PreviewResult.Ready -> { planner.discard(preview.proposalId); null }
        }
    } catch (_: SQLiteException) {
        uiText("Could not read time slots. No task was scheduled.", "无法从设备读取时段，未安排任务")
    }

    private val errors = Channel<String>(Channel.BUFFERED)
    val notices = errors.receiveAsFlow()
    private class Ticket(val id: String) {
        lateinit var error: Deferred<String?>
        var refs: Map<String, String> = emptyMap()
    }
    private var latest: Ticket? = null

    // Keep the confirmed frame ID separate from the most recent write ticket: another task or
    // restore may commit before the user taps the snackbar. Never infer it from frame list order.
    private val pushed = MutableStateFlow<String?>(null)
    val pushedFrame = pushed.asStateFlow()
    fun dismissPush(id: String) { pushed.compareAndSet(id, null) }

    fun pushQueue(taskIds: List<String>): String? {
        // A previous success must not be mistaken for the outcome of this attempt.
        pushed.value = null
        return submit(PlannerCommand.FramePush(taskIds = taskIds, ref = "pushed")) { pushed.value = it.refs.getValue("pushed") }
    }

    override fun start(command: PlannerCommand): String? = submit(command)

    private fun submit(command: PlannerCommand, applied: ((PreviewResult.Ready) -> Unit)? = null): String? = startWrite(notifyOnFailure = true) {
        when (val preview = planner.preview(listOf(command))) {
            is PreviewResult.Rejected -> preview.message
            is PreviewResult.Ready -> commit(preview.proposalId).also { error -> if (error == null) applied?.invoke(preview) }
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
    private fun startWrite(notifyOnFailure: Boolean, write: suspend Ticket.() -> String?): String? {
        if (latest?.error?.isCompleted == false) {
            errors.trySend(uiText("The previous change is still being saved.", "上一项更改正在保存"))
            return null
        }
        val ticket = Ticket(UUID.randomUUID().toString())
        ticket.error = viewModelScope.async {
            val error = try {
                ticket.write()
            } catch (_: SQLiteException) {
                uiText("Could not save to this device. No changes were applied.", "未能保存到设备，本次更改没有应用")
            }
            // Ordinary actions report even if their screen is gone; confirmation forms show the receipt inline.
            if (error != null && notifyOnFailure) errors.send(error)
            error
        }
        latest = ticket
        return ticket.id
    }

    override suspend fun result(requestId: String, onError: ((String) -> Unit)?): SaveOutcome {
        val ticket = latest
        if (ticket?.id == requestId) {
            val error = ticket.error.await() ?: return SaveOutcome.APPLIED
            onError?.invoke(error)
            return SaveOutcome.REJECTED
        }
        // Process death: the transaction may already have committed. Never re-submit the draft.
        val message = uiText("The last save could not be verified. Check the result before trying again.", "上次保存的回执不可用，未重新提交。请核对当前结果。")
        if (onError != null) onError(message) else errors.send(message)
        return SaveOutcome.INTERRUPTED
    }
}
