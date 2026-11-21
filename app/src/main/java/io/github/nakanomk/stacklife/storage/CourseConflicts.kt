package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.courses.MAX_COURSE_FIELD
import io.github.nakanomk.stacklife.courses.ScheduledCourse
import io.github.nakanomk.stacklife.planner.DayBlock
import java.util.PriorityQueue

// Longer imported fields need correction, not silent truncation. Existing task/legacy labels
// may already exceed this limit; abbreviate only their diagnostic copy, never their source.
internal const val MAX_PARTNER_NAMES = 8
private const val MAX_IMPORT_CONFLICTS = 256
internal fun String.diagnosticText(): String {
    if (length <= MAX_COURSE_FIELD) return this
    val end = if (this[MAX_COURSE_FIELD - 1].isHighSurrogate()) MAX_COURSE_FIELD - 1 else MAX_COURSE_FIELD
    return substring(0, end) + "…"
}
internal fun CourseRow.label(): String {
    val room = meeting.room.diagnosticText()
    return "${meeting.title.diagnosticText()} · ${termId.diagnosticText()}" + if (room.isBlank()) "" else " · $room"
}

private inline fun <T> List<T>.startsBefore(from: Int, end: Long, start: (T) -> Long): Int {
    var lo = from; var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (start(this[mid]) < end) lo = mid + 1 else hi = mid
    }
    return lo
}

/** Task slots are start-ordered and mutually disjoint. Their overlaps form one index range. */
private class TaskWindow(private val tasks: List<DayBlock>) {
    var from = 0
        private set
    var until = 0
        private set
    fun move(row: CourseRow) {
        while (from < tasks.size && tasks[from].endMillis <= row.meeting.startMillis) from++
        until = tasks.startsBefore(from, row.meeting.endMillis) { it.startMillis }
    }
}

private val byEnd = Comparator<CourseRow> { a, b -> a.meeting.endMillis.compareTo(b.meeting.endMillis) }
private fun PriorityQueue<CourseRow>.expire(start: Long) {
    while (isNotEmpty() && element().meeting.endMillis <= start) remove()
}

/** Every earlier course still active at this start overlaps. Heap sizes count even a dense
 * legacy timetable without iterating its pairs. Keep untouched terms separate for import. */
private inline fun scanStarts(rows: List<CourseRow>, touched: (CourseRow) -> Boolean,
    visit: (Int, Boolean, Collection<CourseRow>, Collection<CourseRow>) -> Unit) {
    val changed = PriorityQueue(byEnd)
    val existing = PriorityQueue(byEnd)
    for ((i, row) in rows.withIndex()) {
        val start = row.meeting.startMillis
        changed.expire(start); existing.expire(start)
        val included = touched(row)
        visit(i, included, changed, existing)
        (if (included) changed else existing).add(row)
    }
}

internal fun importConflicts(rows: List<CourseRow>, tasks: List<DayBlock>, touched: (CourseRow) -> Boolean,
    coursePair: (CourseRow, CourseRow) -> Unit, taskPair: (CourseRow, DayBlock) -> Unit) {
    fun scan(report: Boolean): Long {
        var count = 0L
        val slots = TaskWindow(tasks)
        scanStarts(rows, touched) { i, included, priorTouched, priorOther ->
            val row = rows[i]
            if (included) slots.move(row)
            if (!report) {
                count += priorTouched.size.toLong()
                if (included) count += priorOther.size.toLong() + (slots.until - slots.from)
                if (count > MAX_IMPORT_CONFLICTS) throw InvalidCommand(
                    uiText({ "These changes create more than $MAX_IMPORT_CONFLICTS overlapping pairs of classes or tasks. Check for duplicate classes or adjust their times and try again." }, { "本次课程变更与课程或任务重叠超过 $MAX_IMPORT_CONFLICTS 对，无法逐项核对。请检查重复课表或调整时段后重试。" }))
            } else {
                for (other in priorTouched) coursePair(other, row)
                if (included) {
                    for (other in priorOther) coursePair(row, other)
                    for (t in slots.from until slots.until) taskPair(row, tasks[t])
                }
            }
        }
        return count
    }
    // Reject before visiting pairs or formatting any text. Sparse imports need no second sweep.
    if (scan(false) != 0L) scan(true)
}

internal class CourseOccupancy(rows: List<CourseRow>, tasks: List<DayBlock>) {
    val courses: List<ScheduledCourse>
    val taskIds = mutableSetOf<String>()
    init {
        val slots = TaskWindow(tasks)
        var markedUntil = 0
        courses = buildList(rows.size) {
            scanStarts(rows, { true }) { i, _, prior, _ ->
                val row = rows[i]
                slots.move(row)
                for (t in maxOf(markedUntil, slots.from) until slots.until) taskIds.add(tasks[t].taskId)
                markedUntil = maxOf(markedUntil, slots.until)
                val until = rows.startsBefore(i + 1, row.meeting.endMillis) { it.meeting.startMillis }
                val count = prior.size.toLong() + (until - i - 1) + (slots.until - slots.from)
                val names = when {
                    count == 0L -> emptyList()
                    count > MAX_PARTNER_NAMES -> listOf(uiText({ "$count overlapping classes or tasks; not listed individually" }, { "共 $count 个重叠的课程或任务，未逐项列出" }))
                    else -> buildList(count.toInt()) {
                        for (other in prior) add(other.label())
                        for (j in i + 1 until until) add(rows[j].label())
                        for (t in slots.from until slots.until) add(tasks[t].title.diagnosticText())
                    }
                }
                add(ScheduledCourse(row.termId, row.source, row.meeting, names))
            }
        }
    }
}
