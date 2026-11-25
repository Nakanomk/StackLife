package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.archive.BackupPart
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.graphs.GoalDag
import io.github.nakanomk.stacklife.images.PHOTO_EDGE
import io.github.nakanomk.stacklife.planner.*
import java.time.LocalDate
import java.time.ZoneId

private val civil = LocalDate.of(1,1,1).toEpochDay()..LocalDate.of(9999,12,31).toEpochDay()
private fun validId(id:String) = require(id.isNotBlank() && id.length<=512 && id.none(Char::isISOControl)) { "Invalid record identity" }
private val routineIds=RoutineKind.entries.mapTo(hashSetOf()) { it.scheduleId }
private fun dense(values:List<Int>) = require(values.sorted().withIndex().all { it.index==it.value }) { "Invalid or duplicate position" }
private fun validInterval(i:ExactInterval) {
    // Civil extremes depend on the device zone; permit either UTC offset at those endpoints.
    require(i.startMillis >= (civil.first-1)*86_400_000L && i.endMillis < (civil.last+2)*86_400_000L && i.endMillis>=i.startMillis) { "Invalid interval" }
}
private fun validKey(k:OccurrenceKey) { when(k) {
    is OccurrenceKey.Schedule -> { validId(k.scheduleId); k.originalDay?.let { require(it in civil) } }
    is OccurrenceKey.Course -> with(k.occurrence) {
        validId(termId); validId(source); require(week in 1..53 && day in 1..7 && firstPeriod>=1 && lastPeriod>=firstPeriod && courseFieldProblem(title,room)==null)
    }
} }
internal class BackupSchedules(records:List<BackupRecord>) {
    val roots=records.filterIsInstance<BSchedule>().associateBy { it.id }
    val segments=records.filterIsInstance<BSegment>().groupBy({it.scheduleId},{it.value}).mapValues { (_,s) -> SchedulePattern.Daily(s.sortedBy { it.fromDay }) }
    val exceptions=records.filterIsInstance<BException>().associateBy { it.scheduleId to it.originalDay }
    private val zone=ZoneId.systemDefault()
    fun interval(link:GraphLink.Occurrence,owner:Owner?):ExactInterval? {
        val root=roots[link.scheduleId] ?: return null
        if(!root.enabled || root.once!=null || (root.role==null && owner!=Owner.QUEUE && owner!=Owner.FRAME)) return null
        val exception=exceptions[link.scheduleId to link.originalDate]
        return if(exception!=null) exception.moved else segments[link.scheduleId]?.segmentFor(link.originalDate)?.intervalOn(link.originalDate,zone)
    }
    fun live(link:GraphLink,tasks:Map<String,BTask>):Boolean = when(link) {
        is GraphLink.Task -> tasks[link.taskId]?.let { it.owner!=Owner.REMOVED }==true
        is GraphLink.Occurrence -> interval(link,tasks[link.scheduleId]?.owner)!=null
    }
}
internal fun validateBackup(parts:Set<BackupPart>,records:List<BackupRecord>) {
    val ids=HashSet<Pair<Class<out BackupRecord>,Any>>(records.size)
    require(records.all { it.part() in parts && ids.add(it.identity()) }) { "Duplicate record identity or wrong category" }
    val tasks=records.filterIsInstance<BTask>().associateBy { it.id }
    for(t in tasks.values) {
        validId(t.id); require(t.id !in routineIds && t.title.isNotBlank() && t.minutes>=0)
        require((t.owner==Owner.FRAME)==(t.frameId!=null) && (t.owner==Owner.QUEUE)==(t.position!=null)) { "Invalid task ownership" }
        require(t.owner==Owner.DONE || (t.completedFromPosition==null && t.completedFromFrame==null))
        require(t.completedFromPosition==null || t.completedFromPosition>=0 && t.completedFromFrame==null)
        t.completedFromFrame?.let(::validId)
    }
    dense(tasks.values.mapNotNull { it.position })
    val frames=records.filterIsInstance<BFrame>(); frames.forEach { validId(it.id) }; dense(frames.map { it.order })
    val frameIds=frames.mapTo(hashSetOf()) { it.id }; val items=records.filterIsInstance<BItem>().groupBy { it.frameId }
    for((id,group) in items) {
        // Partial pushes retain original queue positions. (frameId, position) is already unique above.
        require(id in frameIds && group.all { it.position >= 0 }); val owned=HashSet<String>()
        for(i in group) {
            validId(i.taskId); require(i.title.isNotBlank() && i.minutes>=0)
            require(if(i.referenceOnly) i.ownedTaskId==null else i.ownedTaskId==i.taskId && owned.add(i.taskId) && i.occurrenceDay==null)
            require(i.referenceOnly || i.taskId !in routineIds)
            i.occurrenceDay?.let { require(it in civil && i.saved?.occurrenceInterval!=null) }
            i.saved?.let { s -> require(s.payloadVersion==1 && s.schedule.id==i.taskId); s.occurrenceInterval?.let(::validInterval)
                when(val p=s.schedule.pattern) { is SchedulePattern.Once -> validInterval(p.interval); is SchedulePattern.Daily -> require(p.segments.isNotEmpty()) }
            }
        }
    }
    val ownership=items.values.flatten().mapNotNullTo(hashSetOf()) { i -> i.ownedTaskId?.let { i.frameId to it } }
    val allOwners=Owner.entries.all { it.authority() in parts }
    if(allOwners) require(ownership.all { (_,id) -> id in tasks }) { "Missing identity referenced by original frame item" }
    require(tasks.values.none { it.owner==Owner.FRAME && (it.frameId to it.id) !in ownership }) { "Live frame member has no owning original item" }
    val schedules=BackupSchedules(records)
    for(s in schedules.roots.values) {
        validId(s.id)
        require(if(s.role==null) s.taskId==s.id && s.sourceOwner!=null && s.sourceOwner!=Owner.REMOVED && s.id !in routineIds
            else s.role.scheduleId==s.id && s.taskId==null && s.sourceOwner==null && s.once==null)
        if(s.sourceOwner!=null && s.sourceOwner.authority() in parts) require(tasks[s.taskId]?.owner==s.sourceOwner) { "Schedule source identity is absent or contradictory" }
        if(s.once==null) require(s.id in schedules.segments) else { require(s.id !in schedules.segments); validInterval(s.once) }
    }
    for((id,p) in schedules.segments) { val root=schedules.roots.getValue(id); require(root.role!=null || p.segments.all { it.zone!=null }) }
    for(e in schedules.exceptions.values) { require(schedules.roots.getValue(e.scheduleId).once==null && e.originalDay in civil); e.moved?.let(::validInterval) }
    val terms=records.filterIsInstance<BTerm>().associateBy { it.id to it.source }
    val specs=mutableMapOf<String,TermSpec>()
    for(t in terms.values) { val spec=t.row().spec(); require(spec.problem()==null && calendarFor(t.source)!=null); require(specs.put(t.id,spec).let { it==null || it==spec }) }
    val classes=records.filterIsInstance<BClass>().associateBy { CourseOccurrenceId.of(it.termId,it.source,it.meeting) }
    for((k,c) in classes) { require((k.termId to k.source) in terms && courseFieldProblem(k.title,k.room)==null)
        require(calendarFor(k.source)?.meetingOrNull(specs.getValue(k.termId),k.week,k.day,k.title,k.room,k.firstPeriod,k.lastPeriod)==c.meeting) { "Class time does not match its term and periods" }
    }
    records.filterIsInstance<BCompletion>().forEach { validKey(it.key) } // Historical marks may outlive their series or course.
    val policies=records.filterIsInstance<BPolicy>().associateBy { it.subject }; val rules=records.filterIsInstance<BRule>().groupBy { it.subject }
    require(rules.keys.all(policies::containsKey)); for((subject,p) in policies) {
        val group=rules[subject].orEmpty(); dense(group.map { it.order }); require(!p.inherit || subject is ReminderSubject.Occurrence && group.isEmpty())
        subject.rootId()?.let { require(it in schedules.roots) }; if(subject is ReminderSubject.Occurrence) {
            validKey(subject.key); if(subject.key is OccurrenceKey.Course) require(subject.key.occurrence in classes)
        }
        require(group.map { it.rule.time }.distinct().size==group.size)
        val start=when(subject) {
            ReminderSubject.CourseDefault -> civil.first*86_400_000L
            is ReminderSubject.Schedule -> schedules.roots[subject.id]?.once?.startMillis ?: schedules.segments[subject.id]?.segments?.first()?.fromDay?.times(86_400_000L)
            is ReminderSubject.Occurrence -> when(val k=subject.key) {
                is OccurrenceKey.Course -> classes[k.occurrence]?.meeting?.startMillis
                is OccurrenceKey.Schedule -> if(k.originalDay==null) schedules.roots[k.scheduleId]?.once?.startMillis
                    else schedules.interval(GraphLink.Occurrence(k.scheduleId,k.originalDay),schedules.roots[k.scheduleId]?.sourceOwner)?.startMillis
            }
        }
        for(r in group) {
            require(r.rule.id.isNotBlank() && r.rule.id.length<=128 && (r.rule.time !is ReminderWhen.AtInstant || subject is ReminderSubject.Occurrence))
            if(start!=null) r.rule.time.trigger(start)
        }
    }
    val graphs=records.filterIsInstance<BGraph>(); dense(graphs.map { it.order }); val graphIds=graphs.mapTo(hashSetOf()) { it.id }
    val nodes=records.filterIsInstance<BNode>().groupBy { it.graphId }; val edges=records.filterIsInstance<BEdge>().groupBy({it.graphId},{it.edge})
    require(nodes.keys.all(graphIds::contains) && edges.keys.all(graphIds::contains)); for(g in graphs) {
        validId(g.id); require(g.title.isNotBlank()); val links=HashSet<GraphLink>()
        val ns=nodes[g.id].orEmpty().map { n -> n.node.also { v -> validId(v.id); require(v.title.isNotBlank() && v.minutes>=0 && v.x.isFinite() && v.y.isFinite() && v.linkState==null && v.creationOrder in 0 until Long.MAX_VALUE)
            val link=v.link; require(link==null || links.add(link)); require(n.linkedTaskId==null || n.sourceOwner!=null); when(link) {
                null -> require(!n.sourceAvailable && n.linkedTaskId==null)
                is GraphLink.Task -> { validId(link.taskId); require(link.taskId !in routineIds && (n.linkedTaskId==null || n.linkedTaskId==link.taskId) && n.sourceAvailable==(n.sourceOwner!=null && n.sourceOwner!=Owner.REMOVED)) }
                is GraphLink.Occurrence -> {
                    validKey(link.key()); require(n.linkedTaskId==null || n.linkedTaskId==link.scheduleId)
                    if(link.scheduleId in routineIds) require(n.linkedTaskId==null && n.sourceOwner==null)
                    else if(n.sourceAvailable) require(n.sourceOwner==Owner.QUEUE || n.sourceOwner==Owner.FRAME)
                }
            }
            val sourceId=when(link) { is GraphLink.Task -> link.taskId; is GraphLink.Occurrence -> link.scheduleId; null -> null }
            if(allOwners || n.sourceOwner?.authority() in parts || sourceId in tasks) require(tasks[sourceId]?.owner==n.sourceOwner)
            if(link!=null && (n.sourceOwner?.authority() in parts || n.sourceOwner==null) && (link is GraphLink.Task || BackupPart.TODAY in parts))
                require(n.sourceAvailable==schedules.live(link,tasks)) { "Contradictory graph dependency" }
        } }
        GoalDag(ns.associateBy { it.id },edges[g.id].orEmpty())
    }
    val countdowns=records.filterIsInstance<BCountdown>().map { it.value }
    for(c in countdowns) { validId(c.id); require(c.problem()==null) }; countdowns.groupBy { it.pinned }.values.forEach { dense(it.map { c -> c.position }) }
    validateHistory(records)
    if(records.any { it.part()==BackupPart.TODAY }) require(RoutineKind.entries.all { schedules.roots[it.scheduleId]?.role==it }) { "Missing system routine" }
}

