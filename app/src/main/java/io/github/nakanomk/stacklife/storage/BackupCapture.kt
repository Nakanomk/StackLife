package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.archive.BackupPart
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.planner.*
import kotlinx.serialization.json.Json

private inline fun <T> historyPages(page:(Int,Int)->List<T>, visit:(T)->Unit) {
    var offset=0
    do {
        val rows=page(128,offset)
        for(row in rows) visit(row)
        offset+=rows.size
    } while(rows.size==128)
}

internal fun PlannerDatabase.capture(parts:Set<BackupPart>, append:(BackupRecord)->Unit) {
    val dao=backupTablesDao()
    val workspaceOwners=Owner.entries.filter { it.authority() in parts }
    if(workspaceOwners.isNotEmpty()) for(t in dao.tasks(workspaceOwners))
        append(BTask(t.id,t.title,t.notes,t.minutes,t.owner,t.frameId,t.queuePosition,t.completedFromFrame,t.completedFromPosition,t.icon))
    if(BackupPart.STACK in parts) {
        dao.frames().forEachIndexed { order,f -> append(BFrame(f.id,f.reason,f.savedAtMillis,f.source,order)) }
        for(i in dao.items()) append(BItem(i.frameId,i.originalPosition,i.taskId,i.title,i.notes,i.minutes,i.savedSchedule(),i.referenceOnly,i.occurrenceDay,i.ownedTaskId,i.icon))
    }
    val owners by lazy(LazyThreadSafetyMode.NONE) { dao.owners().associate { it.id to it.owner } }
    val agenda by lazy(LazyThreadSafetyMode.NONE) { buildList<BackupRecord> {
        for(s in dao.schedules()) add(BSchedule(s.id,s.taskId,s.startMillis?.let { ExactInterval(it,requireNotNull(s.endMillis)) },s.enabled,s.role,s.taskId?.let(owners::getValue)))
        for(s in dao.segments()) add(BSegment(s.scheduleId,s.segment))
        for(e in dao.exceptions()) add(BException(e.scheduleId,e.originalDay,e.actual()))
    } }
    if(BackupPart.TODAY in parts) {
        agenda.forEach(append)
    }
    if(BackupPart.TODAY in parts || BackupPart.COURSES in parts) {
        for(k in dao.completions()) BCompletion(Json.decodeFromString(k)).let { if(it.part() in parts) append(it) }
        val subjectByKey=HashMap<String,ReminderSubject>()
        for(p in dao.policies()) {
            val subject=Json.decodeFromString<ReminderSubject>(p.subject); check(p.rootId==subject.rootId())
            val policy=BPolicy(subject,p.inherit)
            if(policy.part() in parts) { subjectByKey[p.subject]=subject; append(policy) }
        }
        var previous:String?=null; var order=0
        for(r in dao.rules()) {
            val subject=subjectByKey[r.subject] ?: continue
            if(previous!=r.subject) { previous=r.subject; order=0 }
            append(BRule(subject,r.rule(),order++))
        }
    }
    if(BackupPart.GRAPHS in parts) {
        dao.graphs().forEachIndexed { order,g -> append(BGraph(g.id,g.title,order)) }
        val dates by lazy(LazyThreadSafetyMode.NONE) { BackupSchedules(agenda) }
        for(n in dao.nodes()) {
            val node=n.node(); val link=node.link
            val sourceId=when(link) { is GraphLink.Task -> link.taskId; is GraphLink.Occurrence -> link.scheduleId; null -> null }
            val available=when(link) {
                is GraphLink.Task -> owners[link.taskId]?.let { it!=Owner.REMOVED }==true
                is GraphLink.Occurrence -> dates.interval(link,owners[sourceId])!=null
                null -> false
            }
            append(BNode(n.graphId,node,n.linkedTaskId,available,owners[sourceId]))
        }
        for(e in dao.edges()) append(BEdge(e.graphId,GraphEdge(e.fromId,e.toId)))
    }
    if(BackupPart.COURSES in parts) {
        for(t in courseDao().terms()) append(BTerm(t.termId,t.source,t.monday,t.weeks,t.initial,t.switchDay))
        for(c in dao.meetings()) append(BClass(c.termId,c.source,c.meeting))
    }
    if(BackupPart.COUNTDOWNS in parts) for(c in countdownDao().list()) append(BCountdown(c))
    if(BackupPart.HISTORY in parts) {
        historyPages(dao::threads) { c -> append(BThread(c.id,c.scope,c.title,c.updatedAtMillis,c.lastMessageId,c.scopeKey)) }
        historyPages(dao::messages) { m ->
            require((m.payloadVersion==0 && m.eventJson==null) || (m.payloadVersion==1 && m.eventJson!=null)) { "Unsupported chat event version" }
            append(BMessage(m.id,m.conversationId,m.role,m.text,m.eventJson?.let { Json.decodeFromString<ChatEvent>(it) }))
        }
        historyPages(dao::images) { i -> append(BImage(i.id,i.messageId,i.ordinal,i.mime,i.width,i.height,i.bytes)) }
        historyPages(dao::documents) { d -> append(BDocument(d.id,d.messageId,d.ordinal,d.mime,d.displayName,d.bytes)) }
    }
    if(BackupPart.APPEARANCE in parts || BackupPart.PREFERENCES in parts) {
        val settings=plannerDao().settings() ?: SettingsRow()
        if(BackupPart.APPEARANCE in parts) append(BAppearance(decodeAppearance(settings.appearance)))
        if(BackupPart.PREFERENCES in parts) append(BPreferences(settings.restorePolicy,settings.keepRestoredFrames))
    }
}
