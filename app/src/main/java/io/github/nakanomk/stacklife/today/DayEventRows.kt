package io.github.nakanomk.stacklife.today

import java.util.Locale
import io.github.nakanomk.stacklife.i18n.AppLanguage
import io.github.nakanomk.stacklife.planner.AgendaOccurrence
import io.github.nakanomk.stacklife.planner.PlannerQuery

internal sealed interface DayEventRow {
    val start: Long
    val end: Long
    data class Event(val entry: AgendaOccurrence, override val start: Long, override val end: Long) : DayEventRow
    data class More(val group: TimeGridPlacement.Overflow) : DayEventRow {
        override val start get() = group.start
        override val end get() = group.end
    }
    data class Gap(override val start: Long, override val end: Long) : DayEventRow
}

/** Visible entries plus overflow links; the free complement uses *every* clipped span. */
internal fun dayEventRows(day: PlannerQuery.Day, placement: TimeGridPlacement): List<DayEventRow> {
    val rows = ArrayList<DayEventRow>()
    for (r in placement.visible) rows.add(DayEventRow.Event(r.item, r.start, r.end))
    for (g in placement.more) rows.add(DayEventRow.More(g))
    rows.sortBy { it.start }
    return buildList {
        var cursor = day.startMillis
        for (row in rows) {
            if (row.start > cursor) add(DayEventRow.Gap(cursor, row.start))
            add(row)
            cursor = maxOf(cursor, row.end)
        }
        if (cursor < day.endMillis) add(DayEventRow.Gap(cursor, day.endMillis))
    }
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
