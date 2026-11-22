package io.github.nakanomk.stacklife.planner

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DailySegmentTest {
    private val ny = ZoneId.of("America/New_York")
    private val utc = ZoneId.of("UTC")
    private fun day(date: String) = LocalDate.parse(date).toEpochDay()
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private fun onDate(segment: DailySegment, date: String, zone: ZoneId, next: Long? = null): List<Pair<Long, ExactInterval>> {
        val d = LocalDate.parse(date)
        return buildList { segment.between(d.atStartOfDay(zone).toInstant().toEpochMilli(),
            d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),zone,next) { key, span -> add(key to span) } }
    }

    @Test fun clockGapFoldAndSkippedDateFollowTheSeriesZone() {
        val initial = day("2010-01-01")
        val gap = DailySegment(initial,150,ny.id,0)
        val spring = gap.intervalOn(day("2026-03-08"),utc)!!
        assertEquals(ExactInterval(millis("2026-03-08T07:30:00Z"),millis("2026-03-08T07:30:00Z")),spring)
        val fold = gap.copy(minute=90).intervalOn(day("2026-11-01"),utc)!!
        assertEquals(millis("2026-11-01T05:30:00Z"),fold.startMillis)
        val apia = ZoneId.of("Pacific/Apia")
        val local = DailySegment(initial,0,null,0)
        assertNull(local.intervalOn(day("2011-12-30"),apia))
        assertEquals(listOf(day("2011-12-31")),onDate(local,"2011-12-31",apia).map { it.first })
        assertEquals(listOf(day("2011-12-30")),onDate(local,"2011-12-30",utc).map { it.first })
    }

    @Test fun crossingMidnightRetainsOriginalDayAcrossACutoverAndLastDate() {
        val old = DailySegment(day("2026-09-01"),23*60,null,26*60,day("2026-09-10"))
        val actual = onDate(old,"2026-09-05",utc,next=day("2026-09-05"))
        assertEquals(listOf(day("2026-09-03"),day("2026-09-04")),actual.map { it.first })
        assertEquals(millis("2026-09-05T01:00:00Z"),actual.first().second.endMillis)
        assertTrue(onDate(old,"2026-09-13",utc).isEmpty())
        val midnight = old.copy(minute=0,minutes=0)
        val one = onDate(midnight,"2026-09-10",utc).single()
        assertTrue(one.second.point); assertEquals(day("2026-09-10"),one.first)
        assertTrue(onDate(midnight,"2026-09-11",utc).isEmpty())
    }

    @Test fun remoteWindowReturnsOnlyTheIntersectingOccurrenceNotTheSeriesPrefix() {
        val segment = DailySegment(day("0001-01-01"),23*60+59,"Asia/Shanghai",0)
        val end = onDate(segment,"9999-12-30",ZoneId.of("Asia/Shanghai")).single()
        assertEquals(day("9999-12-30"),end.first)
        val utcMillis = Instant.parse("9999-12-30T15:59:00Z").toEpochMilli()
        assertEquals(ExactInterval(utcMillis,utcMillis),end.second)
        assertFalse(end.second.intersects(utcMillis-1,utcMillis))
        assertTrue(end.second.intersects(utcMillis,utcMillis+1))
        val longEvent = segment.copy(minutes=24*60)
        assertEquals(Duration.ofDays(1).toMillis(),longEvent.intervalOn(end.first,utc)!!.let { it.endMillis-it.startMillis })
        assertNull(segment.intervalOn(day("9999-12-31") + 1, utc))
        segment.between(Long.MAX_VALUE - 1, Long.MAX_VALUE, utc, null) { _, _ -> fail("Outside the civil-date picker domain") }
        assertThrows(IllegalArgumentException::class.java) { segment.copy(zone = "+14:00") }
    }
}
