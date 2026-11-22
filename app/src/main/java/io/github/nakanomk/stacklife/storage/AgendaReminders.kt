package io.github.nakanomk.stacklife.storage

import androidx.room.withTransaction
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.reminders.*
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.dmfs.rfc5545.recurrenceset.OfRule
import org.dmfs.rfc5545.recurrenceset.Within

private val daily = RecurrenceRule("FREQ=DAILY", RecurrenceRule.RfcMode.RFC5545_STRICT)
private val lastCivilDay = LocalDate.of(9999, 12, 31).toEpochDay()
private fun Long.allDay(): DateTime = LocalDate.ofEpochDay(this).let { DateTime(it.year, it.monthValue - 1, it.dayOfMonth) }

/** The cursor and the returned ties are actual TRIGGERS, not event starts. No horizon for a daily rule.
 * Explicit occurrence overrides are visited as independent subjects, including moved-in exceptions. */
private fun PlannerDatabase.nextPerRule(fromInclusive: Long, zone: ZoneId, group: (List<ReminderOccurrence>) -> Unit) {
    val alarms = alarmDao()
    val schedules = scheduleDao()
    val reader = ScheduleReader(schedules)
    var earliest = Long.MAX_VALUE
    val ties = ArrayList<ReminderOccurrence>()
    fun accept(row: AgendaOccurrence?, rule: ReminderRule, inherited: Boolean): Boolean {
        if (row == null || row.completed) return false
        val trigger = rule.time.triggerOrNull(row.startMillis) ?: return false
        if (trigger !in fromInclusive..earliest) return false
        if (inherited && schedules.setting(ReminderSubject.Occurrence(row.key).storageKey())?.inherit == false) return false
        if (alarms.status(row.stableKey, rule.id, trigger) != null) return false
        if (trigger < earliest) { earliest = trigger; ties.clear() }
        ties += ReminderOccurrence(row, rule, trigger)
        return true
    }

    for (entry in alarms.candidateRules(fromInclusive)) {
        if (ties.isNotEmpty()) { group(ties); ties.clear() }
        earliest = Long.MAX_VALUE
        val rule = entry.rule()
        val subject = Json.decodeFromString<ReminderSubject>(entry.subject)
        if (subject is ReminderSubject.Occurrence) {
            accept(readOccurrence(subject.key, zone), rule, false)
            continue
        }
        val lead = (rule.time as ReminderWhen.BeforeStart).leadMillis
        val lower = startAtOrAfter(fromInclusive, lead) ?: continue
        fun upper() = startAtOrAfter(earliest, lead) ?: Long.MAX_VALUE
        when (subject) {
            ReminderSubject.CourseDefault -> {
                var cursor = lower
                while (true) {
                    val start = alarms.nextCourseStart(cursor) ?: break
                    if (start > upper()) break
                    var found = false
                    for (c in alarms.coursesAt(start)) {
                        if (accept(readOccurrence(OccurrenceKey.Course(c.key()), zone), rule, true)) found = true
                    }
                    if (found || start == Long.MAX_VALUE) break
                    cursor = start + 1
                }
            }
            is ReminderSubject.Schedule -> {
                val series = reader.load(subject.id) ?: continue
                if (!series.header.root.enabled || series.header.owner == Owner.REMOVED || series.header.owner == Owner.DONE) continue
                fun consider(key: OccurrenceKey.Schedule, i: ExactInterval, version: Long = series.header.root.version) {
                    if (i.startMillis !in lower..upper()) return
                    val row = series.row(key, i, version)
                    if (!schedules.completed(row.stableKey)) accept(row, rule, true)
                }
                when (val p = series.pattern) {
                    is SchedulePattern.Once -> consider(OccurrenceKey.Schedule(subject.id), p.interval)
                    is SchedulePattern.Daily -> {
                        val near = Instant.ofEpochMilli(lower).atOffset(ZoneOffset.MIN).toLocalDate().toEpochDay()
                        for ((index, seg) in p.segments.withIndex()) {
                            val first = maxOf(near, seg.fromDay)
                            val last = minOf(seg.endDay ?: lastCivilDay, p.segments.getOrNull(index + 1)?.fromDay?.minus(1) ?: lastCivilDay,
                                Instant.ofEpochMilli(upper()).atOffset(ZoneOffset.MAX).toLocalDate().toEpochDay())
                            if (first > last) continue
                            val start = first.allDay()
                            for (d in Within(start, (last + 1).allDay(), OfRule(daily, start))) {
                                val date = LocalDate.of(d.year, d.month + 1, d.dayOfMonth)
                                if (date.atStartOfDay().toInstant(ZoneOffset.MAX).toEpochMilli() > upper()) break
                                val day = date.toEpochDay()
                                val interval = seg.intervalOn(day, zone) ?: continue
                                if (interval.startMillis !in lower..upper() || schedules.exception(subject.id, day) != null) continue
                                consider(OccurrenceKey.Schedule(subject.id, day), interval)
                            }
                        }
                        // A move into a completely different year is not near the original date cursor.
                        for (ex in alarms.moved(subject.id, lower, upper())) {
                            if (requireNotNull(ex.startMillis) > upper()) break
                            consider(OccurrenceKey.Schedule(subject.id, ex.originalDay), requireNotNull(ex.actual()),
                                maxOf(ex.version, series.header.root.version))
                        }
                    }
                }
            }
            is ReminderSubject.Occurrence -> error("Handled above")
        }
    }
    if (ties.isNotEmpty()) group(ties)
}

internal suspend fun PlannerDatabase.nextReminderGroup(fromInclusive: Long, zone: ZoneId): List<ReminderOccurrence> = withTransaction {
    val found = ArrayList<ReminderOccurrence>()
    var first = Long.MAX_VALUE
    nextPerRule(fromInclusive, zone) { items ->
        val trigger = items.first().triggerMillis
        if (trigger < first) { first = trigger; found.clear() }
        if (trigger == first) found.addAll(items)
    }
    found.sortWith(reminderOrder)
    found
}

/** Keep one frontier per rule, not an unbounded occurrence expansion. Android installs only the
 * minimum deadline. If its inexact wake is late, the 5-minute and at-start rules are still known
 * to have been scheduled before the wake; a newly configured past reminder is never swept up. */
internal suspend fun PlannerDatabase.alarmFrontier(fromInclusive: Long, zone: ZoneId): List<AlarmStamp> = withTransaction {
    buildList { nextPerRule(fromInclusive, zone) { items -> for (item in items) add(item.stamp()) } }
}

/** Inside the caller's transaction. A tag/action is never enough to authorize delivery or completion. */
internal fun PlannerDatabase.currentReminder(stamp: AlarmStamp, zone: ZoneId, requireVersion: Boolean): ReminderOccurrence? {
    val row = readOccurrence(stamp.key, zone) ?: return null
    if (row.completed || requireVersion && row.version != stamp.version) return null
    val target = ReminderSubject.Occurrence(stamp.key)
    val rules = scheduleDao().run { effectiveRules(target, policy(target)) }
    val rule = rules.firstOrNull { it.id == stamp.ruleId && it.time == stamp.time && it.time.triggerOrNull(row.startMillis) == stamp.triggerMillis } ?: return null
    return ReminderOccurrence(row, rule, stamp.triggerMillis)
}
