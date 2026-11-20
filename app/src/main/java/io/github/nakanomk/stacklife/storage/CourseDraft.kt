package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.DayBlock
import io.github.nakanomk.stacklife.planner.PlannerChange

internal data class ReviewedCourses(val term: TermSpec, val meetings: List<CourseMeeting>)
private data class TermContents(val term: TermRow, val rows: Set<CourseRow>)
internal class CourseWrites(val termId: String, val removeTerm: Boolean, val term: TermRow?,
    val removed: List<CourseRow>, val added: List<CourseRow>) {
    fun applyTo(dao: CourseDao) {
        if (removeTerm) { dao.remove(termId, HUST_SOURCE); return }
        term?.let(dao::put)
        if (removed.isNotEmpty()) dao.delete(removed)
        if (added.isNotEmpty()) dao.insert(added)
    }
}

/** Overlay has entries only for local source/term pairs explicitly touched in this batch. */
internal class CourseDraft(private val dao: CourseDao) {
    private val original = mutableMapOf<String, TermContents?>()
    private val current = linkedMapOf<String, TermContents?>()
    private fun load(id: String): TermContents? {
        if (id !in original) original[id] = dao.term(id, HUST_SOURCE)?.let { TermContents(it, dao.meetings(id, HUST_SOURCE).toSet()) }
        return original[id]
    }
    fun replace(draft: ReviewedCourses) {
        val spec = draft.term
        spec.problem()?.let { throw InvalidCommand(it) }
        if (draft.meetings.isEmpty()) throw InvalidCommand("没有有效课程。现有课表未被替换。")
        val row = TermRow(spec.id, HUST_SOURCE, spec.firstMonday.toEpochDay(), spec.weeks, spec.initial, spec.switchDate?.toEpochDay())
        val meetings = LinkedHashSet<CourseRow>(draft.meetings.size)
        for (c in draft.meetings) {
            courseFieldProblem(c.title, c.room)?.let { throw InvalidCommand("第 ${c.week} 周、星期 ${c.day} 第 ${c.firstPeriod} 节：$it") }
            if (c.week !in 1..spec.weeks || c.day !in 1..7 || c.firstPeriod !in 1..12 || c.lastPeriod !in c.firstPeriod..12)
                throw InvalidCommand("课程的名称、周次或节次无效，请重新核对。")
            if (c != spec.meeting(c.week, c.day, c.title, c.room, c.firstPeriod, c.lastPeriod))
                throw InvalidCommand("课时与已确认的学期作息不一致。")
            meetings.add(CourseRow(spec.id, HUST_SOURCE, c))
        }
        load(spec.id)
        current[spec.id] = TermContents(row, meetings)
    }
    fun remove(id: String) {
        val contents = if (id in current) current[id] else load(id)
        if (contents == null) throw InvalidCommand("所选学期没有可移除的华科课表。")
        current[id] = null
    }
    fun intersecting(start: Long, end: Long): List<CourseRow> {
        val stored = dao.intersecting(start, end)
        if (current.isEmpty()) return stored
        return buildList {
            for (row in stored) if (row.source != HUST_SOURCE || row.termId !in current) add(row)
            for (contents in current.values) if (contents != null) for (row in contents.rows) {
                if (row.meeting.startMillis < end && row.meeting.endMillis > start) add(row)
            }
        }.sortedBy { it.meeting.startMillis }
    }
    fun finish(changes: MutableList<PlannerChange>, tasks: (Long, Long) -> List<DayBlock>): List<CourseWrites> {
        if (current.isEmpty()) return emptyList()
        val writes = ArrayList<CourseWrites>()
        var from = Long.MAX_VALUE; var until = Long.MIN_VALUE
        for (contents in current.values) if (contents != null) for (row in contents.rows) {
            from = minOf(from, row.meeting.startMillis); until = maxOf(until, row.meeting.endMillis)
        }
        if (from < until) {
            fun warn(a: CourseRow, other: String) {
                changes.add(PlannerChange(a.termId, "冲突 · ${a.meeting.title.diagnosticText()}", a.summary(), "与 $other 重叠；保留双方"))
            }
            importConflicts(intersecting(from, until), tasks(from, until), { it.source == HUST_SOURCE && it.termId in current },
                { a, b -> warn(a, b.summary()) },
                { c, t -> warn(c, "${t.title.diagnosticText()} ${intervalLabel(t.startMillis, t.endMillis, campusZone)}") })
        }
        for ((id, next) in current) {
            val old = original[id]
            val was = old?.rows.orEmpty(); val now = next?.rows.orEmpty()
            val removed = was.filterNot { it in now }; val added = now.filterNot { it in was }
            if (old?.term != next?.term) changes.add(PlannerChange(id, "学期 · $id", old?.term?.spec()?.summary(), next?.term?.spec()?.summary()))
            for (row in removed) changes.add(PlannerChange(id, "移除课程 · ${row.meeting.title}", row.summary(), null))
            for (row in added) changes.add(PlannerChange(id, "新增课程 · ${row.meeting.title}", null, row.summary()))
            if (old?.term != next?.term || removed.isNotEmpty() || added.isNotEmpty()) {
                writes.add(CourseWrites(id, next == null, next?.term?.takeUnless { it == old?.term }, removed, added))
            }
        }
        return writes
    }
}

private fun CourseRow.summary() = "${label()} · 第${meeting.week}周 · 第${meeting.firstPeriod}–${meeting.lastPeriod}节 · " +
    intervalLabel(meeting.startMillis, meeting.endMillis, campusZone)
private fun TermSpec.summary() = "$firstMonday 起，$weeks 周，${if (initial == BellSchedule.SUMMER) "夏令" else "冬令"}作息" +
    (switchDate?.let { "，$it 起切换" } ?: "")