private fun validateHistory(records:List<BackupRecord>) {
    val threads=records.filterIsInstance<BThread>().associateBy { it.id }; val messages=records.filterIsInstance<BMessage>().associateBy { it.id }
    val last=mutableMapOf<Long,Long>(); val ordinals=mutableMapOf<Long,MutableList<Int>>()
    for(m in messages.values) {
        require(m.id in 1 until Long.MAX_VALUE && m.thread in threads); last[m.thread]=maxOf(last[m.thread] ?: 0L,m.id)
        m.event?.let { require(m.role==HistoryRole.NOTICE && m.text.isEmpty() && it.changes.isNotEmpty()); validateBackupEvent(it) }
    }
    for(t in threads.values) require(t.id in 1 until Long.MAX_VALUE && t.lastMessage==last[t.id]) { "Invalid conversation message order" }
    val fileIds=HashSet<String>()
    fun attachment(id:String,msg:Long,ordinal:Int) { require(privatePhotoId.matches(id) && fileIds.add(id) && messages[msg]?.role==HistoryRole.USER); ordinals.getOrPut(msg) { arrayListOf() }.add(ordinal) }
    for(i in records.filterIsInstance<BImage>()) { attachment(i.id,i.message,i.ordinal); require(i.bytes in 1..MAX_IMAGE_INPUT_BYTES && i.width in 1..PHOTO_EDGE && i.height in 1..PHOTO_EDGE && i.mime in setOf("image/jpeg","image/png","image/webp")) }
    for(d in records.filterIsInstance<BDocument>()) { attachment(d.id,d.message,d.ordinal); require(d.mime in documentMimeTypes(true) && d.bytes in 1..(if(d.mime==PDF_MIME) 4*1024*1024L else 64*1024L)); require(d.name.length in 1..160 && d.name.none { it=='/' || it=='\\' || it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt() }) }
    for(o in ordinals.values) { dense(o); require(o.size<=MAX_CHAT_ATTACHMENTS) }
}
