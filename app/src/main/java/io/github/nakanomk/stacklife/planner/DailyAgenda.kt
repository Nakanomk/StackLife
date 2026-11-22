package io.github.nakanomk.stacklife.planner

import io.github.nakanomk.stacklife.courses.CourseMeeting
import io.github.nakanomk.stacklife.i18n.uiText
import kotlinx.serialization.Serializable

enum class RoutineKind { WAKE, SLEEP }

internal fun RoutineKind.label(): String = when (this) {
    RoutineKind.WAKE -> uiText("Wake up", "起床")
    RoutineKind.SLEEP -> uiText("Bedtime", "就寝")
}

@Serializable
data class CourseOccurrenceId(val termId: String, val source: String, val week: Int, val day: Int,
    val firstPeriod: Int, val lastPeriod: Int, val title: String, val room: String) {
    companion object {
        fun of(termId: String, source: String, m: CourseMeeting) =
            CourseOccurrenceId(termId, source, m.week, m.day, m.firstPeriod, m.lastPeriod, m.title, m.room)
    }
}
