package io.github.nakanomk.stacklife.planner

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CountdownDayTest {
    private fun event(date: String, mode: CountdownMode = CountdownMode.COUNT_DOWN) = Countdown("card", "Day", LocalDate.parse(date).toEpochDay(), mode, false, 0)

    @Test fun leapAndSpringForwardCountCivilDaysWithoutUsingElapsedHours() {
        assertEquals(DayAmount(2, DayDirection.UNTIL), event("2028-03-01").amount(LocalDate.parse("2028-02-28")))
        val day = event("2026-03-09")
        val now = Instant.parse("2026-03-08T05:00:00Z")
        assertEquals(DayAmount(1, DayDirection.UNTIL), day.amount(now.atZone(ZoneId.of("America/New_York")).toLocalDate()))
        assertEquals(DayAmount(2, DayDirection.UNTIL), day.amount(now.atZone(ZoneId.of("America/Los_Angeles")).toLocalDate()))
    }

    @Test fun targetDayIsZeroAndExpiredCountdownNeverShowsNegativeTimeRemaining() {
        val day = event("2026-10-04")
        val target = LocalDate.ofEpochDay(day.targetDay)
        assertEquals(DayAmount(0, DayDirection.TODAY), day.amount(target))
        assertEquals(DayAmount(1, DayDirection.PAST), day.amount(target.plusDays(1)))
        assertEquals(DayAmount(1, DayDirection.SINCE), day.copy(mode=CountdownMode.ACCUMULATE).amount(target.plusDays(1)))
    }
}
