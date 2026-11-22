package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import java.time.ZoneId

internal data class ReviewedCourses(val term: TermSpec, val meetings: List<CourseMeeting>, val source: String, val calendar: CourseCalendar?)
private data class TermContents(val term: TermRow, val rows: Set<CourseRow>)
internal class CourseWrites(val termId: String, val source: String, val removeTerm: Boolean, val term: TermRow?,
    val removed: List<CourseRow>, val added: List<CourseRow>, val links: List<CourseReplacement>) {
    fun applyTo(dao: CourseDao) {
        if (removeTerm) { dao.remove(termId, source); return }
        term?.let(dao::put)
        if (removed.isNotEmpty()) dao.delete(removed)
        if (added.isNotEmpty()) dao.insert(added)
    }
}

private class SourceDraft(val source: String, private val dao: CourseDao) {
    var calendar: CourseCalendar? = calendarFor(source)
    val original = mutableMapOf<String, TermContents?>()
    val current = linkedMapOf<String, TermContents?>()
    fun load(id: String): TermContents? {
        if (id in current) return current[id]
        if (id !in original) original[id] = dao.term(id, source)?.let { TermContents(it, dao.meetings(id, source).toSet()) }
        return original[id]
    }
}

private fun TermSpec.row(source: String) = TermRow(id, source, firstMonday.toEpochDay(), weeks, initial, switchDate?.toEpochDay())

internal fun CourseRow.key() = CourseOccurrenceId.of(termId, source, meeting)
internal fun CourseRow.overlap(): Overlap {
    val key = key()
    return Overlap(key.stableKey(), meeting.title, meeting.startMillis, meeting.endMillis, key)
}

