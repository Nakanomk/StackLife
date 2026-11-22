package io.github.nakanomk.stacklife.today

import java.util.Locale
import io.github.nakanomk.stacklife.planner.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DayEventRowsTest {
    private val day = PlannerQuery.Day(LocalDate.of(2026, 9, 7), ZoneId.of("Asia/Shanghai"))
    private fun at(minute: Int) = day.startMillis + minute * MINUTE_MS
    private fun task(id: String, start: Int, end: Int) = AgendaOccurrence(OccurrenceKey.Schedule(id), id, "", at(start), at(end), false, 0, taskId = id, owner = Owner.QUEUE)
    private fun layout(d: PlannerQuery.Day, all: List<AgendaOccurrence>) = TimeGridPlacement(all.sortedWith(agendaOrder), d.startMillis, d.endMillis)

    @Test fun tasksAndCoursesStayChronologicalButOnlyUnoccupiedMinutesBecomeGaps() {
        val overnight = task("跨入当天", -60, 60)
        val outer = task("重叠时段", 120, 360)
        val nested = task("嵌套", 150, 180)
        val touching = task("紧接", 360, 390)
        val last = task("跨入次日", 1410, 1470)
        val rows = dayEventRows(day, layout(day, listOf(last, outer, touching, overnight, nested)))
        assertEquals(listOf(
            DayEventRow.Event(overnight, at(0), at(60)), DayEventRow.Gap(at(60), at(120)),
            DayEventRow.Event(outer, at(120), at(360)),
            DayEventRow.Event(nested, at(150), at(180)),
            DayEventRow.Event(touching, at(360), at(390)), DayEventRow.Gap(at(390), at(1410)),
            DayEventRow.Event(last, at(1410), at(1440))), rows)
    }

    @Test fun emptyAndSkippedDaysUseActualElapsedTimeNotWallClockSubtraction() {
        val ny = ZoneId.of("America/New_York")
        for ((date, hours) in listOf("2026-03-08" to 23, "2026-11-01" to 25, "2026-11-02" to 24)) {
            val d = PlannerQuery.Day(LocalDate.parse(date), ny)
            val gap = dayEventRows(d, layout(d, emptyList())).single() as DayEventRow.Gap
            assertEquals(d.startMillis, gap.start); assertEquals(d.endMillis, gap.end)
            assertEquals("$hours 小时", elapsedLabel(gap.end - gap.start, Locale.CHINESE))
            assertEquals("$hours hr", elapsedLabel(gap.end - gap.start, Locale.ENGLISH))
        }
        val skipped = PlannerQuery.Day(LocalDate.parse("2011-12-30"), ZoneId.of("Pacific/Apia"))
        assertTrue(dayEventRows(skipped, layout(skipped, emptyList())).isEmpty())
    }

    @Test fun leadingRepeatedHourAndTrailingGapsKeepUnambiguousStarts() {
        val d = PlannerQuery.Day(LocalDate.parse("2026-11-01"), ZoneId.of("America/New_York"))
        val t = d.date.atTime(1, 30)
        val (early, late) = d.zone.rules.getValidOffsets(t).map { t.toInstant(it).toEpochMilli() }
        val events = listOf(AgendaOccurrence(OccurrenceKey.Schedule("a"),"A","",early-MINUTE_MS,early,false,1,taskId = "a",owner = Owner.FRAME),
            AgendaOccurrence(OccurrenceKey.Schedule("b"),"B","",late,late+MINUTE_MS,true,1,taskId = "b",owner = Owner.DONE))
        val gaps = dayEventRows(d, layout(d, events)).filterIsInstance<DayEventRow.Gap>()
        assertEquals(listOf(DayEventRow.Gap(d.startMillis, early - MINUTE_MS), DayEventRow.Gap(early, late),
            DayEventRow.Gap(late + MINUTE_MS, d.endMillis)), gaps)
        assertEquals("1 小时", elapsedLabel(late - early, Locale.CHINESE))
        assertEquals("01:30 -04:00", clockLabel(early, d)); assertEquals("01:30 -05:00", clockLabel(late, d))
        assertEquals("1 小时 35 分钟", elapsedLabel(95 * MINUTE_MS, Locale.CHINESE))
        assertEquals("1 hr 35 min", elapsedLabel(95 * MINUTE_MS, Locale.ENGLISH))
    }

    @Test fun renderingEventRowsDoesNotCompressTheTimeScaleOrItsHitTransform() {
        val entries = layout(day, listOf(task("短时段", 90, 95)))
        val scale = DayAxisScale(day, entries, 2f, 60, 80, 48)
        assertEquals(3, dayEventRows(day, entries).size)
        assertEquals(180, scale.yAt(at(90)))
        assertEquals(60, scale.yAt(at(95)) - scale.yAt(at(90)))
        for (minute in listOf(0, 85, 90, 91, 94, 95, 100, 1440)) {
            assertEquals(at(minute), scale.instantAt(scale.yAt(at(minute)).toFloat()))
        }
    }
    @Test fun overlappingIntervalsHiddenPastTheVisibleEndsAreNotFreeAndPointsDoNotCostTime() {
        val twenty = (0 until 20).map { task("span$it", 60, if (it < 2) 70 else 160) }
        val point = task("late point", 1439, 1439)
        val sameTime = (0 until 3).map { task("midnight$it", 0, 0) }
        for (count in listOf(2, 3, 20)) {
            val p = layout(day, twenty.take(count) + point + sameTime + task("tomorrow", 1440, 1440))
            val rows = dayEventRows(day, p)
            val occupied = if (count == 2) 10 else 100
            assertEquals((1440L - occupied) * MINUTE_MS, rows.filterIsInstance<DayEventRow.Gap>().sumOf { it.end - it.start })
            val all = rows.flatMap { row -> when (row) {
                is DayEventRow.Event -> listOf(row.entry.key)
                is DayEventRow.More -> p.members(row.group).map { it.key }
                is DayEventRow.Gap -> emptyList()
            } }.toSet()
            assertEquals((twenty.take(count) + point + sameTime).map { it.key }.toSet(), all)
            val scale = DayAxisScale(day, p, 2f, 60, 80, 48)
            val midnight = p.visible.first { it.item.startMillis == at(0) }
            assertEquals(midnight.start, midnight.end)
            assertTrue(scale.yAt(at(60)) >= scale.minimumHeight(midnight))
            assertTrue(scale.height - scale.yAt(point.startMillis) >= 60)
            assertEquals(day.endMillis, scale.instantAt(scale.height.toFloat()))
            assertEquals(point.startMillis, scale.instantAt(scale.yAt(point.startMillis).toFloat()))
        }
    }
}
