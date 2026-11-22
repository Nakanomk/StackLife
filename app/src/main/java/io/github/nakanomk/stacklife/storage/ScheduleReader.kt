package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.planner.*
import java.time.ZoneId

internal data class LoadedSchedule(val header: AgendaHeader, val pattern: SchedulePattern) {
    fun summary() = ScheduleSummary(header.root.id, pattern, header.root.enabled, header.root.role)
    fun row(key: OccurrenceKey.Schedule, interval: ExactInterval, version: Long = header.root.version): AgendaOccurrence {
        val (root, title, notes, owner) = header
        return AgendaOccurrence(key, root.role?.label() ?: requireNotNull(title), notes.orEmpty(), interval.startMillis,
            interval.endMillis, owner == Owner.DONE, version, root.taskId, owner, role = root.role)
    }
}
internal fun ScheduleRow.pattern(segments: List<DailySegment>) = if (startMillis != null)
    SchedulePattern.Once(ExactInterval(startMillis, requireNotNull(endMillis))) else SchedulePattern.Daily(segments)

/** Call inside the caller's single read transaction. The database never materializes future days. */
internal class ScheduleReader(private val dao: ScheduleDao) {
    fun load(id: String): LoadedSchedule? = dao.header(id)?.let { h -> LoadedSchedule(h, h.root.pattern(
        if (h.root.startMillis == null) dao.segments(listOf(id)).map { it.segment } else emptyList())) }

    fun loadMany(ids: List<String>): Map<String, LoadedSchedule> = buildMap {
        for (batch in ids.chunked(900)) {
            val headers = dao.headers(batch)
            val daily = headers.mapNotNull { if (it.root.startMillis == null) it.root.id else null }
            val segments = if (daily.isEmpty()) emptyMap() else dao.segments(daily).groupBy({ it.scheduleId }, { it.segment })
            for (h in headers) put(h.root.id, LoadedSchedule(h, h.root.pattern(segments[h.root.id].orEmpty())))
        }
    }

    fun resolve(key: OccurrenceKey.Schedule, zone: ZoneId): AgendaOccurrence? =
        load(key.scheduleId)?.let { resolve(key, zone, it) }

    fun resolve(key: OccurrenceKey.Schedule, zone: ZoneId, series: LoadedSchedule): AgendaOccurrence? {
        if (!series.header.root.enabled || series.header.owner == Owner.REMOVED) return null
        if (key.originalDay != null && series.header.owner == Owner.DONE) return null
        val row = when (val pattern = series.pattern) {
            is SchedulePattern.Once -> if (key.originalDay == null) series.row(key, pattern.interval) else null
            is SchedulePattern.Daily -> {
                val day = key.originalDay ?: return null
                val exception = dao.exception(key.scheduleId, day)
                if (exception != null) exception.actual()?.let { series.row(key, it, maxOf(exception.version, series.header.root.version)) }
                else pattern.segmentFor(day)?.intervalOn(day, zone)?.let { series.row(key, it) }
            }
        } ?: return null
        return if (!row.completed && dao.completed(row.stableKey)) row.copy(completed = true) else row
    }

    fun between(from: Long, until: Long, zone: ZoneId): List<AgendaOccurrence> {
        if (from >= until) return emptyList()
        val once = dao.once(from, until)
        val result = ArrayList<AgendaOccurrence>(once.size)
        for (h in once) if (h.owner != Owner.REMOVED) {
            val i = ExactInterval(requireNotNull(h.root.startMillis), requireNotNull(h.root.endMillis))
            result.add(LoadedSchedule(h, SchedulePattern.Once(i)).row(OccurrenceKey.Schedule(h.root.id), i))
        }
        val headers = dao.series()
        if (headers.isNotEmpty()) {
            val emitted = HashMap<OccurrenceKey.Schedule, AgendaOccurrence>()
            val loaded = HashMap<String, LoadedSchedule>(headers.size)
            for (batch in headers.chunked(900)) {
                val ids = batch.map { it.root.id }
                val segments = dao.segments(ids).groupBy({ it.scheduleId }, { it.segment })
                var first = Long.MAX_VALUE; var last = Long.MIN_VALUE
                for (h in batch) {
                    val rules = segments.getValue(h.root.id)
                    val series = LoadedSchedule(h, SchedulePattern.Daily(rules))
                    loaded[h.root.id] = series
                    for (n in rules.indices) rules[n].between(from, until, zone, rules.getOrNull(n + 1)?.fromDay) { day, actual ->
                        val key = OccurrenceKey.Schedule(h.root.id, day)
                        first = minOf(first, day); last = maxOf(last, day)
                        emitted[key] = series.row(key, actual)
                    }
                }
                if (first <= last) for (ex in dao.originals(ids, first, last)) emitted.remove(OccurrenceKey.Schedule(ex.scheduleId, ex.originalDay))
            }
            // A moved-in exception need not have a generated occurrence anywhere near this window.
            for (ex in dao.moved(from, until)) {
                val series = loaded[ex.scheduleId] ?: continue
                val key = OccurrenceKey.Schedule(ex.scheduleId, ex.originalDay)
                emitted[key] = series.row(key, requireNotNull(ex.actual()), maxOf(ex.version, series.header.root.version))
            }
            result.addAll(emitted.values)
        }
        markCompleted(result)
        result.sortWith(agendaOrder)
        return result
    }

    fun markCompleted(rows: MutableList<AgendaOccurrence>) {
        for (start in rows.indices step 900) {
            val batch = rows.subList(start, minOf(rows.size, start + 900))
            val marks = dao.completed(batch.map { it.stableKey }).toHashSet()
            if (marks.isNotEmpty()) for (i in batch.indices) {
                val row = batch[i]
                if (!row.completed && row.stableKey in marks) batch[i] = row.copy(completed = true)
            }
        }
    }
}
internal fun ExceptionRow.actual(): ExactInterval? = startMillis?.let { ExactInterval(it, requireNotNull(endMillis)) }
