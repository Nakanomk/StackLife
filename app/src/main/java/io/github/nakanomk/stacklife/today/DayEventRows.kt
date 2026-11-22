package io.github.nakanomk.stacklife.today

import java.util.Locale
import io.github.nakanomk.stacklife.i18n.AppLanguage
import io.github.nakanomk.stacklife.planner.PlannerQuery

internal sealed interface DayEventRow {
    val start: Long
    val end: Long
    data class Event(val entry: AxisEntry, override val start: Long, override val end: Long) : DayEventRow
    data class Gap(override val start: Long, override val end: Long) : DayEventRow
}

/** Events stay individual; free time is the complement of the union of their clipped intervals. */
internal fun dayEventRows(day: PlannerQuery.Day, chronological: List<AxisEntry>): List<DayEventRow> = buildList {
    var occupiedUntil = day.startMillis
    for (entry in chronological) {
        val start = maxOf(day.startMillis, entry.startMillis)
        val end = minOf(day.endMillis, entry.endMillis)
        if (start >= end) continue
        if (start > occupiedUntil) add(DayEventRow.Gap(occupiedUntil, start))
        add(DayEventRow.Event(entry, start, end))
        occupiedUntil = maxOf(occupiedUntil, if (entry is AxisEntry.Routine) start else end)
    }
    if (occupiedUntil < day.endMillis) add(DayEventRow.Gap(occupiedUntil, day.endMillis))
}

internal fun elapsedLabel(millis: Long, locale: Locale = AppLanguage.locale): String = buildList {
    val chinese = locale.language == "zh"
    val minutes = millis / MINUTE_MS
    if (minutes >= 60) add(if (chinese) "${minutes / 60} 小时" else "${minutes / 60} hr")
    if (minutes % 60 != 0L) add(if (chinese) "${minutes % 60} 分钟" else "${minutes % 60} min")
    val seconds = millis % MINUTE_MS / 1000
    if (seconds > 0) add(if (chinese) "$seconds 秒" else "$seconds sec")
    if (isEmpty()) add(if (millis > 0) { if (chinese) "不足 1 秒" else "Less than 1 sec" } else { if (chinese) "0 分钟" else "0 min" })
}.joinToString(" ")
