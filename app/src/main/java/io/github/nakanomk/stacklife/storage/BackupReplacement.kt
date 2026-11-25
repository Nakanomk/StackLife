package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.archive.*
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.*

private fun BackupPart.label()=when(this) {
    BackupPart.QUEUE->uiText("Queue","队列"); BackupPart.TODAY->uiText("Today","今天"); BackupPart.STACK->uiText("Stack","栈")
    BackupPart.GRAPHS->uiText("Graphs","流程图"); else -> wire
}
internal fun PlannerDatabase.replacement(selected:Set<BackupPart>,available:Set<BackupPart>,old:List<BackupRecord>,incoming:List<BackupRecord>,stamp:String,baseline:Long):TableReplacement {
    val conflicts=linkedSetOf<String>(); val impacts=linkedSetOf<String>()
    val oldTasks=old.filterIsInstance<BTask>().associateBy { it.id }
    val tasks=oldTasks.filterValues { it.owner.authority() !in selected }.toMutableMap()
    val newTasks=incoming.filterIsInstance<BTask>()
    val outgoing=old.filter { it.part() in selected }
    val finalRecords=ArrayList<BackupRecord>(old.size+incoming.size)
    old.filterTo(finalRecords) { it.part() !in selected && it !is BTask }; incoming.filterTo(finalRecords) { it !is BTask }
    fun conflict(id:String,detail:String,need:Set<BackupPart>) {
        val missing=need-selected
        val unavailable=missing-available
        val resolution=when {
            unavailable.isNotEmpty() -> uiText("Required category missing from this backup: ","此备份缺少所需分类：")+unavailable.joinToString { it.label() }
            missing.isEmpty() -> uiText("The selected records disagree.","所选记录互相矛盾。")
            else -> uiText("Also select: ","需补选：")+missing.joinToString { it.label() }
        }
        conflicts.add("$id · $detail · $resolution")
    }
    for(t in newTasks) {
        val before=tasks.put(t.id,t)
        if(before!=null && before.owner!=Owner.REMOVED) conflict(t.id,uiText("Identity belongs to an unselected category","此 ID 属于未选分类"),setOf(before.owner.authority()))
        if(before?.owner==Owner.REMOVED && t.owner!=Owner.REMOVED) impacts.add(uiText({ "${t.id}: reactivates a retired identity." },{ "${t.id}：重新启用已移除的任务身份。" }))
    }
    val schedules=BackupSchedules(finalRecords)
    val previousSchedules by lazy(LazyThreadSafetyMode.NONE) { BackupSchedules(old) }
    for(s in schedules.roots.values) {
        val id=s.taskId ?: continue; val t=tasks[id]
        if(t==null || t.owner==Owner.REMOVED) {
            val need=if(BackupPart.TODAY !in selected) setOf(BackupPart.TODAY) else setOf(requireNotNull(s.sourceOwner).authority())
            conflict(id,uiText("Schedule needs a live task","排程需要同 ID 的活任务"),need)
        } else if(BackupPart.TODAY !in selected && !t.sameProjection(oldTasks[id]))
            impacts.add(uiText({ "$id: existing agenda entries will use the restored task details and ownership." },{ "$id：原有日程将引用恢复后的任务内容和归属。" }))
    }
    // Preserve the identity FK of historical items even when their live task belongs to an absent
    // class. This creates a REMOVED row, never a second live task or a new schedule.
    fun retain(id:String,title:String,notes:String,minutes:Int,icon:TaskIcon) {
        if(id !in tasks) {
            tasks[id]=oldTasks[id]?.removed() ?: BTask(id,title,notes,minutes,Owner.REMOVED,null,null,null,null,icon)
            impacts.add(uiText({ "$id: retain a retired identity for historical references." },{ "$id：保留已移除身份以保护历史引用。" }))
        }
    }
    for(i in finalRecords.filterIsInstance<BItem>()) i.ownedTaskId?.let { retain(it,i.title,i.notes,i.minutes,i.icon) }
    for(n in finalRecords.filterIsInstance<BNode>()) {
        val link=n.node.link ?: continue
        val live=schedules.live(link,tasks)
        if(n.sourceAvailable && !live) {
            val need=linkedSetOf<BackupPart>()
            if(BackupPart.GRAPHS !in selected) need.add(BackupPart.GRAPHS) else {
                when(link) {
                    is GraphLink.Task -> n.sourceOwner?.let { need.add(it.authority()) }
                    is GraphLink.Occurrence -> {
                        val s=schedules.roots[link.scheduleId]; val currentOwner=tasks[link.scheduleId]?.owner
                        if(n.sourceOwner!=null && currentOwner !in setOf(Owner.QUEUE,Owner.FRAME)) need.add(n.sourceOwner.authority())
                        val key=link.scheduleId to link.originalDate
                        if(s==null || !s.enabled || s.once!=null || (if(key in schedules.exceptions) schedules.exceptions.getValue(key).moved==null
                            else schedules.segments[link.scheduleId]?.segmentFor(link.originalDate)?.intervalOn(link.originalDate,java.time.ZoneId.systemDefault())==null)) need.add(BackupPart.TODAY)
                    }
                }
            }
            conflict("${n.graphId}/${n.node.id}",uiText("A working graph link would lose its source","有效图链接将失去来源"),need)
        } else if(!n.sourceAvailable && live) impacts.add(uiText({ "${n.graphId}/${n.node.id}: the saved link can resolve again." },{ "${n.graphId}/${n.node.id}：此历史链接将重新关联来源。" }))
        if(BackupPart.GRAPHS !in selected && n.sourceAvailable && live) {
            val id=when(link) { is GraphLink.Task -> link.taskId; is GraphLink.Occurrence -> link.scheduleId }
            val sourceChanged=tasks[id]?.let { !it.sameProjection(oldTasks[id]) }==true
            if(sourceChanged || (link is GraphLink.Occurrence && schedules.interval(link,tasks[id]?.owner)!=previousSchedules.interval(link,oldTasks[id]?.owner)))
                impacts.add(uiText({ "${n.graphId}/${n.node.id}: the link is retained and will show the restored source." },{ "${n.graphId}/${n.node.id}：链接保留，将显示恢复后的来源内容。" }))
        }
        n.linkedTaskId?.let { retain(it,n.node.title,n.node.notes,n.node.minutes,n.node.icon) }
    }
    finalRecords.addAll(tasks.values)
    val counts=selected.associateWith { part ->
        val prior=outgoing.filter { it.part()==part }.associateBy { it.identity() }
        val after=finalRecords.filter { it.part()==part }.associateBy { it.identity() }
        BackupCounts(added=after.keys.count { it !in prior },removed=prior.keys.count { it !in after },updated=after.count { (k,v) -> prior[k]?.let { !it.sameContent(v) }==true })
    }
    val taskWrites=tasks.values.filter { it!=oldTasks[it.id] || (BackupPart.STACK in selected && it.owner==Owner.FRAME) }
    val taskDeletes=oldTasks.keys-tasks.keys
    return TableReplacement(counts,impacts.toList(),conflicts.toList(),stamp) { revision ->
        check(revision>baseline && (plannerDao().settings()?.revision ?: 0L)==baseline) { "Planner changed after preview" }
        writeReplacement(selected,outgoing,incoming,taskWrites,taskDeletes,revision)
    }
}
// Read-only dependency metadata helps explain a missing category, but never changes a record.
private fun BackupRecord.sameContent(other:BackupRecord):Boolean = when {
    this is BSchedule && other is BSchedule -> copy(sourceOwner=other.sourceOwner)==other
    this is BNode && other is BNode -> copy(sourceAvailable=other.sourceAvailable,sourceOwner=other.sourceOwner)==other
    else -> this==other
}

private fun BTask.sameProjection(other:BTask?)=other!=null && title==other.title && notes==other.notes && minutes==other.minutes && icon==other.icon && owner==other.owner
