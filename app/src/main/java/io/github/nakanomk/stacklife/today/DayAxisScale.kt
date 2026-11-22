package io.github.nakanomk.stacklife.today

import io.github.nakanomk.stacklife.planner.PlannerQuery
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal const val MINUTE_MS = 60_000L
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ROOT)

internal fun clockLabel(millis: Long, day: PlannerQuery.Day, includeDate: Boolean = true): String {
    val time = Instant.ofEpochMilli(millis).atZone(day.zone)
    val date = if (!includeDate || time.toLocalDate() == day.date) "" else "${time.monthValue}/${time.dayOfMonth} "
    val offset = if (day.zone.rules.getValidOffsets(time.toLocalDateTime()).size > 1) " ${time.offset}" else ""
    return date + time.format(clockFormat) + offset
}


/** Shared by the day axis and before/after strips, including spans that cross midnight. */
internal fun timeFraction(start: Long, end: Long, at: Long): Double = when {
    at <= start -> 0.0
    at >= end -> 1.0
    end - start > 0 -> (at - start).toDouble() / (end - start)
    else -> (at.toDouble() - start.toDouble()) / (end.toDouble() - start.toDouble())
}
internal data class AxisTick(val millis: Long, val label: String, val tag: String, val major: Boolean)
internal data class AxisBand(
    val start: Long, val end: Long, val top: Int, val height: Int, val ticks: List<AxisTick>,
) {
    fun yAt(millis: Long): Int = (timeFraction(start, end, millis) * height).roundToInt()
}

/** The same piecewise transform draws, hit-tests and auto-scrolls. Expand short occupied
 * intervals in place, rather than covering adjacent appointments with oversized touch targets. */
internal class DayAxisScale(val day: PlannerQuery.Day, val placement: TimeGridPlacement, pixelsPerMinute: Float,
    private val minimumBlockHeight: Int, private val minimumCourseHeight: Int, private val checkHeight: Int) {
    val bands: List<AxisBand>
    val height: Int
    fun minimumHeight(rect: TimeGridPlacement.Rect): Int = (if (rect.item.course != null) minimumCourseHeight else minimumBlockHeight) +
        if (rect.columns > 1) checkHeight else 0

    init {
        val ticks = buildList {
            for (minute in 0 until 24 * 60 step 15) {
                val time = day.date.atTime(minute / 60, minute % 60)
                val offsets = day.zone.rules.getValidOffsets(time)
                for (offset in offsets) {
                    val label = time.format(clockFormat)
                    val repeated = offsets.size > 1
                    add(AxisTick(time.toInstant(offset).toEpochMilli(), if (repeated) "$label\n$offset" else label,
                        "hour:$label" + if (repeated) ":$offset" else "", minute % 60 == 0))
                }
            }
        }.sortedBy { it.millis }
        // Pixels required by an instant (or a +N entrance) belong to the visual axis only.
        // Reserve them before the following boundary without extending the logical interval.
        val boundaries = sortedSetOf(day.startMillis, day.endMillis)
        val pointHeights = mutableMapOf<Long, Int>()
        val minimumAtEnd = mutableMapOf<Long, MutableList<Pair<Long, Int>>>()
        fun reserve(start: Long, end: Long, height: Int) {
            boundaries.add(start); boundaries.add(end)
            if (start == end) pointHeights[start] = maxOf(pointHeights[start] ?: 0, height)
            else minimumAtEnd.getOrPut(end) { ArrayList() }.add(start to height)
        }
        for (r in placement.visible) reserve(r.start, r.end, minimumHeight(r))
        for (g in placement.more) reserve(g.start, g.end, minimumBlockHeight)
        val tops = mutableMapOf(day.startMillis to 0)
        val result = mutableListOf<AxisBand>()
        var top = 0
        var cursor = day.startMillis
        var tickIndex = 0
        for (next in boundaries) {
            if (next <= cursor) continue
            var bottom = top + ((next - cursor).toDouble() / MINUTE_MS * pixelsPerMinute).roundToInt().coerceAtLeast(1)
            bottom = maxOf(bottom, top + (pointHeights[cursor] ?: 0))
            for ((start, minimum) in minimumAtEnd[next].orEmpty()) {
                bottom = maxOf(bottom, tops.getValue(start) + minimum)
            }
            val first = tickIndex
            while (tickIndex < ticks.size && ticks[tickIndex].millis < next) tickIndex++
            result.add(AxisBand(cursor, next, top, bottom - top, ticks.subList(first, tickIndex)))
            tops[next] = bottom
            cursor = next
            top = bottom
        }
        bands = result
        height = top
    }

    fun instantAt(y: Float): Long {
        if (y <= 0f || bands.isEmpty()) return day.startMillis
        if (y >= height) return day.endMillis
        val index = bands.binarySearch { it.top.compareTo(y.toInt()) }
        val band = bands[if (index >= 0) index else -index - 2]
        return band.start + ((y - band.top).toDouble() / band.height * (band.end - band.start)).roundToLong()
    }

    fun yAt(millis: Long): Int {
        if (millis <= day.startMillis || bands.isEmpty()) return 0
        if (millis >= day.endMillis) return height
        val index = bands.binarySearch { it.start.compareTo(millis) }
        val band = bands[if (index >= 0) index else -index - 2]
        return band.top + band.yAt(millis)
    }
}