/** Each import replaces exactly one (source, term); manual edits replace only one pattern. */
internal class CourseDraft(private val dao: CourseDao) {
    private val sources = mutableMapOf<String, SourceDraft>()
    private val manualOrigins = mutableMapOf<CourseRow, CourseRow>()
    private fun source(name: String) = sources.getOrPut(name) { SourceDraft(name, dao) }
    private fun calendar(name: String) = sources[name]?.calendar ?: calendarFor(name)
    private fun zone(name: String) = calendar(name)?.zone ?: ZoneId.systemDefault()
    private fun otherTerm(id: String, except: String): TermSpec? {
        for ((name, overlay) in sources) if (name != except) overlay.current[id]?.let { return it.term.spec() }
        return dao.terms(id).firstOrNull { it.source != except && sources[it.source]?.current?.containsKey(id) != true }?.spec()
    }
    fun stored(key: CourseOccurrenceId) = with(key) { dao.occurrence(termId, source, week, day, firstPeriod, lastPeriod, title, room) }
    fun resolve(key: CourseOccurrenceId): CourseRow? {
        val overlay = sources[key.source]?.current
        if (overlay == null || key.termId !in overlay) return stored(key)
        return overlay[key.termId]?.rows?.find { it.key() == key }
    }
    fun replace(draft: ReviewedCourses) {
        val spec = draft.term
        val calendar = draft.calendar
        if (draft.source == MANUAL_SOURCE || calendar == null)
            throw InvalidCommand(uiText("The import source is not registered.", "未登记此课表导入来源。"))
        spec.problem()?.let { throw InvalidCommand(it) }
        if (draft.meetings.isEmpty()) throw InvalidCommand(uiText("No valid classes. Your existing timetable was not replaced.", "没有有效课程。现有课表未被替换。"))
        val other = otherTerm(spec.id, draft.source)
        if (other != null && other != spec)
            throw InvalidCommand(uiText("Another timetable already uses this term with different calendar settings. Use its settings or a different term name.", "同名学期已有其他课表使用不同校历。请沿用原学期设置，或使用另一个学期名称。"))
        val meetings = LinkedHashSet<CourseRow>(draft.meetings.size)
        for (c in draft.meetings) {
            courseFieldProblem(c.title, c.room)?.let { throw InvalidCommand(uiText({ "Week ${c.week}, day ${c.day}, period ${c.firstPeriod}: $it" }, { "第 ${c.week} 周、星期 ${c.day} 第 ${c.firstPeriod} 节：$it" })) }
            if (c != calendar.meetingOrNull(spec, c.week, c.day, c.title, c.room, c.firstPeriod, c.lastPeriod) || c.startMillis >= c.endMillis)
                throw InvalidCommand(uiText("Class times do not match the confirmed bell schedule.", "课时与已确认的学期作息不一致。"))
            meetings.add(CourseRow(spec.id, draft.source, c))
        }
        val imported = source(draft.source)
        imported.calendar = calendar
        imported.load(spec.id)
        imported.current[spec.id] = TermContents(spec.row(draft.source), meetings)
    }
    fun remove(id: String, requested: String?) {
        val name = requested ?: run {
            val imported = mutableSetOf<String>()
            for (term in dao.terms(id)) if (term.source != MANUAL_SOURCE) imported.add(term.source)
            for ((name, overlay) in sources) if (name != MANUAL_SOURCE && id in overlay.current) {
                if (overlay.current[id] == null) imported.remove(name) else imported.add(name)
            }
            imported.singleOrNull() ?: throw InvalidCommand(if (imported.isEmpty()) uiText("This term has no imported timetable.", "此学期没有已导入的课表。")
                else uiText("Select which school timetable to remove. No classes were removed.", "此学期有多个来源，请选择要移除的学校课表。未删除课程。"))
        }
        if (name == MANUAL_SOURCE) throw InvalidCommand(uiText("Remove manual classes individually.", "请逐门移除手动课程。"))
        val imported = source(name)
        if (imported.load(id) == null) throw InvalidCommand(uiText("This term no longer contains the selected timetable.", "所选学期已没有此来源的课表。"))
        imported.current[id] = null
    }
    fun manual(cmd: PlannerCommand.ManualCourse) {
        val id = cmd.termId
        val manual = source(MANUAL_SOURCE)
        val previous = manual.load(id)
        val existing = previous?.term?.spec() ?: otherTerm(id, MANUAL_SOURCE)
        val term = cmd.createTerm?.also {
            if (existing != null || cmd.before != null || id != it.id)
                throw InvalidCommand(uiText("A new term cannot overwrite an existing timetable. Select the existing term or use another name.", "不能用新学期覆盖已有课表，请选择已有学期或使用其他名称。"))
            it.problem()?.let { error -> throw InvalidCommand(error) }
        } ?: existing ?: throw InvalidCommand(uiText("This term no longer exists. Select another term.", "所选学期已不存在，请重新选择。"))
        if (cmd.before == null && cmd.after == null) throw InvalidCommand(uiText("Select a manual class.", "请选择一门手动课程。"))
        val rows = previous?.rows.orEmpty().toMutableSet()
        val originalWeeks = mutableMapOf<Int, CourseRow>()
        cmd.before?.let { before ->
            val found = mutableSetOf<Int>()
            rows.removeAll { row ->
                if (!before.pattern.matches(row.meeting)) false else {
                    found.add(row.meeting.week)
                    val origin = manualOrigins.remove(row) ?: row.takeIf { it in manual.original[id]?.rows.orEmpty() }
                    if (origin != null) originalWeeks[row.meeting.week] = origin
                    true
                }
            }
            if (found.isEmpty() || found != before.weeks) throw InvalidCommand(uiText("The weeks for this class have changed. Close and reopen the editor.", "这门手动课程的周次已改变，请关闭编辑器并重新打开。"))
        }
        cmd.after?.let { after ->
            val p = after.pattern
            if (p.title.isBlank()) throw InvalidCommand(uiText("Enter a class name.", "请填写课程名称。"))
            if (p.title.length > MAX_COURSE_FIELD || p.room.length > MAX_COURSE_FIELD)
                throw InvalidCommand(uiText({ "Class names and locations must each be at most $MAX_COURSE_FIELD characters." }, { "课程名称和地点各不能超过 $MAX_COURSE_FIELD 个字符。" }))
            if (after.weeks.isEmpty()) throw InvalidCommand(uiText("Select at least one week.", "请至少选择一个上课周。"))
            if (rows.any { p.matches(it.meeting) })
                throw InvalidCommand(uiText("A manual class with this name, location, and periods already exists. Edit its weeks instead.", "已有相同名称、地点和节次的手动课程。请打开原课程编辑上课周。"))
            for (w in after.weeks.sorted()) {
                val meeting = SchoolImports.default.calendar.meetingOrNull(term, w, p.day, p.title, p.room, p.firstPeriod, p.lastPeriod)
                    ?: throw InvalidCommand(uiText("Select weeks within the term, a weekday, and valid start and end periods.", "请选择学期内的上课周、星期和有效的起止节次。"))
                val row = CourseRow(id, MANUAL_SOURCE, meeting)
                rows.add(row)
                originalWeeks[w]?.let { if (row != it) manualOrigins[row] = it }
            }
        }
        manual.current[id] = if (rows.isEmpty()) null else TermContents(term.row(MANUAL_SOURCE), rows)
    }
    fun storedIntersecting(start: Long, end: Long) = dao.intersecting(start, end)

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
    fun finish(changes: MutableList<PlannerChange>, version: Long, previousTasks: (Long, Long) -> List<AgendaOccurrence>,
        tasks: (Long, Long) -> List<AgendaOccurrence>): List<CourseWrites> {
        if (sources.isEmpty()) return emptyList()
        val writes = ArrayList<CourseWrites>()
        fun conflicts(stored: Boolean): Map<Pair<String, String>, CourseOverlapSummary> {
            var from = Long.MAX_VALUE; var until = Long.MIN_VALUE
            fun touched(row: CourseRow): Boolean {
                val src = sources[row.source] ?: return false
                if (row.termId !in src.current) return false
                val opposite = if (stored) src.current else src.original
                return src.source != MANUAL_SOURCE || row !in opposite[row.termId]?.rows.orEmpty()
            }
            for (s in sources.values) for (id in s.current.keys) {
                val contents = (if (stored) s.original[id] else s.current[id]) ?: continue
                for (row in contents.rows) if (touched(row)) {
                    from = minOf(from, row.meeting.startMillis); until = maxOf(until, row.meeting.endMillis)
                }
            }
            if (from >= until) return emptyMap()
            return importConflicts(if (stored) storedIntersecting(from, until) else intersecting(from, until),
                if (stored) previousTasks(from, until) else tasks(from, until), ::touched)
        }
        val nextConflicts = conflicts(false)
        val oldConflicts = conflicts(true)
        for (s in sources.values) for ((id, next) in s.current) {
            val old = s.original[id]
            val was = old?.rows.orEmpty(); val now = next?.rows.orEmpty()
            val removed = was.filterNot { it in now }; val added = now.filterNot { it in was }
            val changed = old?.term != next?.term || removed.isNotEmpty() || added.isNotEmpty()
            val key = s.source to id
            val overlaps = nextConflicts[key]
            val links = if (s.source != MANUAL_SOURCE) emptyList() else added.mapNotNull { row ->
                manualOrigins[row]?.let { CourseReplacement(it.key(), row.key()) }
            }
            if (changed || (overlaps?.count ?: 0L) != 0L) {
                val retained = added.mapTo(hashSetOf()) { it.key() }.also { keys -> links.forEach { keys.add(it.old) } }
                val cancelled = removed.count { it.key() !in retained }
                fun TermContents.snapshot(rows: List<CourseRow>, summary: CourseOverlapSummary?, links: List<CourseReplacement>, cancelled: Int) =
                    CourseSetSnapshot(id, s.source, term.monday, term.weeks, term.initial, term.switchDay,
                        zone(s.source).id, rows.map { it.meeting }, summary?.examples.orEmpty(), links, summary?.count ?: 0, cancelled)
                changes.add(PlannerChange("courses:${s.source.length}:${s.source}:$id", old?.snapshot(removed, oldConflicts[key], emptyList(), cancelled),
                    next?.snapshot(added, overlaps, links, 0)))
            }
            if (changed) {
                for (row in added) row.occurrenceVersion = version
                writes.add(CourseWrites(id, s.source, next == null, next?.term?.takeUnless { it == old?.term }, removed, added, links))
            }
        }
        return writes
    }
}
