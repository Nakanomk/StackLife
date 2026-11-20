package io.github.nakanomk.stacklife.planner

import io.github.nakanomk.stacklife.courses.*
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

interface PlannerApi {
    fun observe(query: PlannerQuery): Flow<QueryResult>
    suspend fun preview(commands: List<PlannerCommand>): PreviewResult
    suspend fun commit(proposalId: String): CommitResult
    fun discard(proposalId: String)
    /** Register local user-reviewed input, never raw web/file contents or a model-supplied draft. */
    fun stageCourseImport(term: TermSpec, meetings: List<CourseMeeting>): String
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
    data object Graphs : PlannerQuery
    data class Graph(val graphId: String) : PlannerQuery
    data class Goal(val graphId: String, val goalId: String) : PlannerQuery
    data class Day(val date: LocalDate, val zone: ZoneId) : PlannerQuery {
        val startMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

sealed interface QueryResult

data class Terms(val terms: List<TermSpec>) : QueryResult
data class CourseWeek(val term: TermSpec?, val week: Int, val classes: List<ScheduledCourse>) : QueryResult

/** Queue order is priority order; frames are newest first within restorable and then empty frames. */
data class Workspace(
    val revision: Long,
    val queue: List<QueueTask>,
    val frames: List<FrameView>,
    val restorePolicy: RestorePolicy,
) : QueryResult

data class QueueTask(
    val id: String, val title: String, val notes: String, val minutes: Int,
    val scheduledStartMillis: Long? = null, val scheduledEndMillis: Long? = null,
)

enum class ScheduledState { QUEUE, FRAME, DONE }

/** The stored interval is never clipped. Rendering clips it to the requested civil day. */
data class DayBlock(
    val taskId: String, val title: String, val startMillis: Long, val endMillis: Long, val state: ScheduledState,
)

data class DayPlan(
    val day: PlannerQuery.Day, val revision: Long, val tray: List<QueueTask>, val blocks: List<DayBlock>,
    val courses: List<ScheduledCourse> = emptyList(), val conflictingTaskIds: Set<String> = emptySet(),
) : QueryResult

enum class FrameSource { PUSH, MANUAL, SWAP, GRAPH }
enum class RestorePolicy { PREPEND, APPEND, SWAP }
enum class RestoreMode { ALL, SELECTED }

/** Items are immutable snapshots in their original order. */
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
    val scheduledStartMillis: Long?,
    val scheduledEndMillis: Long?,
    val restorable: Boolean,
)

/** A creation ref is batch-local: declare `ref = "a"`, then address it as `@a`. */
data class TaskInput(val title: String, val notes: String = "", val minutes: Int = 30, val ref: String? = null)

sealed interface PlannerCommand {
    data class TaskAdd(val task: TaskInput) : PlannerCommand
    data class TaskEdit(val taskId: String, val title: String? = null, val notes: String? = null, val minutes: Int? = null) : PlannerCommand
    data class TaskFinish(val taskId: String) : PlannerCommand
    data class TaskRemove(val taskId: String) : PlannerCommand
    data class QueueOrder(val taskIds: List<String>) : PlannerCommand
    data class FrameCreate(val reason: String, val tasks: List<TaskInput>, val ref: String? = null) : PlannerCommand
    data class FramePush(val reason: String, val taskIds: List<String>, val ref: String? = null) : PlannerCommand
    data class FrameRestore(val frameId: String, val mode: RestoreMode, val taskIds: List<String> = emptyList()) : PlannerCommand
    data class SetRestorePolicy(val policy: RestorePolicy) : PlannerCommand
    data class EditAppearance(val patch: AppearancePatch, val expected: Appearance? = null) : PlannerCommand
    data class SlotSet(
        val taskId: String, val start: OffsetDateTime, val durationMinutes: Int, val expectedRevision: Long? = null,
    ) : PlannerCommand
    data class SlotClear(val taskId: String, val expectedRevision: Long? = null) : PlannerCommand
    data class CourseImport(val draftId: String) : PlannerCommand
    data class TermRemove(val termId: String) : PlannerCommand
    data class GraphCreate(val title: String, val ref: String? = null) : PlannerCommand
    data class GraphPatch(val graphId: String, val edits: List<GraphEdit>, val expectedRevision: Long? = null) : PlannerCommand
    data class GraphRemove(val graphId: String, val expectedRevision: Long? = null) : PlannerCommand
    data class GraphFrame(
        val graphId: String, val goalId: String, val reason: String? = null, val ref: String? = null,
        val taskRefs: Map<String, String> = emptyMap(), val expectedRevision: Long? = null,
    ) : PlannerCommand
}

data class PlannerChange(val entityId: String, val field: String, val before: String?, val after: String?, val entityLabel: String? = null)

sealed interface PreviewResult {
    data class Ready(val proposalId: String, val baseRevision: Long, val changes: List<PlannerChange>,
        val refs: Map<String, String> = emptyMap()) : PreviewResult
    data class Rejected(val message: String) : PreviewResult
}

enum class CommitFailure { STALE_PREVIEW, UNKNOWN_PROPOSAL, STORAGE }

sealed interface CommitResult {
    data class Applied(val revision: Long) : CommitResult
    data class Rejected(val code: CommitFailure, val message: String) : CommitResult
}
