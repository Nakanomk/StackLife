package io.github.nakanomk.stacklife.planner

import io.github.nakanomk.stacklife.courses.BellSchedule
import io.github.nakanomk.stacklife.courses.CourseMeeting
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A local, non-executable record. Provider tool output is built independently by ToolProjection. */
@Serializable
data class PlannerChange(val entityId: String, val before: ChangeSnapshot?, val after: ChangeSnapshot?)

@Serializable sealed interface ChangeSnapshot

@Serializable enum class Owner { QUEUE, FRAME, DONE, REMOVED }
/** Enum names are the durable SQLite/JSON identifiers; never store a drawable resource ID. */
@Serializable enum class TaskIcon { AUTO, TASK, EVENT, PERSON, SCHOOL }
@Serializable @SerialName("task")
data class TaskSnapshot(val title: String, val notes: String, val minutes: Int, val owner: Owner,
    val frameId: String?, val queuePosition: Int?, val icon: TaskIcon = TaskIcon.AUTO) : ChangeSnapshot

@Serializable data class NamedId(val id: String, val title: String)
@Serializable @SerialName("queue") data class QueueSnapshot(val items: List<NamedId>) : ChangeSnapshot

@Serializable data class FrameItemSnapshot(val taskId: String, val originalPosition: Int, val title: String,
    val notes: String, val minutes: Int, val startMillis: Long?, val endMillis: Long?, val restorable: Boolean,
    val schedule: ScheduleSummary? = null, val referenceOnly: Boolean = false, val occurrenceDay: Long? = null,
    val icon: TaskIcon = TaskIcon.AUTO)
@Serializable data class FrameRestoreAction(val mode: RestoreMode, val policy: RestorePolicy,
    val taskIds: List<String>, val savedQueueFrameId: String?)
@Serializable @SerialName("frame")
data class FrameSnapshot(val reason: String, val savedAtMillis: Long, val source: FrameSource,
    val items: List<FrameItemSnapshot>, val restores: List<FrameRestoreAction> = emptyList()) : ChangeSnapshot

@Serializable @SerialName("graph") data class GraphSnapshot(val title: String, val nodes: List<GraphNode>,
    val edges: List<GraphEdge>) : ChangeSnapshot
@Serializable @SerialName("goalGeneration")
data class GoalGenerationSnapshot(val frame: Boolean, val order: List<GraphNode>) : ChangeSnapshot
@Serializable @SerialName("countdown") data class CountdownSnapshot(val value: Countdown) : ChangeSnapshot

@Serializable data class Overlap(val id: String, val title: String, val startMillis: Long, val endMillis: Long,
    val course: CourseOccurrenceId? = null)
@Serializable @SerialName("schedule") data class ScheduleSnapshot(val taskId: String, val title: String,
    val startMillis: Long, val endMillis: Long, val zone: String, val overlaps: List<Overlap>) : ChangeSnapshot
@Serializable @SerialName("series") data class SeriesSnapshot(val title: String, val schedule: ScheduleSummary,
    val explicitExceptions: Int) : ChangeSnapshot
@Serializable @SerialName("occurrence") data class OccurrenceSnapshot(val key: OccurrenceKey, val title: String,
    val interval: ExactInterval?, val completed: Boolean, val zone: String) : ChangeSnapshot
@Serializable @SerialName("reminders") data class ReminderSnapshot(val title: String, val subject: ReminderSubject,
    val policy: ReminderPolicy, val effective: List<ReminderRule>, val expiredRuleIds: Set<String>) : ChangeSnapshot

@Serializable data class CourseConflict(val occurrence: CourseOccurrenceId, val startMillis: Long, val endMillis: Long, val other: Overlap)
@Serializable data class CourseReplacement(val old: CourseOccurrenceId, val new: CourseOccurrenceId)
/** Meetings are the affected sides, not the entire timetable. Only a manual edit supplies a correspondence. */
@Serializable @SerialName("courses") data class CourseSetSnapshot(val termId: String, val source: String,
    val firstMonday: Long, val weeks: Int, val initial: BellSchedule, val switchDay: Long?, val zone: String,
    val meetings: List<CourseMeeting>, val conflicts: List<CourseConflict> = emptyList(),
    val replacements: List<CourseReplacement> = emptyList(), val conflictCount: Long = conflicts.size.toLong(),
    val cancelledReminders: Int = 0) : ChangeSnapshot

@Serializable sealed interface PreferenceSnapshot : ChangeSnapshot
@Serializable @SerialName("appearance") data class AppearanceSnapshot(@Serializable(with = StoredAppearance::class) val value: Appearance) : PreferenceSnapshot
@Serializable @SerialName("restorePolicy") data class RestorePolicySnapshot(val value: RestorePolicy) : PreferenceSnapshot
@Serializable @SerialName("keepRestoredFrames") data class KeepRestoredFramesSnapshot(val keep: Boolean) : PreferenceSnapshot
@Serializable @SerialName("routineTime") data class RoutineTimeSnapshot(val kind: RoutineKind, val minute: Int?) : PreferenceSnapshot
@Serializable @SerialName("courseCompletion") data class CourseCompletionSnapshot(val occurrence: CourseOccurrenceId,
    val completed: Boolean) : ChangeSnapshot
@Serializable @SerialName("routineCompletion") data class RoutineCompletionSnapshot(val kind: RoutineKind, val epochDay: Long,
    val completed: Boolean) : ChangeSnapshot

internal fun CourseOccurrenceId.stableKey() = "class:" + Json.encodeToString(this)
