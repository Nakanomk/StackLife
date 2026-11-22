package io.github.nakanomk.stacklife.planner

import io.github.nakanomk.stacklife.courses.CourseMeeting
import io.github.nakanomk.stacklife.i18n.uiText
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class RoutineKind { WAKE, SLEEP }

internal fun RoutineKind.label(): String = when (this) {
    RoutineKind.WAKE -> uiText("Wake up", "起床")
    RoutineKind.SLEEP -> uiText("Bedtime", "就寝")
}

data class DailyRoutine(val wakeMinute: Int? = null, val sleepMinute: Int? = null) {
    internal operator fun get(kind: RoutineKind): Int? = if (kind == RoutineKind.WAKE) wakeMinute else sleepMinute

    internal fun event(kind: RoutineKind, date: LocalDate, zone: ZoneId, completed: Boolean): RoutineEvent? {
        val minute = this[kind] ?: return null
        val time = date.atTime(LocalTime.ofSecondOfDay(minute * 60L)).atZone(zone)
        // atZone advances through a DST gap and chooses the first occurrence of a repeated hour.
        if (time.toLocalDate() != date) return null
        return RoutineEvent(kind, date, time.toInstant().toEpochMilli(), completed)
    }
}

data class RoutineEvent(val kind: RoutineKind, val date: LocalDate, val startMillis: Long, val completed: Boolean)

data class CourseOccurrenceId(val termId: String, val source: String, val week: Int, val day: Int,
    val firstPeriod: Int, val lastPeriod: Int, val title: String, val room: String) {
    companion object {
        fun of(termId: String, source: String, m: CourseMeeting) =
            CourseOccurrenceId(termId, source, m.week, m.day, m.firstPeriod, m.lastPeriod, m.title, m.room)
    }
}
