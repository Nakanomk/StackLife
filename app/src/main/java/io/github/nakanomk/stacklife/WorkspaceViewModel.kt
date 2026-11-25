package io.github.nakanomk.stacklife

import io.github.nakanomk.stacklife.i18n.uiText

import android.database.sqlite.SQLiteException
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
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
    // A returning settings scene must lay out its real rows before sampling the launcher.
    // Keep only these three fixed summaries; subscriptions still stop when the page leaves.
    val routineSchedules by lazy { RoutineKind.entries.associateWith {
        read<ScheduleView>(PlannerQuery.Schedule(it.scheduleId)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadState.Loading)
    } }
    val courseReminders by lazy {
        read<ReminderView>(PlannerQuery.Reminders(ReminderSubject.CourseDefault))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadState.Loading)
    }

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
            } else commit(preview.proposalId)
        }
    }

    fun receipt(requestId: String): CommitResult.Applied? = latest?.takeIf { it.id == requestId }?.receipt
    fun reviewedRef(requestId: String, ref: String): String? = receipt(requestId)?.refs?.get(ref)

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
        var receipt: CommitResult.Applied? = null
    }
    private var latest: Ticket? = null

    data class Feedback(val receipt: CommitResult.Applied, val kept: Boolean = false) {
        val effect: WorkspaceEffect get() = receipt.effects.single()
    }
    private val feedbackState = MutableStateFlow<Feedback?>(null)
    val feedback = feedbackState.asStateFlow()
    // The dock's subtree is transient (IME, rotation, nested destinations). An open reason
    // editor keeps its original frame even after another push takes over the Undo action.
    internal class PushReason(val frameId: String) { val field = mutableStateOf(TextFieldValue()) }
    var pushReason by mutableStateOf<PushReason?>(null)
        private set
    fun editReason(frameId: String) { if (pushReason == null) pushReason = PushReason(frameId) }
    fun closeReason(frameId: String) { if (pushReason?.frameId == frameId) pushReason = null }
    suspend fun forgetRestoredWorkspace() {
        val previous = latest
        previous?.error?.await()
        if (previous === latest) { latest = null; feedbackState.value = null; pushReason = null }
    }
    fun dismissFeedback(request: String) {
        feedbackState.value?.takeIf { it.receipt.requestId == request }?.let { feedbackState.compareAndSet(it, null) }
    }
    fun applyFeedback(request: String, action: WorkspaceReceiptAction): String? = startWrite(notifyOnFailure = true) {
        val shown = feedbackState.value?.takeIf { it.receipt.requestId == request }
        when (val result = planner.applyWorkspaceReceipt(request, action)) {
            is CommitResult.Rejected -> { dismissFeedback(request); result.message }
            is CommitResult.Applied -> {
                receipt = result
                if (shown != null && action == WorkspaceReceiptAction.KEEP_HISTORY) feedbackState.compareAndSet(shown, shown.copy(kept = true))
                else dismissFeedback(request)
                if (result.overlappingSchedule) errors.send(uiText("Schedule restored. It overlaps another event.", "排程已恢复，与其他日程重叠。"))
                null
            }
        }
    }

    override fun start(command: PlannerCommand): String? = startWrite(notifyOnFailure = true) {
        when (val preview = planner.preview(listOf(command))) {
            is PreviewResult.Rejected -> preview.message
            is PreviewResult.Ready -> commit(preview.proposalId)
        }
    }

    private suspend fun Ticket.commit(id: String): String? = try {
        when (val result = planner.commit(id)) {
            is CommitResult.Applied -> {
                receipt = result
                if (result.effects.isNotEmpty()) feedbackState.value = if (result.expiresAtElapsed == null) null else Feedback(result)
                null
            }
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
