package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.*
import java.time.Instant
import java.time.ZoneId

/** Sparse read-transaction overlay. Immutable write sets survive until revision-checked commit. */
internal class AgendaDraft(private val dao: ScheduleDao, private val courses: CourseDraft, private val version: Long,
    private val edited: Map<String, TaskRow>, private val task: (String) -> TaskRow) {
    private val reader = ScheduleReader(dao)
    private val zone = ZoneId.systemDefault()
    private val old = mutableMapOf<String, LoadedSchedule?>()
    private val roots = linkedMapOf<String, ScheduleSummary?>()
    private val exceptions = linkedMapOf<OccurrenceKey.Schedule, ExceptionRow?>()
    private val marks = linkedMapOf<OccurrenceKey, Boolean>()
    private val policies = linkedMapOf<ReminderSubject, ReminderPolicy?>()
    private val receiptMoves = mutableListOf<Pair<OccurrenceKey, OccurrenceKey>>()
    private val oldOccurrences = linkedMapOf<OccurrenceKey, AgendaOccurrence?>()
    private fun invalid(en: String, zh: String): Nothing = throw InvalidCommand(uiText(en, zh))
    private fun stored(id: String): LoadedSchedule? {
        if (id !in old) old[id] = reader.load(id)
        return old[id]
    }
    fun preload(ids: Collection<String>) {
        val need = ids.filter { it !in old && it !in roots }
        val found = reader.loadMany(need)
        for (id in need) old[id] = found[id]
    }
    fun current(id: String): ScheduleSummary? = if (id in roots) roots[id] else stored(id)?.summary()
    fun clear(id: String) { if (current(id) != null) set(id, null, false) }
    private fun effectivePolicy(subject: ReminderSubject): ReminderPolicy = if (subject in policies) policies[subject]
        ?: ReminderPolicy(subject is ReminderSubject.Occurrence, emptyList()) else dao.policy(subject)
    private fun rules(subject: ReminderSubject, policy: ReminderPolicy) = if (policy.inherit && subject is ReminderSubject.Occurrence)
        effectivePolicy(subject.key.reminderParent()).rules else policy.rules
    private fun rememberKey(key: OccurrenceKey) { if (key !in oldOccurrences) oldOccurrences[key] = when (key) {
        is OccurrenceKey.Schedule -> reader.resolve(key, zone)
        is OccurrenceKey.Course -> courses.stored(key.occurrence)?.agenda(dao.completed(key.storageKey()))
    } }

    fun set(id: String, pattern: SchedulePattern?, enabled: Boolean) {
        val before = current(id)
        val role = before?.role
        if (role == null) {
            val t = task(id)
            if (t.owner !in setOf(Owner.QUEUE, Owner.FRAME)) invalid("Only active tasks can be scheduled.", "只能安排在队或栈内任务。")
            if (before == null && t.owner != Owner.QUEUE) invalid("Only queued tasks may receive their first schedule.", "只能为在队任务首次安排日程。")
        }
        if (pattern == null && before == null) invalid("There is no schedule to clear.", "没有可取消的排程。")
        if (pattern is SchedulePattern.Daily) {
            if (role == null && pattern.segments.any { it.zone == null }) invalid("Choose a fixed time zone for a daily task.", "请为每日任务选择固定时区。")
        } else if (pattern != null && role != null) invalid("A system routine uses a daily schedule.", "系统作息使用每日系列。")
        if (pattern is SchedulePattern.Once) {
            for (v in listOf(pattern.interval.startMillis, pattern.interval.endMillis)) if (Instant.ofEpochMilli(v).atZone(zone).year !in 1..9999)
                invalid("Choose a time in years 1–9999.", "请选择公元 1–9999 年内的时刻。")
        }
        val next = if (pattern == null) before?.takeIf { role != null }?.copy(enabled = false) else
            ScheduleSummary(id, if (pattern is SchedulePattern.Daily) SchedulePattern.Daily(pattern.segments.toList()) else pattern, enabled, role)
        if (next == before) return
        if (before?.pattern is SchedulePattern.Daily && next?.pattern !is SchedulePattern.Daily) clearExceptions(id) { true }
        roots[id] = next
        if (before == null && next != null) policies.putIfAbsent(ReminderSubject.Schedule(id), initialReminderPolicy)
        if (next == null) policies[ReminderSubject.Schedule(id)] = null
    }
    private fun clearExceptions(id: String, test: (Long) -> Boolean) {
        for (ex in dao.exceptions(id)) if (test(ex.originalDay)) exceptions[OccurrenceKey.Schedule(id, ex.originalDay)] = null
        for (key in exceptions.keys.toList()) if (key.scheduleId == id && test(requireNotNull(key.originalDay))) exceptions[key] = null
    }
    private fun exception(key: OccurrenceKey.Schedule) = if (key in exceptions) exceptions[key] else dao.exception(key.scheduleId, requireNotNull(key.originalDay))
    private fun scheduleRow(s: ScheduleSummary, key: OccurrenceKey.Schedule, interval: ExactInterval, occurrenceVersion: Long): AgendaOccurrence {
        val t = if (s.role == null) edited[s.id] ?: task(s.id) else null
        return AgendaOccurrence(key, s.role?.label() ?: requireNotNull(t).title, t?.notes.orEmpty(), interval.startMillis, interval.endMillis,
            marks[key] ?: (t?.owner == Owner.DONE || dao.completed(key.storageKey())), occurrenceVersion, t?.id, t?.owner, role = s.role)
    }
    fun resolve(key: OccurrenceKey): AgendaOccurrence? = when (key) {
        is OccurrenceKey.Course -> courses.resolve(key.occurrence)?.let { it.agenda(marks[key] ?: dao.completed(key.storageKey())) }
        is OccurrenceKey.Schedule -> current(key.scheduleId)?.let { s ->
            if (!s.enabled || s.role == null && (edited[s.id] ?: task(s.id)).owner.let { it == Owner.REMOVED || it == Owner.DONE && key.originalDay != null }) return@let null
            val v = if (s.id in roots) version else stored(s.id)?.header?.root?.version ?: version
            when (val p = s.pattern) {
                is SchedulePattern.Once -> if (key.originalDay != null) null else scheduleRow(s, key, p.interval, v)
                is SchedulePattern.Daily -> {
                    val day = key.originalDay ?: return@let null
                    val e = exception(key)
                    if (e != null) e.actual()?.let { scheduleRow(s, key, it, maxOf(v, e.version)) }
                    else p.segmentFor(day)?.intervalOn(day, zone)?.let { scheduleRow(s, key, it, v) }
                }
            }
        }
    }
    fun override(cmd: PlannerCommand.SetOccurrenceOverride) {
        val key = cmd.key; val id = key.scheduleId; val day = key.originalDay ?: invalid("Select a dated occurrence.", "请选择具体日期的发生项。")
        val s = current(id) ?: invalid("This series no longer exists.", "该系列已不存在。")
        val p = s.pattern as? SchedulePattern.Daily ?: invalid("This event is not daily.", "该事件不是每日系列。")
        if (resolve(key) == null) invalid("This occurrence no longer exists.", "这次事件已不存在。")
        when (cmd.scope) {
            EditScope.THIS -> {
                if (cmd.segment != null) invalid("A single occurrence needs an exact time, not a recurrence rule.", "单次改时不能使用重复规则。")
                rememberKey(key)
                val i = cmd.interval
                val normal = p.segmentFor(day)?.intervalOn(day, zone)
                exceptions[key] = if (i != null && i == normal) null else ExceptionRow(id, day, i?.startMillis, i?.endMillis, version)
            }
            EditScope.FOLLOWING, EditScope.ALL -> {
                if (cmd.interval != null) invalid("Use a daily rule to change multiple dates.", "多日修改须使用每日规则。")
                val next = cmd.segment
                if (cmd.scope == EditScope.ALL) {
                    if (next == null) set(id, p, false) else set(id, SchedulePattern.Daily(listOf(next)), true)
                } else {
                    if (next != null && next.fromDay != day) invalid("The new segment must start on the chosen original date.", "新规则须从所选原始日期生效。")
                    val past = p.segments.takeWhile { it.fromDay < day }.toMutableList()
                    if (next != null) past.add(next) else {
                        clearExceptions(id) { it >= day }
                        if (past.isNotEmpty()) { val last = past.last(); past[past.lastIndex] = last.copy(endDay = minOf(last.endDay ?: day - 1, day - 1)) }
                    }
                    if (past.isEmpty()) set(id, p, false) else set(id, SchedulePattern.Daily(past), true)
                }
            }
        }
    }
    fun complete(cmd: PlannerCommand.SetOccurrenceCompletion): AgendaOccurrence {
        val key = cmd.key
        val actual = resolve(key) ?: invalid("This occurrence no longer exists.", "这次事件已不存在。")
        if (cmd.expectedVersion != null && actual.version != cmd.expectedVersion) invalid("This notification is out of date.", "该通知对应的事件已改变。")
        rememberKey(key); marks[key] = cmd.completed
        return actual
    }
    fun reminders(subject: ReminderSubject, policy: ReminderPolicy) {
        if (policy.inherit && (subject !is ReminderSubject.Occurrence || policy.rules.isNotEmpty()))
            invalid("Only an occurrence can inherit instead of supplying rules.", "仅具体发生项可继承，继承时不可另列规则。")
        val start = when (subject) {
            ReminderSubject.CourseDefault -> null
            is ReminderSubject.Schedule -> {
                val s = current(subject.id) ?: invalid("Schedule this event before setting reminders.", "请先安排事件再设置提醒。")
                when (val p = s.pattern) { is SchedulePattern.Once -> p.interval.startMillis
                    is SchedulePattern.Daily -> p.segments.minOf { Instant.ofEpochSecond(Math.multiplyExact(it.fromDay, 86400L)).toEpochMilli() } }
            }
            is ReminderSubject.Occurrence -> resolve(subject.key)?.startMillis ?: invalid("Select an existing occurrence.", "请选择尚存在的发生项。")
        }
        val ids = hashSetOf<String>(); val times = hashSetOf<ReminderWhen>()
        for (r in policy.rules) {
            if (r.id.isBlank() || r.id.length > 128 || !ids.add(r.id) || !times.add(r.time)) invalid("Choose distinct reminder rules.", "请勿重复添加同一提醒规则。")
            if (r.time is ReminderWhen.AtInstant && subject !is ReminderSubject.Occurrence) invalid("An absolute reminder needs one dated event.", "指定时刻的提醒必须绑定单次事件。")
            try { r.time.trigger(start ?: Instant.parse("0001-01-01T00:00:00Z").toEpochMilli()) }
            catch (_: ArithmeticException) { invalid("The reminder is outside the supported time range.", "提醒时刻超出可保存范围。") }
        }
        policies[subject] = policy.copy(rules = policy.rules.toList())
    }

    fun storedBetween(from: Long, until: Long) = reader.between(from, until, zone)
    fun between(from: Long, until: Long): List<AgendaOccurrence> {
        val base = storedBetween(from, until)
        if (roots.isEmpty() && exceptions.isEmpty() && edited.isEmpty()) return base
        val out = ArrayList<AgendaOccurrence>(base.size)
        for (row in base) {
            val key = row.key as OccurrenceKey.Schedule
            if (key.scheduleId in roots || key in exceptions) continue
            val t = edited[key.scheduleId]
            if (t?.owner == Owner.REMOVED || t?.owner == Owner.DONE && key.originalDay != null) continue
            out.add(if (t == null) row else row.copy(title = t.title, notes = t.notes, owner = t.owner))
        }
        for ((id, s) in roots) {
            if (s == null || !s.enabled) continue
            when (val p = s.pattern) {
                is SchedulePattern.Once -> if (p.interval.intersects(from, until)) out.add(scheduleRow(s, OccurrenceKey.Schedule(id), p.interval, version))
                is SchedulePattern.Daily -> {
                    val storedExceptions = dao.exceptions(id).associateBy { it.originalDay }
                    for (i in p.segments.indices) p.segments[i].between(from, until, zone, p.segments.getOrNull(i+1)?.fromDay) { day, actual ->
                        val k = OccurrenceKey.Schedule(id, day)
                        if (k !in exceptions && day !in storedExceptions || k in exceptions && exceptions[k] == null) out.add(scheduleRow(s, k, actual, version))
                    }
                    for ((day, ex) in storedExceptions) {
                        val k = OccurrenceKey.Schedule(id, day)
                        if (k !in exceptions) ex.actual()?.takeIf { it.intersects(from, until) }?.let { out.add(scheduleRow(s,k,it,version)) }
                    }
                }
            }
        }
        for (k in exceptions.keys) if (k.scheduleId !in roots || exceptions[k] != null) resolve(k)?.takeIf { intersectsWindow(it.startMillis,it.endMillis,from,until) }?.let(out::add)
        out.sortWith(agendaOrder); return out
    }

    /** Called once, after manual correspondences have been reduced to the final transaction delta. */
    fun migrateCourses(writes: List<CourseWrites>) {
        for (w in writes) {
            val finalKeys = w.added.mapTo(hashSetOf()) { it.key() }
            val transfers = w.links.associate { it.old to it.new }
            val saved = w.removed.associate { row -> val k = OccurrenceKey.Course(row.key()); k to ((marks[k] ?: dao.completed(k.storageKey())) to effectivePolicy(ReminderSubject.Occurrence(k))) }
            for ((k, _) in saved) if (k.occurrence !in finalKeys) {
                marks[k] = false; policies[ReminderSubject.Occurrence(k)] = null
            }
            for ((k, values) in saved) transfers[k.occurrence]?.takeIf { it != k.occurrence }?.let {
                val dest = OccurrenceKey.Course(it)
                if (values.first) marks[dest] = true
                if (!values.second.inherit) policies[ReminderSubject.Occurrence(dest)] = values.second
                receiptMoves.add(k to dest)
            }
        }
    }
    fun finish(changes: MutableList<PlannerChange>): AgendaWrites {
        val now = System.currentTimeMillis()
        val writes = linkedMapOf<String, ScheduleSummary?>()
        for ((id, next) in roots) {
            val before = stored(id)?.summary()
            if (before == next) continue
            writes[id] = next
            fun snap(s: ScheduleSummary, initial: Boolean): ChangeSnapshot {
                val title = if (initial) stored(id)?.header?.let { it.root.role?.label() ?: it.title } else s.role?.label() ?: task(id).title
                val name = requireNotNull(title)
                val once = s.pattern as? SchedulePattern.Once
                if (once == null || !s.enabled) {
                    val existing = dao.exceptions(id).mapTo(hashSetOf()) { it.originalDay }
                    if (!initial) for ((key, ex) in exceptions) if (key.scheduleId == id) { if (ex == null) existing.remove(key.originalDay) else existing.add(ex.originalDay) }
                    return SeriesSnapshot(name,s,existing.size)
                }
                val interval = once.interval; val end = if (interval.point) Math.addExact(interval.startMillis,1) else interval.endMillis
                val neighbours = if (initial) storedBetween(interval.startMillis, end) else between(interval.startMillis,end)
                val fixed = if (initial) courses.storedIntersecting(interval.startMillis,end) else courses.intersecting(interval.startMillis,end)
                val overlaps = buildList { for (o in neighbours) if (o.key != OccurrenceKey.Schedule(id)) add(Overlap(o.stableKey,o.title,o.startMillis,o.endMillis)); fixed.forEach { add(it.overlap()) } }
                return ScheduleSnapshot(id,name,interval.startMillis,interval.endMillis,zone.id,overlaps)
            }
            changes.add(PlannerChange("schedule:$id",before?.let { snap(it,true) },next?.let { snap(it,false) }))
        }
        for ((key, before) in oldOccurrences) {
            val after = resolve(key)
            fun AgendaOccurrence.snap() = OccurrenceSnapshot(key,title,ExactInterval(startMillis,endMillis),completed,zone.id)
            val a = before?.snap(); val b = after?.snap()
            if (a != b) changes.add(PlannerChange(key.storageKey(),a,b))
        }
        val ruleWrites = linkedMapOf<ReminderSubject, ReminderPolicy?>()
        for ((subject, policy) in policies) {
            val root = subject.rootId()
            if (policy != null && root in roots && roots[root] == null) continue
            if (policy == null && dao.setting(subject.storageKey()) == null) continue
            val original = dao.policy(subject)
            val fresh = subject is ReminderSubject.Schedule && stored(subject.id) == null
            if (policy == original && !fresh) continue
            ruleWrites[subject] = policy
            val actual = (subject as? ReminderSubject.Occurrence)?.let { resolve(it.key) }
            val s = (subject as? ReminderSubject.Schedule)?.let { current(it.id) ?: stored(it.id)?.summary() }
            val header = actual?.title ?: s?.let { it.role?.label() ?: task(it.id).title } ?: uiText("Class reminders", "课程提醒")
            fun snap(p: ReminderPolicy, initial: Boolean): ReminderSnapshot {
                val effective = if (initial) dao.effectiveRules(subject,p) else rules(subject,p)
                val at = actual?.startMillis ?: (s?.pattern as? SchedulePattern.Once)?.interval?.startMillis
                val expired = effective.filterTo(ArrayList()) { r -> when (val time = r.time) {
                    is ReminderWhen.AtInstant -> time.instantMillis < now
                    is ReminderWhen.BeforeStart -> at != null && time.trigger(at) < now
                } }.mapTo(linkedSetOf()) { it.id }
                return ReminderSnapshot(header,subject,p,effective,expired)
            }
            val after = policy?.let { snap(it, false) }
            if (!fresh || policy != initialReminderPolicy || !after?.expiredRuleIds.isNullOrEmpty())
                changes.add(PlannerChange("reminder:" + subject.storageKey(), if (fresh) null else snap(original, true), after))
        }
        // Title changes invalidate notification actions even though their subject/trigger stay the same.
        for ((id,t) in edited) if (id !in writes) stored(id)?.let { old -> if (old.header.title != t.title || old.header.notes != t.notes) writes[id]=old.summary() }
        val exWrites = exceptions.filter { (k,e) -> val prior = dao.exception(k.scheduleId, requireNotNull(k.originalDay)); prior?.copy(version = e?.version ?: 0) != e }
        return AgendaWrites(version,writes,exWrites,marks.filter { (k,done) -> dao.completed(k.storageKey()) != done },ruleWrites,receiptMoves.toList())
    }
}

