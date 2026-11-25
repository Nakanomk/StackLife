package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.archive.BackupPart
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.planner.*
import kotlinx.serialization.json.Json

/** Called only inside the same Room transaction as approval/stamp check and active file generation.
 * Clear selected children before releasing parent identities; no REPLACE on a shared parent. */
internal fun PlannerDatabase.writeReplacement(selected:Set<BackupPart>,outgoing:List<BackupRecord>,incoming:List<BackupRecord>,
    taskWrites:List<BTask>,taskDeletes:Set<String>,revision:Long) {
    val backup=backupTablesDao(); val planner=plannerDao(); val schedules=scheduleDao()
    for(r in outgoing) when(r) {
        is BCompletion -> schedules.reopen(r.key.storageKey())
        is BPolicy -> schedules.deletePolicy(r.subject.storageKey())
        else -> Unit
    }
    if(BackupPart.TODAY in selected) backup.clearSchedules()
    if(BackupPart.GRAPHS in selected) backup.clearGraphs()
    if(BackupPart.STACK in selected) {
        backup.clearItems(); backup.detachFrameTasks(); backup.clearFrames()
        planner.insertFrames(incoming.filterIsInstance<BFrame>().sortedBy { it.order }.map { FrameRow(it.id,it.reason,it.savedAt,it.source) })
    }
    if(taskWrites.isNotEmpty()) backup.putTasks(taskWrites.map { it.row() })
    for(id in taskDeletes) backup.deleteTask(id)
    if(BackupPart.STACK in selected) planner.insertItems(incoming.filterIsInstance<BItem>().map { i ->
        FrameItemRow(i.frameId,i.position,i.taskId,i.title,i.notes,i.minutes,i.saved?.let { snapshotJson.encodeToString(it) },i.referenceOnly,i.occurrenceDay,i.ownedTaskId,i.icon)
    })
    if(BackupPart.TODAY in selected) {
        for(s in incoming.filterIsInstance<BSchedule>()) schedules.write(ScheduleRow(s.id,s.taskId,s.once?.startMillis,s.once?.endMillis,s.enabled,s.role,revision))
        schedules.writeSegments(incoming.filterIsInstance<BSegment>().map { SegmentRow(it.scheduleId,it.value) })
        for(e in incoming.filterIsInstance<BException>()) schedules.write(ExceptionRow(e.scheduleId,e.originalDay,e.moved?.startMillis,e.moved?.endMillis,revision))
    }
    if(BackupPart.COURSES in selected) {
        backup.clearTerms(); val courses=courseDao()
        for(t in incoming.filterIsInstance<BTerm>()) courses.put(t.row())
        courses.insert(incoming.filterIsInstance<BClass>().map { CourseRow(it.termId,it.source,it.meeting).apply { occurrenceVersion=revision } })
    }
    for(r in incoming.filterIsInstance<BCompletion>()) schedules.complete(CompletionRow(r.key.storageKey()))
    for(p in incoming.filterIsInstance<BPolicy>()) schedules.write(ReminderSettingRow(p.subject.storageKey(),p.inherit,p.subject.rootId()))
    val ruleRows=incoming.filterIsInstance<BRule>().sortedBy { it.order }.map { r ->
        val time=r.rule.time; ReminderRuleRow(r.subject.storageKey(),r.rule.id,(time as? ReminderWhen.BeforeStart)?.leadMillis,(time as? ReminderWhen.AtInstant)?.instantMillis)
    }
    if(ruleRows.isNotEmpty()) schedules.insertRules(ruleRows)
    if(BackupPart.GRAPHS in selected) {
        val graphs=graphDao(); for(g in incoming.filterIsInstance<BGraph>().sortedBy { it.order }) graphs.putGraph(GraphRow(g.id,g.title))
        graphs.putNodes(incoming.filterIsInstance<BNode>().map { n -> with(n.node) { GraphNodeRow(n.graphId,id,title,notes,minutes,creationOrder,x,y,link?.storageKey(),n.linkedTaskId,icon) } })
        graphs.putEdges(incoming.filterIsInstance<BEdge>().map { GraphEdgeRow(it.graphId,it.edge.from,it.edge.to) })
    }
    if(BackupPart.COUNTDOWNS in selected) {
        backup.clearCountdowns(); countdownDao().put(incoming.filterIsInstance<BCountdown>().map { with(it.value) { CountdownRow(id,title,targetDay,mode,pinned,position,backgroundId,maskOpacity) } })
    }
    if(BackupPart.HISTORY in selected) {
        backup.clearHistory(); backup.putThreads(incoming.filterIsInstance<BThread>().map { ConversationRow(it.id,it.scope,it.title,it.updatedAt,it.lastMessage,it.scopeKey) })
        backup.putMessages(incoming.filterIsInstance<BMessage>().map { m ->
            // ChatEvent is an inert rendering snapshot, not a Planner proposal or executable tool request.
            ConversationMessageRow(m.id,m.thread,m.role,m.text,if(m.event==null) 0 else 1,m.event?.let { Json.encodeToString(it) })
        })
        backup.putImages(incoming.filterIsInstance<BImage>().map { ConversationImageRow(it.id,it.message,it.ordinal,it.mime,it.width,it.height,it.bytes) })
        backup.putDocuments(incoming.filterIsInstance<BDocument>().map { ConversationDocumentRow(it.id,it.message,it.ordinal,it.mime,it.name,it.bytes) })
    }
    val appearance=incoming.filterIsInstance<BAppearance>().singleOrNull(); val prefs=incoming.filterIsInstance<BPreferences>().singleOrNull()
    val restoredPlan=selected.any { it==BackupPart.TODAY || it==BackupPart.QUEUE || it==BackupPart.STACK || it==BackupPart.COURSES || it==BackupPart.GRAPHS || it==BackupPart.COUNTDOWNS }
    if(appearance!=null || prefs!=null || restoredPlan) {
        val old=planner.settings() ?: SettingsRow()
        planner.saveSettings(old.copy(appearance=appearance?.value?.let(::encodeAppearance) ?: old.appearance,
            restorePolicy=prefs?.restore ?: old.restorePolicy,keepRestoredFrames=prefs?.keep ?: old.keepRestoredFrames,
            onboardingVersion=if(restoredPlan) maxOf(old.onboardingVersion,1) else old.onboardingVersion))
    }
}
