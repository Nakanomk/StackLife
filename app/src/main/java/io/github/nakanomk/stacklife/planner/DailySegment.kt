package io.github.nakanomk.stacklife.planner

import io.github.nakanomk.stacklife.i18n.uiText
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.dmfs.rfc5545.recurrenceset.OfRule
import org.dmfs.rfc5545.recurrenceset.Within

@Serializable
data class ExactInterval(val startMillis: Long, val endMillis: Long) {
    init { require(endMillis >= startMillis) { uiText("End must not precede start.", "结束不能早于开始。") } }
    val point: Boolean get() = startMillis == endMillis

    fun intersects(from: Long, until: Long): Boolean = intersectsWindow(startMillis, endMillis, from, until)
}

internal fun intersectsWindow(start: Long, end: Long, from: Long, until: Long): Boolean =
    from < until && start < until && (end > from || start == end && start == from)

/** Date-only DAILY, interval one, no count. A null zone follows the device; endDay is inclusive. */
@Serializable
data class DailySegment(val fromDay: Long, val minute: Int, val zone: String?, val minutes: Int, val endDay: Long? = null) {
    init {
        require(minute in 0..1439 && minutes >= 0) { uiText("Invalid daily time or duration.", "每日时刻或时长无效。") }
        require(endDay == null || endDay >= fromDay) { uiText("The last date precedes the first.", "截止日期不能早于首日。") }
        require(fromDay in civilDays && (endDay == null || endDay in civilDays)) {
            uiText("Choose a civil date in years 1–9999.", "请选择公元 1–9999 年内的日期。")
        }
        require(zone == null || zone in regionIds) { uiText("Choose an IANA time zone.", "请选择 IANA 时区。") }
    }

    fun intervalOn(day: Long, deviceZone: ZoneId): ExactInterval? {
        if (day !in civilDays || day < fromDay || endDay != null && day > endDay) return null
        return interval(LocalDate.ofEpochDay(day), zone?.let(ZoneId::of) ?: deviceZone)
    }

    private fun interval(date: LocalDate, zoneId: ZoneId): ExactInterval? {
        // atZone shifts a clock gap forward and picks the earlier offset of a fold. A
        // skipped civil date has no occurrence: never silently move it to the following day.
        val start = date.atTime(LocalTime.ofSecondOfDay(minute * 60L)).atZone(zoneId)
        if (start.toLocalDate() != date) return null
        val millis = start.toInstant().toEpochMilli()
        return ExactInterval(millis, Math.addExact(millis, minutes * 60_000L))
    }

    /** Emits original date + untrimmed interval once, including carry-in from earlier dates. */
    internal fun between(from: Long, until: Long, deviceZone: ZoneId, nextSegmentDay: Long?,
        emit: (Long, ExactInterval) -> Unit) {
        if (from >= until) return
        val duration = minutes * 60_000L
        val earliest = if (from < Long.MIN_VALUE + duration) Long.MIN_VALUE else from - duration
        // Offset bounds (not a guessed DST margin) include both sides of any legal clock change.
        val first = maxOf(fromDay, Instant.ofEpochMilli(earliest).atOffset(ZoneOffset.MIN).toLocalDate().toEpochDay())
        val last = minOf(endDay ?: civilDays.last, nextSegmentDay?.minus(1) ?: civilDays.last,
            Instant.ofEpochMilli(until - 1).atOffset(ZoneOffset.MAX).toLocalDate().toEpochDay())
        if (first > last) return
        val zoneId = zone?.let(ZoneId::of) ?: deviceZone
        val start = LocalDate.ofEpochDay(first).allDay()
        // lib-recur 0.17.1 Freq.DAILY.fastForward steps from DTSTART. For this sole rule
        // (INTERVAL=1, no BY*/COUNT), clipping DTSTART is equivalent and bounds that work.
        val dates = Within(start, LocalDate.ofEpochDay(last + 1).allDay(), OfRule(dailyRule, start))
        for (d in dates) {
            val date = LocalDate.of(d.year, d.month + 1, d.dayOfMonth)
            val actual = interval(date, zoneId) ?: continue
            if (actual.intersects(from, until)) emit(date.toEpochDay(), actual)
        }
    }
}

private val civilDays = LocalDate.of(1, 1, 1).toEpochDay()..LocalDate.of(9999, 12, 31).toEpochDay()
private val regionIds = ZoneId.getAvailableZoneIds()
private val dailyRule = RecurrenceRule("FREQ=DAILY", RecurrenceRule.RfcMode.RFC5545_STRICT)
private fun LocalDate.allDay() = DateTime(year, monthValue - 1, dayOfMonth)
