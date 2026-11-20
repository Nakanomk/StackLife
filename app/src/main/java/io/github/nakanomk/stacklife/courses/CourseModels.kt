package io.github.nakanomk.stacklife.courses

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

const val HUST_SOURCE = "hust-hub"
internal const val MAX_COURSE_FIELD = 256
internal fun courseFieldProblem(title: String, room: String): String? = when {
    title.isBlank() -> "KCMC 课名不能为空"
    title.length > MAX_COURSE_FIELD || room.length > MAX_COURSE_FIELD -> "课名或教室超过 $MAX_COURSE_FIELD 个字符，请修正或排除此项。"
    else -> null
}

val campusZone: ZoneId = ZoneId.of("Asia/Shanghai")
enum class BellSchedule { SUMMER, WINTER }
data class PeriodTime(val start: LocalTime, val end: LocalTime)
data class TermSpec(val id: String, val firstMonday: LocalDate, val weeks: Int, val initial: BellSchedule, val switchDate: LocalDate? = null)
/** One occurrence, not a recurring course collapsed by name. day is ISO 1..7. */
data class CourseMeeting(val week: Int, val day: Int, val title: String, val room: String, val firstPeriod: Int, val lastPeriod: Int,
    val startMillis: Long, val endMillis: Long)
data class ScheduledCourse(val termId: String, val source: String, val meeting: CourseMeeting, val conflicts: List<String> = emptyList())

fun TermSpec.problem(): String? = when {
    id.isBlank() || id != id.trim() || id.length > 128 -> "学期标识须为 1–128 个字符，首尾不能有空格。"
    firstMonday.year !in 1..9999 || firstMonday.dayOfWeek.value != 1 -> "首周日期必须是有效的周一。"
    weeks !in 1..53 -> "总周数须为 1–53。"
    else -> {
        val last = firstMonday.plusWeeks(weeks.toLong()).minusDays(1)
        when {
            last.year > 9999 -> "学期结束日期超出范围。"
            switchDate != null && switchDate !in firstMonday..last -> "作息切换日不在本学期内。"
            else -> null
        }
    }
}

fun TermSpec.meeting(week: Int, day: Int, title: String, room: String, firstPeriod: Int, lastPeriod: Int): CourseMeeting {
    val date = firstMonday.plusWeeks(week - 1L).plusDays(day - 1L)
    val periods = periodsOn(date)
    return CourseMeeting(week, day, title, room, firstPeriod, lastPeriod,
        date.atTime(periods[firstPeriod - 1].start).atZone(campusZone).toInstant().toEpochMilli(),
        date.atTime(periods[lastPeriod - 1].end).atZone(campusZone).toInstant().toEpochMilli())
}