internal class AgendaWrites(val version: Long, private val roots: Map<String,ScheduleSummary?>,
    private val exceptions: Map<OccurrenceKey.Schedule,ExceptionRow?>, private val marks: Map<OccurrenceKey,Boolean>,
    private val policies: Map<ReminderSubject,ReminderPolicy?>, private val moves: List<Pair<OccurrenceKey,OccurrenceKey>>) {
    val changed get() = roots.isNotEmpty() || exceptions.isNotEmpty() || marks.isNotEmpty() || policies.isNotEmpty() || moves.isNotEmpty()
    fun apply(dao: ScheduleDao) {
        for ((id,s) in roots) {
            if (s == null) { dao.deleteRoot(id); continue }
            val once = s.pattern as? SchedulePattern.Once
            dao.write(ScheduleRow(id,id.takeIf { s.role == null },once?.interval?.startMillis,once?.interval?.endMillis,s.enabled,s.role,version))
            dao.clearSegments(id)
            if (s.pattern is SchedulePattern.Daily) dao.writeSegments(s.pattern.segments.map { SegmentRow(id,it) })
        }
        for ((k,e) in exceptions) if (e == null) dao.deleteException(k.scheduleId,requireNotNull(k.originalDay)) else if (k.scheduleId !in roots || roots[k.scheduleId] != null) dao.write(e)
        for ((k,done) in marks) if (done) dao.complete(CompletionRow(k.storageKey())) else dao.reopen(k.storageKey())
        for ((s,p) in policies) dao.putPolicy(s,p)
        for ((old,new) in moves) dao.copyReceipts(old.storageKey(),new.storageKey())
    }
}
