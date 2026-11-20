package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.DayBlock
import io.github.nakanomk.stacklife.planner.PlannerChange
import io.github.nakanomk.stacklife.planner.PlannerCommand

internal data class ReviewedCourses(val term: TermSpec, val meetings: List<CourseMeeting>)
private data class TermContents(val term: TermRow, val rows: Set<CourseRow>)
internal class CourseWrites(val termId: String, val source: String, val removeTerm: Boolean, val term: TermRow?,
    val removed: List<CourseRow>, val added: List<CourseRow>) {
    fun applyTo(dao: CourseDao) {
        if (removeTerm) { dao.remove(termId, source); return }
        term?.let(dao::put)
        if (removed.isNotEmpty()) dao.delete(removed)
        if (added.isNotEmpty()) dao.insert(added)
    }
}

private class SourceDraft(val source: String, private val dao: CourseDao) {
    val original = mutableMapOf<String, TermContents?>()
    val current = linkedMapOf<String, TermContents?>()
    fun spec(id: String): TermSpec? =
        (if (id in current) current[id]?.term else dao.term(id, source))?.spec()
    fun load(id: String): TermContents? {
        if (id in current) return current[id]
        if (id !in original) original[id] = dao.term(id, source)?.let { TermContents(it, dao.meetings(id, source).toSet()) }
        return original[id]
    }
}

private fun TermSpec.row(source: String) = TermRow(id, source, firstMonday.toEpochDay(), weeks, initial, switchDate?.toEpochDay())

