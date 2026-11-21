package io.github.nakanomk.stacklife.today

import java.util.Locale
import io.github.nakanomk.stacklife.courses.CourseMeeting
import io.github.nakanomk.stacklife.courses.ScheduledCourse
import io.github.nakanomk.stacklife.planner.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DayEventRowsTest {
    private val day = PlannerQuery.Day(LocalDate.of(2026, 9, 7), ZoneId.of("Asia/Shanghai"))
    private fun at(minute: Int) = day.startMillis + minute * MINUTE_MS
    private fun task(id: String, start: Int, end: Int) = DayBlock(id, id, at(start), at(end), ScheduledState.QUEUE)

    @Test fun tasksAndCoursesStayChronologicalButOnlyUnoccupiedMinutesBecomeGaps() {
        val overnight = task("跨入当天", -60, 60)
        val outer = task("重叠时段", 120, 360)
        val nested = ScheduledCourse("学期", "manual", CourseMeeting(1, 1, "固定课", "教室", 1, 2, at(150), at(180)))
        val touching = task("紧接", 360, 390)
        val last = task("跨入次日", 1410, 1470)
        val plan = DayPlan(day, 1, emptyList(), listOf(last, outer, touching, overnight), listOf(nested), setOf(outer.taskId))
        val rows = dayEventRows(day, plan.chronologicalEntries())
        assertEquals(listOf(
            DayEventRow.Event(AxisEntry.Task(overnight, false), at(0), at(60)), DayEventRow.Gap(at(60), at(120)),
            DayEventRow.Event(AxisEntry.Task(outer, true), at(120), at(360)),
            DayEventRow.Event(AxisEntry.Course(nested), at(150), at(180)),
            DayEventRow.Event(AxisEntry.Task(touching, false), at(360), at(390)), DayEventRow.Gap(at(390), at(1410)),
            DayEventRow.Event(AxisEntry.Task(last, false), at(1410), at(1440))), rows)
    }

    @Test fun emptyAndSkippedDaysUseActualElapsedTimeNotWallClockSubtraction() {
        val ny = ZoneId.of("America/New_York")
        for ((date, hours) in listOf("2026-03-08" to 23, "2026-11-01" to 25, "2026-11-02" to 24)) {
            val d = PlannerQuery.Day(LocalDate.parse(date), ny)
            val gap = dayEventRows(d, emptyList()).single() as DayEventRow.Gap
            assertEquals(d.startMillis, gap.start); assertEquals(d.endMillis, gap.end)
            assertEquals("$hours 小时", elapsedLabel(gap.end - gap.start, Locale.CHINESE))
            assertEquals("$hours hr", elapsedLabel(gap.end - gap.start, Locale.ENGLISH))
        }
        val skipped = PlannerQuery.Day(LocalDate.parse("2011-12-30"), ZoneId.of("Pacific/Apia"))
        assertTrue(dayEventRows(skipped, emptyList()).isEmpty())
    }

    @Test fun leadingRepeatedHourAndTrailingGapsKeepUnambiguousStarts() {
        val d = PlannerQuery.Day(LocalDate.parse("2026-11-01"), ZoneId.of("America/New_York"))
        val t = d.date.atTime(1, 30)
        val (early, late) = d.zone.rules.getValidOffsets(t).map { t.toInstant(it).toEpochMilli() }
        val events = listOf(DayBlock("a", "A", early - MINUTE_MS, early, ScheduledState.FRAME),
            DayBlock("b", "B", late, late + MINUTE_MS, ScheduledState.DONE)).map { AxisEntry.Task(it, false) }
        val gaps = dayEventRows(d, events).filterIsInstance<DayEventRow.Gap>()
        assertEquals(listOf(DayEventRow.Gap(d.startMillis, early - MINUTE_MS), DayEventRow.Gap(early, late),
            DayEventRow.Gap(late + MINUTE_MS, d.endMillis)), gaps)
        assertEquals("1 小时", elapsedLabel(late - early, Locale.CHINESE))
        assertEquals("01:30 -04:00", clockLabel(early, d)); assertEquals("01:30 -05:00", clockLabel(late, d))
        assertEquals("1 小时 35 分钟", elapsedLabel(95 * MINUTE_MS, Locale.CHINESE))
        assertEquals("1 hr 35 min", elapsedLabel(95 * MINUTE_MS, Locale.ENGLISH))
    }

    @Test fun renderingEventRowsDoesNotCompressTheTimeScaleOrItsHitTransform() {
        val entries = listOf(AxisEntry.Task(task("短时段", 90, 95), false))
        val scale = DayAxisScale(day, entries, 2f, 60, 80)
        assertEquals(3, dayEventRows(day, entries).size)
        assertEquals(180, scale.yAt(at(90)))
        assertEquals(60, scale.yAt(at(95)) - scale.yAt(at(90)))
        for (minute in listOf(0, 85, 90, 91, 94, 95, 100, 1440)) {
            assertEquals(at(minute), scale.instantAt(scale.yAt(at(minute)).toFloat()))
        }
    }
}
