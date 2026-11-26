package io.github.nakanomk.stacklife.planner

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.courses.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

interface PlannerApi {
    fun observe(query: PlannerQuery): Flow<QueryResult>
    suspend fun preview(commands: List<PlannerCommand>): PreviewResult
    suspend fun commit(proposalId: String): CommitResult
    /** Local user action only: a live, one-use receipt; never offered as a model tool. */
    suspend fun applyWorkspaceReceipt(requestId: String, action: WorkspaceReceiptAction): CommitResult
    fun discard(proposalId: String)
    /** Register local user-reviewed input, never raw web/file contents or a model-supplied draft. */
    fun stageCourseImport(term: TermSpec, meetings: List<CourseMeeting>, source: String = SchoolImports.default.id): String
    fun discardCourseImport(draftId: String)
}

sealed interface PlannerQuery {
    data object Workspace : PlannerQuery
    data object Queue : PlannerQuery
    data class Task(val taskId: String) : PlannerQuery
    data object Frames : PlannerQuery
    data class Frame(val frameId: String) : PlannerQuery
    data object Settings : PlannerQuery
    data object Terms : PlannerQuery
    data class Week(val termId: String, val week: Int) : PlannerQuery
    data class CourseWeeks(val termId: String, val source: String, val pattern: CoursePattern) : PlannerQuery
    data class Schedule(val id: String) : PlannerQuery
    data class Occurrence(val key: OccurrenceKey, val zone: ZoneId) : PlannerQuery
    data class Reminders(val subject: ReminderSubject) : PlannerQuery
    data object Countdowns : PlannerQuery
    data class Countdown(val id: String) : PlannerQuery
    data object Graphs : PlannerQuery
    data class Graph(val graphId: String) : PlannerQuery
    data class Goal(val graphId: String, val goalId: String) : PlannerQuery
    data class LinkableEvents(val graphId: String, val search: String = "", val sourceFilter: LinkFilter = LinkFilter.ALL,
        val date: LocalDate, val offset: Int = 0, val limit: Int = 50) : PlannerQuery {
        init { require(offset >= 0 && limit in 1..50) }
    }
    data class Source(val link: GraphLink) : PlannerQuery
    data class Day(val date: LocalDate, val zone: ZoneId) : PlannerQuery {
        val startMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

sealed interface QueryResult

data class Terms(val terms: List<TermSpec>, val importedSources: Map<String, List<String>> = emptyMap()) : QueryResult
data class CourseWeeksView(val weeks: Set<Int>) : QueryResult
data class ScheduleView(val revision: Long, val summary: ScheduleSummary?) : QueryResult
data class OccurrenceView(val revision: Long, val occurrence: AgendaOccurrence?) : QueryResult
data class ReminderView(val revision: Long, val policy: ReminderPolicy, val effective: List<ReminderRule>) : QueryResult
data class CourseWeek(val term: TermSpec?, val week: Int, val classes: List<ScheduledCourse>,
    /** Index in this term's distinct title catalog; only this week's keys leave the read transaction. */
    internal val colorIndices: Map<String, Int> = emptyMap()) : QueryResult

/** Queue order is priority order; frames are newest first within restorable and then empty frames. */
data class Workspace(
    val revision: Long,
    val queue: List<QueueTask>,
    val frames: List<FrameView>,
    val restorePolicy: RestorePolicy,
) : QueryResult

data class QueueTask(
    val id: String, val title: String, val notes: String, val minutes: Int,
    val schedule: ScheduleSummary? = null,
    val icon: TaskIcon = TaskIcon.AUTO,
)

data class DayPlan(
    val day: PlannerQuery.Day, val revision: Long, val tray: List<QueueTask>, val occurrences: List<AgendaOccurrence>,
) : QueryResult

enum class FrameSource { PUSH, MANUAL, SWAP, GRAPH }
enum class RestorePolicy { PREPEND, APPEND, SWAP }
enum class RestoreMode { ALL, SELECTED }

internal fun swapReason(): String = uiText("Queue saved before switching frames", "切换栈帧前自动保存")

/** SWAP reasons are system-owned. Never translate a user-entered reason based on its text. */
internal fun frameLabel(reason: String, source: FrameSource): String =
    if (source == FrameSource.SWAP) swapReason() else reason.ifBlank { uiText("Saved queue", "暂存的队列") }

/** Saved values stay in their original order; only items still in this frame expose a live task. */
data class FrameView(
    val id: String,
    val reason: String,
    val savedAtMillis: Long,
    val source: FrameSource,
    val items: List<FrameEntry>,
) {
    val remaining: Int = items.count { it.restorable }
}

data class FrameEntry(
    val taskId: String,
    val originalPosition: Int,
    val title: String,
    val notes: String,
    val minutes: Int,
    val schedule: ScheduleSummary?,
    val current: QueueTask?,
    val referenceOnly: Boolean = false,
    val occurrenceDay: Long? = null,
    val occurrenceInterval: ExactInterval? = null,
    val icon: TaskIcon = TaskIcon.AUTO,
) {
    val restorable: Boolean get() = !referenceOnly && current != null
    val link: GraphLink? get() = if (!referenceOnly) null else occurrenceDay?.let { GraphLink.Occurrence(taskId, it) } ?: GraphLink.Task(taskId)
}

/** A creation ref is batch-local: declare `ref = "a"`, then address it as `@a`. */
data class TaskInput(val title: String, val notes: String = "", val minutes: Int = 30, val ref: String? = null, val icon: TaskIcon = TaskIcon.TASK)

sealed interface PlannerCommand {
    data class TaskAdd(val task: TaskInput) : PlannerCommand
    data class TaskEdit(val taskId: String, val title: String? = null, val notes: String? = null, val minutes: Int? = null,
        val expectedFrameId: String? = null, val expectedRevision: Long? = null, val icon: TaskIcon? = null) : PlannerCommand
    data class TaskFinish(val taskId: String) : PlannerCommand
    data class SetSchedule(val id: String, val pattern: SchedulePattern?, val enabled: Boolean = true,
        val expectedRevision: Long? = null) : PlannerCommand
    data class SetOccurrenceOverride(val key: OccurrenceKey.Schedule, val scope: EditScope, val interval: ExactInterval? = null,
        val segment: DailySegment? = null, val expectedRevision: Long? = null) : PlannerCommand
    data class SetOccurrenceCompletion(val key: OccurrenceKey, val completed: Boolean, val expectedRevision: Long? = null,
        val expectedVersion: Long? = null) : PlannerCommand
    data class SetReminders(val subject: ReminderSubject, val policy: ReminderPolicy, val expectedRevision: Long? = null) : PlannerCommand
    data class TaskRemove(val taskId: String) : PlannerCommand
    data class QueueOrder(val taskIds: List<String>) : PlannerCommand
    data class FrameCreate(val reason: String, val tasks: List<TaskInput>, val ref: String? = null) : PlannerCommand
    data class FramePush(val reason: String = "", val taskIds: List<String>, val ref: String? = null) : PlannerCommand
    data class FrameAddReason(val frameId: String, val reason: String) : PlannerCommand
    data class FrameRestore(val frameId: String, val mode: RestoreMode, val taskIds: List<String> = emptyList()) : PlannerCommand
    data class DeleteFrameHistory(val frameIds: List<String>) : PlannerCommand
    data class SetRestorePolicy(val policy: RestorePolicy) : PlannerCommand
    data class KeepRestoredFrames(val keep: Boolean) : PlannerCommand
    /** A non-null local day requests the practice plan on first completion only. */
    data class CompleteOnboarding(val visible: Set<Destination>? = null, val baseline: Appearance? = null,
        val starterDay: LocalDate? = null) : PlannerCommand
    data class EditAppearance(val patch: AppearancePatch, val expected: Appearance? = null) : PlannerCommand
    data class CourseImport(val draftId: String) : PlannerCommand
    data class TermRemove(val termId: String, val source: String? = null) : PlannerCommand
    /** Null before = add, null after = remove. New term creation never reuses an existing ID. */
    data class ManualCourse(val termId: String, val before: ManualSeries?, val after: ManualSeries?,
        val createTerm: TermSpec? = null) : PlannerCommand
    data class CountdownAdd(val title: String, val targetDay: Long, val mode: CountdownMode = CountdownMode.COUNT_DOWN,
        val pinned: Boolean = false, val backgroundId: String? = null, val maskOpacity: Float = .3f,
        val ref: String? = null, val expectedRevision: Long? = null) : PlannerCommand
    data class CountdownEdit(val id: String, val title: String? = null, val targetDay: Long? = null, val mode: CountdownMode? = null,
        val maskOpacity: Float? = null, val background: BackgroundChange? = null, val expectedRevision: Long? = null) : PlannerCommand
    data class CountdownRemove(val id: String, val expectedRevision: Long? = null) : PlannerCommand
    data class CountdownSetPinned(val id: String, val pinned: Boolean, val expectedRevision: Long? = null) : PlannerCommand
    data class CountdownOrder(val pinned: Boolean, val ids: List<String>, val expectedRevision: Long? = null) : PlannerCommand
    data class GraphCreate(val title: String, val ref: String? = null) : PlannerCommand
    data class GraphPatch(val graphId: String, val edits: List<GraphEdit>, val expectedRevision: Long? = null) : PlannerCommand
    data class GraphRemove(val graphId: String, val expectedRevision: Long? = null) : PlannerCommand
    data class GraphQueue(val graphId: String, val goalId: String, val expectedRevision: Long? = null) : PlannerCommand
    data class GraphFrame(
        val graphId: String, val goalId: String, val reason: String? = null, val ref: String? = null,
        val taskRefs: Map<String, String> = emptyMap(), val expectedRevision: Long? = null,
    ) : PlannerCommand
}

sealed interface PreviewResult {
    data class Ready(val proposalId: String, val baseRevision: Long, val changes: List<PlannerChange>,
        val refs: Map<String, String> = emptyMap()) : PreviewResult
    data class Rejected(val message: String) : PreviewResult
}

enum class CommitFailure { STALE_PREVIEW, UNKNOWN_PROPOSAL, STORAGE }
enum class WorkspaceReceiptAction { KEEP_HISTORY, UNDO }

/** In command order, with resolved IDs only. Moved tasks retain their order in the source. */
sealed interface WorkspaceEffect {
    data class Pushed(val frameId: String, val taskIds: List<String>) : WorkspaceEffect
    data class Restored(val frameId: String, val taskIds: List<String>, val mode: RestoreMode,
        val policy: RestorePolicy, val swapped: Pushed?, val sourceDeleted: Boolean) : WorkspaceEffect
    data class Removed(val taskId: String) : WorkspaceEffect
    data class DeletedHistory(val frameIds: List<String>) : WorkspaceEffect
}

sealed interface CommitResult {
    data class Applied(val revision: Long, val requestId: String, val effects: List<WorkspaceEffect> = emptyList(),
        val refs: Map<String, String> = emptyMap(), val expiresAtElapsed: Long? = null,
        val overlappingSchedule: Boolean = false) : CommitResult
    data class Rejected(val code: CommitFailure, val message: String) : CommitResult
}
