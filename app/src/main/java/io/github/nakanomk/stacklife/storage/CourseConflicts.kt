package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.courses.MAX_COURSE_FIELD
import io.github.nakanomk.stacklife.courses.ScheduledCourse
import io.github.nakanomk.stacklife.planner.*
import java.util.PriorityQueue

// Longer imported fields need correction, not silent truncation. Existing task/legacy labels
// may already exceed this limit; abbreviate only their diagnostic copy, never their source.
internal const val MAX_PARTNER_NAMES = 8
internal fun String.diagnosticText(): String {
    if (length <= MAX_COURSE_FIELD) return this
    val end = if (this[MAX_COURSE_FIELD - 1].isHighSurrogate()) MAX_COURSE_FIELD - 1 else MAX_COURSE_FIELD
    return substring(0, end) + "…"
}
internal fun CourseRow.label(): String {
    val room = meeting.room.diagnosticText()
    return if (room.isBlank()) meeting.title.diagnosticText() else "${meeting.title.diagnosticText()} ($room)"
}

private inline fun <T> List<T>.startsBefore(from: Int, end: Long, start: (T) -> Long): Int {
    var lo = from; var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (start(this[mid]) < end) lo = mid + 1 else hi = mid
    }
    return lo
}

/** Nested/crossing intervals need independent end ranks. A point at the window's start still counts. */
private class TaskWindow(private val tasks: List<AgendaOccurrence>) {
    private val ends = tasks.sortedWith(compareBy({ it.endMillis }, { it.point }))
    private val lastOccupied = LongArray(tasks.size).also { a ->
        var last = Long.MIN_VALUE
        tasks.forEachIndexed { i,t -> last = maxOf(last,if (t.point) t.startMillis else t.endMillis - 1); a[i] = last }
    }
    fun count(start: Long, end: Long): Int {
        val until = tasks.startsBefore(0, end) { it.startMillis }
        var low = 0; var high = ends.size
        while (low < high) {
            val mid = (low + high) ushr 1
            val t = ends[mid]
            if (t.endMillis < start || t.endMillis == start && !t.point) low = mid + 1 else high = mid
        }
        return until - low
    }
    fun visit(start: Long, end: Long, limit: Int, accept: (AgendaOccurrence) -> Unit) {
        var remaining = limit
        if (remaining <= 0) return
        var lo = 0; var hi = lastOccupied.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (lastOccupied[m] < start) lo = m + 1 else hi = m }
        for (i in lo until tasks.size) {
            val t = tasks[i]
            if (t.startMillis >= end) break
            if (intersectsWindow(t.startMillis, t.endMillis, start, end)) {
                accept(t)
                if (--remaining == 0) break
            }
        }
    }
}

private val byEnd = Comparator<CourseRow> { a, b -> a.meeting.endMillis.compareTo(b.meeting.endMillis) }
private fun PriorityQueue<CourseRow>.expire(start: Long) {
    while (isNotEmpty() && element().meeting.endMillis <= start) remove()
}

internal class CourseOverlapSummary {
    var count: Long = 0; private set
    val examples = ArrayList<CourseConflict>()
    fun add(n: Int, sample: (Int) -> Unit) {
        count += n.toLong()
        val limit = minOf(n, 256 - examples.size)
        if (limit > 0) sample(limit)
    }
    fun record(row: CourseRow, other: Overlap) {
        examples.add(CourseConflict(row.key(), row.meeting.startMillis, row.meeting.endMillis, other))
    }
}

/** Only diagnostic examples are bounded. Count every pair; density never rejects a write. */
internal fun importConflicts(rows: List<CourseRow>, tasks: List<AgendaOccurrence>, touched: (CourseRow) -> Boolean):
    Map<Pair<String, String>, CourseOverlapSummary> {
    val result = mutableMapOf<Pair<String, String>, CourseOverlapSummary>()
    val changed = mutableMapOf<Pair<String, String>, PriorityQueue<CourseRow>>()
    val existing = PriorityQueue(byEnd)
    val slots = TaskWindow(tasks)
    for (row in rows) {
        val start = row.meeting.startMillis; val end = row.meeting.endMillis
        existing.expire(start)
        for (heap in changed.values) heap.expire(start)
        if (touched(row)) {
            val group = row.source to row.termId
            val out = result.getOrPut(group, ::CourseOverlapSummary)
            fun prior(heap: PriorityQueue<CourseRow>) = out.add(heap.size) { n -> heap.asSequence().take(n).forEach { out.record(row, it.overlap()) } }
            prior(existing); for (heap in changed.values) prior(heap)
            out.add(slots.count(start, end)) { n -> slots.visit(start, end, n) { out.record(row, Overlap(it.stableKey, it.title, it.startMillis, it.endMillis)) } }
            changed.getOrPut(group) { PriorityQueue(byEnd) }.add(row)
        } else {
            for ((group, heap) in changed) {
                val out = result.getValue(group)
                out.add(heap.size) { n -> heap.asSequence().take(n).forEach { out.record(it, row.overlap()) } }
            }
            existing.add(row)
        }
    }
    return result
}

internal class CourseOccupancy(rows: List<CourseRow>, tasks: List<AgendaOccurrence>) {
    val courses: List<ScheduledCourse>
    init {
        val slots = TaskWindow(tasks)
        val prior = PriorityQueue(byEnd)
        courses = buildList(rows.size) {
            for ((i, row) in rows.withIndex()) {
                val m = row.meeting
                prior.expire(m.startMillis)
                val until = rows.startsBefore(i + 1, m.endMillis) { it.meeting.startMillis }
                val tasksHere = slots.count(m.startMillis, m.endMillis)
                val count = prior.size.toLong() + until - i - 1 + tasksHere
                val names = when {
                    count == 0L -> emptyList()
                    count > MAX_PARTNER_NAMES -> listOf(uiText({ "$count overlapping classes or tasks; not listed individually" }, { "共 $count 个重叠的课程或任务，未逐项列出" }))
                    else -> buildList(count.toInt()) {
                        for (other in prior) add(other.label())
                        for (j in i + 1 until until) add(rows[j].label())
                        slots.visit(m.startMillis, m.endMillis, tasksHere) { add(it.title.diagnosticText()) }
                    }
                }
                add(ScheduledCourse(row.termId, row.source, m, names))
                prior.add(row)
            }
        }
    }
}

/** Sorted effective instants, including points. Only diagnostic names are bounded. */
internal fun markScheduleConflicts(rows: MutableList<AgendaOccurrence>) {
    if (rows.size < 2) return
    val index = TaskWindow(rows)
    for (i in rows.indices) {
        val o = rows[i]
        if (o.course != null) continue
        val end = if (o.point) Math.addExact(o.startMillis,1) else o.endMillis
        val count = index.count(o.startMillis,end) - 1
        if (count <= 0) continue
        val names = if (count > MAX_PARTNER_NAMES)
            listOf(uiText("$count overlapping events; not listed individually", "共 $count 个重叠事件，未逐项列出"))
        else buildList(count) {
            index.visit(o.startMillis,end,count+1) { other -> if (other.key != o.key) add(other.title.diagnosticText()) }
        }
        rows[i] = o.copy(conflicts = names)
    }
}
