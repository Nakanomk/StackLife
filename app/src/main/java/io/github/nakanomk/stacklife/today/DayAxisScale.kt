package io.github.nakanomk.stacklife.today

import io.github.nakanomk.stacklife.planner.DayBlock
import io.github.nakanomk.stacklife.planner.DayPlan
import io.github.nakanomk.stacklife.planner.PlannerQuery
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal const val MINUTE_MS = 60_000L
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

internal fun clockLabel(millis: Long, day: PlannerQuery.Day): String {
    val time = Instant.ofEpochMilli(millis).atZone(day.zone)
    val date = if (time.toLocalDate() == day.date) "" else "${time.monthValue}/${time.dayOfMonth} "
    val offset = if (day.zone.rules.getValidOffsets(time.toLocalDateTime()).size > 1) " ${time.offset}" else ""
    return date + time.format(clockFormat) + offset
}

internal data class AxisTick(val millis: Long, val label: String, val tag: String, val major: Boolean)
internal data class AxisBand(
    val start: Long, val end: Long, val top: Int, val height: Int, val block: DayBlock?,
    val ticks: List<AxisTick>,
) {
    fun yAt(millis: Long): Int = ((millis - start).toDouble() / (end - start) * height).roundToInt()
}

/** The same piecewise transform draws, hit-tests and auto-scrolls. Expand short occupied
 * intervals in place, rather than covering adjacent appointments with oversized touch targets. */
internal class DayAxisScale(plan: DayPlan, pixelsPerMinute: Float, minimumBlockHeight: Int) {
    val day = plan.day
    val bands: List<AxisBand>
    val height: Int

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
        val result = mutableListOf<AxisBand>()
        var top = 0
        var tickIndex = 0
        fun band(start: Long, end: Long, block: DayBlock?) {
            if (end <= start) return
            val height = ((end - start).toDouble() / MINUTE_MS * pixelsPerMinute).roundToInt()
                .coerceAtLeast(if (block == null) 1 else minimumBlockHeight)
            val first = tickIndex
            while (tickIndex < ticks.size && ticks[tickIndex].millis < end) tickIndex++
            result.add(AxisBand(start, end, top, height, block, ticks.subList(first, tickIndex)))
            top += height
        }
        var cursor = day.startMillis
        for (block in plan.blocks.sortedBy { it.startMillis }) {
            val start = block.startMillis.coerceAtLeast(day.startMillis)
            val end = block.endMillis.coerceAtMost(day.endMillis)
            band(cursor, start, null)
            band(start, end, block)
            cursor = end
        }
        band(cursor, day.endMillis, null)
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
