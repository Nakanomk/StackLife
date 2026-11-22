package io.github.nakanomk.stacklife.planner

import io.github.nakanomk.stacklife.courses.ScheduledCourse
import io.github.nakanomk.stacklife.i18n.uiText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Original date, not the exception's current start. One-shot identity has an explicit kind in storage. */
@Serializable
sealed interface OccurrenceKey {
    @Serializable @SerialName("schedule") data class Schedule(val scheduleId: String, val originalDay: Long? = null) : OccurrenceKey
    @Serializable @SerialName("course") data class Course(val occurrence: CourseOccurrenceId) : OccurrenceKey
}
private val keyFormat = Json { encodeDefaults = true }
internal fun OccurrenceKey.storageKey(): String = keyFormat.encodeToString(this)
internal fun ReminderSubject.storageKey(): String = keyFormat.encodeToString(this)
internal val RoutineKind.scheduleId: String get() = "system:$name"

@Serializable
sealed interface SchedulePattern {
    @Serializable @SerialName("once") data class Once(val interval: ExactInterval) : SchedulePattern
    @Serializable @SerialName("daily") data class Daily(val segments: List<DailySegment>) : SchedulePattern {
        init {
            require(segments.isNotEmpty() && (1 until segments.size).all { segments[it-1].fromDay < segments[it].fromDay }) {
                uiText("Daily segments must have distinct dates in order.", "每日系列的生效日期必须唯一且有序。")
            }
        }
        internal fun segmentFor(day: Long): DailySegment? {
            val i = segments.binarySearchBy(day, selector = DailySegment::fromDay)
            return segments.getOrNull(if (i >= 0) i else -i - 2)?.takeIf { it.endDay == null || day <= it.endDay }
        }
    }
}

@Serializable
data class ScheduleSummary(val id: String, val pattern: SchedulePattern, val enabled: Boolean = true, val role: RoutineKind? = null)

@Serializable data class SavedSchedule(val payloadVersion: Int = 1, val schedule: ScheduleSummary,
    val occurrenceInterval: ExactInterval? = null)

enum class EditScope { THIS, FOLLOWING, ALL }

/** Local read result. No raw note or course identity is automatically authorized for a provider. */
data class AgendaOccurrence(val key: OccurrenceKey, val title: String, val notes: String, val startMillis: Long,
    val endMillis: Long, val completed: Boolean, val version: Long, val taskId: String? = null, val owner: Owner? = null,
    val course: ScheduledCourse? = null, val role: RoutineKind? = null, val conflicts: List<String> = emptyList()) {
    internal val stableKey = key.storageKey()
    val point: Boolean get() = startMillis == endMillis
    val daily: Boolean get() = (key as? OccurrenceKey.Schedule)?.originalDay != null
    val editable: Boolean get() = role != null || course?.source == io.github.nakanomk.stacklife.courses.MANUAL_SOURCE ||
        taskId != null && owner != Owner.REMOVED
}
internal val agendaOrder: Comparator<AgendaOccurrence> =
    compareBy<AgendaOccurrence> { it.startMillis }.thenBy { it.endMillis }.thenBy { it.stableKey }

@Serializable
sealed interface ReminderSubject {
    @Serializable @SerialName("courses") data object CourseDefault : ReminderSubject
    @Serializable @SerialName("schedule") data class Schedule(val id: String) : ReminderSubject
    @Serializable @SerialName("occurrence") data class Occurrence(val key: OccurrenceKey) : ReminderSubject
}

@Serializable
sealed interface ReminderWhen {
    @Serializable @SerialName("before") data class BeforeStart(val leadMillis: Long) : ReminderWhen {
        init { require(leadMillis >= 0L) { uiText("A reminder lead cannot be negative.", "提醒提前量不能为负。") } }
    }
    @Serializable @SerialName("at") data class AtInstant(val instantMillis: Long) : ReminderWhen
}

internal fun ReminderWhen.trigger(start: Long): Long = when (this) {
    is ReminderWhen.AtInstant -> instantMillis
    is ReminderWhen.BeforeStart -> Math.subtractExact(start, leadMillis)
}

@Serializable data class ReminderRule(val id: String, val time: ReminderWhen)
@Serializable data class ReminderPolicy(val inherit: Boolean, val rules: List<ReminderRule>)

internal val initialReminder = ReminderRule("default-15m", ReminderWhen.BeforeStart(15 * 60_000L))
internal val initialReminderPolicy = ReminderPolicy(false, listOf(initialReminder))