/** Source-specific overlays: HUB may replace its collection; manual edits only replace one exact pattern. */
internal class CourseDraft(private val dao: CourseDao) {
    private val sources = mutableMapOf<String, SourceDraft>()
    private fun source(name: String) = sources.getOrPut(name) { SourceDraft(name, dao) }
    private fun termSpec(id: String, name: String): TermSpec? {
        val overlay = sources[name]
        return if (overlay != null) overlay.spec(id) else dao.term(id, name)?.spec()
    }
    fun replace(draft: ReviewedCourses) {
        val spec = draft.term
        spec.problem()?.let { throw InvalidCommand(it) }
        if (draft.meetings.isEmpty()) throw InvalidCommand("没有有效课程。现有课表未被替换。")
        val manualTerm = termSpec(spec.id, MANUAL_SOURCE)
        if (manualTerm != null && manualTerm != spec)
            throw InvalidCommand("同名学期已有手动课程，首周、总周数或作息不同。请沿用原学期设置，或使用另一个学期名称。")
        val meetings = LinkedHashSet<CourseRow>(draft.meetings.size)
        for (c in draft.meetings) {
            courseFieldProblem(c.title, c.room)?.let { throw InvalidCommand("第 ${c.week} 周、星期 ${c.day} 第 ${c.firstPeriod} 节：$it") }
            if (c.week !in 1..spec.weeks || c.day !in 1..7 || c.firstPeriod !in 1..12 || c.lastPeriod !in c.firstPeriod..12)
                throw InvalidCommand("课程的名称、周次或节次无效，请重新核对。")
            if (c != spec.meeting(c.week, c.day, c.title, c.room, c.firstPeriod, c.lastPeriod))
                throw InvalidCommand("课时与已确认的学期作息不一致。")
            meetings.add(CourseRow(spec.id, HUST_SOURCE, c))
        }
        val hub = source(HUST_SOURCE)
        hub.load(spec.id)
        hub.current[spec.id] = TermContents(spec.row(HUST_SOURCE), meetings)
    }
    fun remove(id: String) {
        val hub = source(HUST_SOURCE)
        if (hub.load(id) == null) throw InvalidCommand("所选学期没有可移除的华科课表。")
        hub.current[id] = null
    }
    fun manual(cmd: PlannerCommand.ManualCourse) {
        val id = cmd.termId
        val manual = source(MANUAL_SOURCE)
        val previous = manual.load(id)
        val existing = previous?.term?.spec() ?: termSpec(id, HUST_SOURCE)
        val term = cmd.createTerm?.also {
            if (existing != null || cmd.before != null || id != it.id)
                throw InvalidCommand("不能用新学期覆盖已有课表，请选择已有学期或使用其他名称。")
            it.problem()?.let { error -> throw InvalidCommand(error) }
        } ?: existing ?: throw InvalidCommand("所选学期已不存在，请重新选择。")
        if (cmd.before == null && cmd.after == null) throw InvalidCommand("请选择一门手动课程。")
        val rows = previous?.rows.orEmpty().toMutableSet()
        cmd.before?.let { before ->
            val found = mutableSetOf<Int>()
            rows.removeAll { row ->
                if (before.pattern.matches(row.meeting)) { found.add(row.meeting.week); true } else false
            }
            if (found.isEmpty() || found != before.weeks) throw InvalidCommand("这门手动课程的周次已改变，请关闭编辑器并重新打开。")
        }
        cmd.after?.let { after ->
            val p = after.pattern
            if (p.title.isBlank()) throw InvalidCommand("请填写课程名称。")
            if (p.title.length > MAX_COURSE_FIELD || p.room.length > MAX_COURSE_FIELD)
                throw InvalidCommand("课程名称和地点各不能超过 $MAX_COURSE_FIELD 个字符。")
            if (after.weeks.isEmpty() || after.weeks.any { it !in 1..term.weeks } || p.day !in 1..7 ||
                p.firstPeriod !in 1..12 || p.lastPeriod !in p.firstPeriod..12)
                throw InvalidCommand("请选择学期内的上课周、星期和有效的起止节次。")
            if (rows.any { p.matches(it.meeting) })
                throw InvalidCommand("已有相同名称、地点和节次的手动课程。请打开原课程编辑上课周。")
            for (w in after.weeks.sorted()) rows.add(CourseRow(id, MANUAL_SOURCE,
                term.meeting(w, p.day, p.title, p.room, p.firstPeriod, p.lastPeriod)))
        }
        manual.current[id] = if (rows.isEmpty()) null else TermContents(term.row(MANUAL_SOURCE), rows)
    }
    fun intersecting(start: Long, end: Long): List<CourseRow> {
        val stored = dao.intersecting(start, end)
        if (sources.isEmpty()) return stored
        return buildList {
            for (row in stored) if (sources[row.source]?.current?.containsKey(row.termId) != true) add(row)
            for (s in sources.values) for (contents in s.current.values) if (contents != null) for (row in contents.rows) {
                if (row.meeting.startMillis < end && row.meeting.endMillis > start) add(row)
            }
        }.sortedBy { it.meeting.startMillis }
    }
    fun finish(changes: MutableList<PlannerChange>, tasks: (Long, Long) -> List<DayBlock>): List<CourseWrites> {
        if (sources.isEmpty()) return emptyList()
        val writes = ArrayList<CourseWrites>()
        var from = Long.MAX_VALUE; var until = Long.MIN_VALUE
        fun touched(row: CourseRow): Boolean {
            val src = sources[row.source] ?: return false
            return row.termId in src.current && (src.source == HUST_SOURCE || src.original[row.termId]?.rows?.contains(row) != true)
        }
        for (s in sources.values) for (contents in s.current.values) if (contents != null) for (row in contents.rows) {
            if (touched(row)) { from = minOf(from, row.meeting.startMillis); until = maxOf(until, row.meeting.endMillis) }
        }
        if (from < until) {
            fun warn(a: CourseRow, other: String) {
                changes.add(PlannerChange(a.termId, "冲突 · ${a.meeting.title.diagnosticText()}", a.summary(), "与 $other 重叠；保留双方"))
            }
            importConflicts(intersecting(from, until), tasks(from, until), ::touched,
                { a, b -> warn(a, b.summary()) },
                { c, t -> warn(c, "${t.title.diagnosticText()} ${intervalLabel(t.startMillis, t.endMillis, campusZone)}") })
        }
        for (s in sources.values) for ((id, next) in s.current) {
            val old = s.original[id]
            val was = old?.rows.orEmpty(); val now = next?.rows.orEmpty()
            val removed = was.filterNot { it in now }; val added = now.filterNot { it in was }
            if (old?.term != next?.term) {
                val label = if (s.source == HUST_SOURCE) "学期" else "手动课表"
                changes.add(PlannerChange(id, "$label · $id", old?.term?.spec()?.summary(), next?.term?.spec()?.summary()))
            }
            for (row in removed) changes.add(PlannerChange(id, "移除课程 · ${row.meeting.title}", row.summary(), null))
            for (row in added) changes.add(PlannerChange(id, "新增课程 · ${row.meeting.title}", null, row.summary()))
            if (old?.term != next?.term || removed.isNotEmpty() || added.isNotEmpty()) {
                writes.add(CourseWrites(id, s.source, next == null, next?.term?.takeUnless { it == old?.term }, removed, added))
            }
        }
        return writes
    }
}

private fun CourseRow.summary() = "${label()} · 第${meeting.week}周 · 第${meeting.firstPeriod}–${meeting.lastPeriod}节 · " +
    intervalLabel(meeting.startMillis, meeting.endMillis, campusZone)
private fun TermSpec.summary() = "$firstMonday 起，$weeks 周，${if (initial == BellSchedule.SUMMER) "夏令" else "冬令"}作息" +
    (switchDate?.let { "，$it 起切换" } ?: "")
