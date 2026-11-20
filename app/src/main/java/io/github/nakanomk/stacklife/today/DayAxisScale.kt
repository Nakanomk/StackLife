package io.github.nakanomk.stacklife.today

import io.github.nakanomk.stacklife.courses.ScheduledCourse
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
    val start: Long, val end: Long, val top: Int, val height: Int, val ticks: List<AxisTick>,
) {
    fun yAt(millis: Long): Int = ((millis - start).toDouble() / (end - start) * height).roundToInt()
}

internal sealed interface AxisEntry {
    val startMillis: Long
    val endMillis: Long

    data class Task(val block: DayBlock, val conflict: Boolean) : AxisEntry {
        override val startMillis get() = block.startMillis
        override val endMillis get() = block.endMillis
    }
    data class Course(val course: ScheduledCourse) : AxisEntry {
        override val startMillis get() = course.meeting.startMillis
        override val endMillis get() = course.meeting.endMillis
    }
}

internal data class AxisGroup(val start: Long, val end: Long, val lanes: List<List<AxisEntry>>)

/** The same piecewise transform draws, hit-tests and auto-scrolls. Expand short occupied
 * intervals in place, rather than covering adjacent appointments with oversized touch targets. */
internal class DayAxisScale(plan: DayPlan, pixelsPerMinute: Float, minimumBlockHeight: Int, minimumCourseHeight: Int) {
    val day = plan.day
    val bands: List<AxisBand>
    val groups: List<AxisGroup>
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
        val entries = buildList<AxisEntry> {
            for (block in plan.blocks) add(AxisEntry.Task(block, block.taskId in plan.conflictingTaskIds))
            for (course in plan.courses) add(AxisEntry.Course(course))
        }.sortedBy { it.startMillis }
        fun start(entry: AxisEntry) = maxOf(entry.startMillis, day.startMillis)
        fun end(entry: AxisEntry) = minOf(entry.endMillis, day.endMillis)

        // Only simultaneous events share horizontal space. Adjacent half-open intervals do not.
        groups = buildList {
            var i = 0
            while (i < entries.size) {
                val first = start(entries[i])
                var last = end(entries[i])
                val lanes = mutableListOf<MutableList<AxisEntry>>()
                do {
                    val entry = entries[i++]
                    val free = lanes.firstOrNull { end(it.last()) <= start(entry) }
                    if (free == null) lanes.add(mutableListOf(entry)) else free.add(entry)
                    last = maxOf(last, end(entry))
                } while (i < entries.size && start(entries[i]) < last)
                add(AxisGroup(first, last, lanes))
            }
        }

        // A shared monotonic mapping even for nested intervals. At each end, stretch only as
        // much as the ending entries still need to reach their minimum height from their start.
        val endsAt = entries.groupBy { end(it) }
        val boundaries = sortedSetOf(day.startMillis, day.endMillis)
        for (entry in entries) { boundaries.add(start(entry)); boundaries.add(end(entry)) }
        val tops = mutableMapOf(day.startMillis to 0)
        val result = mutableListOf<AxisBand>()
        var top = 0
        var cursor = day.startMillis
        var tickIndex = 0
        for (next in boundaries) {
            if (next <= cursor) continue
            var bottom = top + ((next - cursor).toDouble() / MINUTE_MS * pixelsPerMinute).roundToInt().coerceAtLeast(1)
            for (entry in endsAt[next].orEmpty()) {
                val minimum = if (entry is AxisEntry.Course) minimumCourseHeight else minimumBlockHeight
                bottom = maxOf(bottom, tops.getValue(start(entry)) + minimum)
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
