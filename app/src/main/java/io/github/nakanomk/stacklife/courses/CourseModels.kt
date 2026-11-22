package io.github.nakanomk.stacklife.courses

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.i18n.AppLanguage
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class ReviewedTimetable(val source: String, val term: TermSpec, val meetings: List<CourseMeeting>)
const val MANUAL_SOURCE = "manual"
internal const val MAX_COURSE_FIELD = 256
internal fun courseFieldProblem(title: String, room: String): String? = when {
    title.isBlank() -> uiText("Class name cannot be blank.", "课名不能为空")
    title.length > MAX_COURSE_FIELD || room.length > MAX_COURSE_FIELD -> uiText({ "Course name or room exceeds $MAX_COURSE_FIELD characters. Correct or exclude this entry." }, { "课名或教室超过 $MAX_COURSE_FIELD 个字符，请修正或排除此项。" })
    else -> null
}

val campusZone: ZoneId get() = SchoolImports.default.calendar.zone
internal fun weekday(day: Int): String = DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, AppLanguage.locale)
enum class BellSchedule { SUMMER, WINTER }
data class PeriodTime(val start: LocalTime, val end: LocalTime)
data class TermSpec(val id: String, val firstMonday: LocalDate, val weeks: Int, val initial: BellSchedule, val switchDate: LocalDate? = null)
/** One occurrence, not a recurring course collapsed by name. day is ISO 1..7. */
data class CourseMeeting(val week: Int, val day: Int, val title: String, val room: String, val firstPeriod: Int, val lastPeriod: Int,
    val startMillis: Long, val endMillis: Long)
data class ScheduledCourse(val termId: String, val source: String, val meeting: CourseMeeting, val conflicts: List<String> = emptyList())

/** Identity of one weekly time pattern within the manual source, never just a title. */
data class CoursePattern(val title: String, val room: String, val day: Int, val firstPeriod: Int, val lastPeriod: Int) {
    internal fun matches(m: CourseMeeting) = m.day == day && m.firstPeriod == firstPeriod && m.lastPeriod == lastPeriod &&
        m.title == title && m.room == room
}
internal fun CourseMeeting.pattern() = CoursePattern(title, room, day, firstPeriod, lastPeriod)
data class ManualSeries(val pattern: CoursePattern, val weeks: Set<Int>)

fun TermSpec.problem(): String? = when {
    id.isBlank() || id != id.trim() || id.length > 128 -> uiText("Term ID must be 1–128 characters, with no leading or trailing spaces.", "学期标识须为 1–128 个字符，首尾不能有空格。")
    firstMonday.year !in 1..9999 || firstMonday.dayOfWeek.value != 1 -> uiText("Week one must start on a valid Monday.", "首周日期必须是有效的周一。")
    weeks !in 1..53 -> uiText("The term must have 1–53 weeks.", "总周数须为 1–53。")
    else -> {
        val last = firstMonday.plusWeeks(weeks.toLong()).minusDays(1)
        when {
            last.year > 9999 -> uiText("The end of the term is out of range.", "学期结束日期超出范围。")
            switchDate != null && switchDate !in firstMonday..last -> uiText("The bell schedule change must fall within the term.", "作息切换日不在本学期内。")
            else -> null
        }
    }
}

fun TermSpec.meeting(week: Int, day: Int, title: String, room: String, firstPeriod: Int, lastPeriod: Int,
    calendar: CourseCalendar = SchoolImports.default.calendar): CourseMeeting =
    requireNotNull(calendar.meetingOrNull(this, week, day, title, room, firstPeriod, lastPeriod))

internal fun CourseCalendar.meetingOrNull(term: TermSpec, week: Int, day: Int, title: String, room: String,
    firstPeriod: Int, lastPeriod: Int): CourseMeeting? {
    if (week !in 1..term.weeks || day !in 1..7) return null
    val date = term.firstMonday.plusWeeks(week - 1L).plusDays(day - 1L)
    val periods = periods(term, date)
    if (firstPeriod !in 1..periods.size || lastPeriod !in firstPeriod..periods.size) return null
    return CourseMeeting(week, day, title, room, firstPeriod, lastPeriod,
        date.atTime(periods[firstPeriod - 1].start).atZone(zone).toInstant().toEpochMilli(),
        date.atTime(periods[lastPeriod - 1].end).atZone(zone).toInstant().toEpochMilli())
}
