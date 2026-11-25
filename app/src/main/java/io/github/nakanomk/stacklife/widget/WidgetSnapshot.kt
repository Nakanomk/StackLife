package io.github.nakanomk.stacklife.widget

import io.github.nakanomk.stacklife.courses.MANUAL_SOURCE
import io.github.nakanomk.stacklife.courses.TermSpec
import io.github.nakanomk.stacklife.courses.calendarFor
import io.github.nakanomk.stacklife.planner.CourseOccurrenceId
import io.github.nakanomk.stacklife.planner.CourseWeek
import io.github.nakanomk.stacklife.planner.DayPlan
import io.github.nakanomk.stacklife.planner.OccurrenceKey
import io.github.nakanomk.stacklife.planner.PlannerApi
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.planner.Terms
import io.github.nakanomk.stacklife.planner.TaskIcon
import io.github.nakanomk.stacklife.reminders.ReminderTarget
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.first

/** Only information actually presented on the launcher. No notes, planner handles or file references. */
internal data class WidgetEvent(val target: ReminderTarget, val end: Long, val title: String, val done: Boolean,
    val room: String = "", val period: Int = 0, val weekday: Int = 0, val taskIcon: TaskIcon? = null) {
    fun ongoing(now: Long) = !done && now >= target.startMillis && now < end
}

internal data class WidgetWeek(val monday: LocalDate, val number: Int?, val hasTerms: Boolean,
    val previous: LocalDate?, val next: LocalDate?)

internal data class WidgetSnapshot(val date: LocalDate, val zone: ZoneId, val events: List<WidgetEvent>,
    val nextRefresh: Long, val week: WidgetWeek? = null)

private fun LocalDate.midnightAfter(zone: ZoneId) = plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

internal fun DayPlan.widgetSnapshot(now: Long): WidgetSnapshot {
    var next = day.endMillis
    val rows = occurrences.map {
        if (!it.completed) {
            if (it.startMillis > now) next = minOf(next, it.startMillis)
            if (it.endMillis > now) next = minOf(next, it.endMillis)
        }
        WidgetEvent(ReminderTarget(it.key, it.startMillis), it.endMillis, it.title, it.completed,
            room = it.course?.meeting?.room.orEmpty(), taskIcon = if (it.taskId != null) it.icon else null)
    }
    return WidgetSnapshot(day.date, day.zone, rows, next)
}

internal suspend fun PlannerApi.widgetDay(now: Instant, zone: ZoneId) =
    (observe(PlannerQuery.Day(now.atZone(zone).toLocalDate(), zone)).first() as DayPlan).widgetSnapshot(now.toEpochMilli())

private fun TermSpec.contains(monday: LocalDate) = monday >= firstMonday && monday < firstMonday.plusWeeks(weeks.toLong())
private fun LocalDate.monday() = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** Null cursor follows the current academic week. An explicitly browsed Monday survives process death. */
internal suspend fun PlannerApi.widgetWeek(terms: Terms, picked: LocalDate?, now: Instant, localZone: ZoneId): WidgetSnapshot {
    fun zone(term: TermSpec) = calendarFor(terms.importedSources[term.id]?.firstOrNull() ?: MANUAL_SOURCE)?.zone ?: localZone
    val term = terms.terms.firstOrNull { it.contains(picked ?: now.atZone(zone(it)).toLocalDate().monday()) }
    val zone = term?.let(::zone) ?: terms.terms.firstOrNull()?.let(::zone) ?: localZone
    val today = now.atZone(zone).toLocalDate()
    val monday = picked ?: today.monday()
    val number = term?.let { ((monday.toEpochDay() - it.firstMonday.toEpochDay()) / 7 + 1).toInt() }
    val week = if (term != null && number != null) observe(PlannerQuery.Week(term.id, number)).first() as CourseWeek else null
    val previous = terms.terms.asSequence().filter { it.firstMonday < monday }
        .map { minOf(monday.minusWeeks(1), it.firstMonday.plusWeeks(it.weeks - 1L)) }.maxOrNull()
    val next = terms.terms.asSequence().filter { it.firstMonday.plusWeeks(it.weeks - 1L) > monday }
        .map { maxOf(monday.plusWeeks(1), it.firstMonday) }.minOrNull()
    val rows = week?.classes.orEmpty().map { c ->
        val m = c.meeting
        WidgetEvent(ReminderTarget(OccurrenceKey.Course(CourseOccurrenceId.of(c.termId, c.source, m)), m.startMillis),
            m.endMillis, m.title, c.completed, m.room, m.firstPeriod, m.day)
    }.sortedWith(compareBy<WidgetEvent> { it.weekday }.thenBy { it.target.startMillis }.thenBy { it.title }.thenBy { it.room })
    return WidgetSnapshot(monday, zone, rows, today.midnightAfter(zone), WidgetWeek(monday, number, terms.terms.isNotEmpty(), previous, next))
}

/** One band per occupied start period; simultaneous meetings get distinct accessible cells, not overpainted cards. */
internal fun weeklyBands(rows: List<WidgetEvent>): List<List<WidgetEvent?>> = buildList {
    for (period in rows.groupBy { it.period }.toSortedMap().values) {
        val days = period.groupBy { it.weekday }
        for (lane in 0 until days.values.maxOf { it.size }) add((1..7).map { days[it]?.getOrNull(lane) })
    }
}
