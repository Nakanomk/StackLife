package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.archive.*
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** v1 wire types. No table names, SQL, database rowids, local revisions or notification receipts. */
@Serializable internal sealed interface BackupRecord
@Serializable @SerialName("task") internal data class BTask(val id:String, val title:String, val notes:String, val minutes:Int,
    val owner:Owner, val frameId:String?, val position:Int?, val completedFromFrame:String?, val completedFromPosition:Int?, val icon:TaskIcon):BackupRecord {
    fun row() = TaskRow(id,title,notes,minutes,owner,frameId,position,completedFromFrame,completedFromPosition,icon)
    fun removed() = copy(owner=Owner.REMOVED,frameId=null,position=null,completedFromFrame=null,completedFromPosition=null)
}
@Serializable @SerialName("frame") internal data class BFrame(val id:String,val reason:String,val savedAt:Long,val source:FrameSource,val order:Int):BackupRecord
@Serializable @SerialName("frameItem") internal data class BItem(val frameId:String,val position:Int,val taskId:String,val title:String,val notes:String,
    val minutes:Int,val saved:SavedSchedule?,val referenceOnly:Boolean,val occurrenceDay:Long?,val ownedTaskId:String?,val icon:TaskIcon):BackupRecord
@Serializable @SerialName("schedule") internal data class BSchedule(val id:String,val taskId:String?,val once:ExactInterval?,val enabled:Boolean,
    val role:RoutineKind?,val sourceOwner:Owner?):BackupRecord
@Serializable @SerialName("segment") internal data class BSegment(val scheduleId:String,val value:DailySegment):BackupRecord
@Serializable @SerialName("exception") internal data class BException(val scheduleId:String,val originalDay:Long,val moved:ExactInterval?):BackupRecord
@Serializable @SerialName("completion") internal data class BCompletion(val key:OccurrenceKey):BackupRecord
@Serializable @SerialName("reminders") internal data class BPolicy(val subject:ReminderSubject,val inherit:Boolean):BackupRecord
@Serializable @SerialName("rule") internal data class BRule(val subject:ReminderSubject,val rule:ReminderRule,val order:Int):BackupRecord
@Serializable @SerialName("graph") internal data class BGraph(val id:String,val title:String,val order:Int):BackupRecord
/** linkedTaskId is the actual nullable FK; sourceAvailable distinguishes broken historical links from required dependencies. */
@Serializable @SerialName("node") internal data class BNode(val graphId:String,val node:GraphNode,val linkedTaskId:String?,val sourceAvailable:Boolean,val sourceOwner:Owner?):BackupRecord
@Serializable @SerialName("edge") internal data class BEdge(val graphId:String,val edge:GraphEdge):BackupRecord
@Serializable @SerialName("term") internal data class BTerm(val id:String,val source:String,val monday:Long,val weeks:Int,val initial:BellSchedule,val switchDay:Long?):BackupRecord {
    fun row() = TermRow(id,source,monday,weeks,initial,switchDay)
}
@Serializable @SerialName("class") internal data class BClass(val termId:String,val source:String,val meeting:CourseMeeting):BackupRecord
@Serializable @SerialName("countdown") internal data class BCountdown(val value:Countdown):BackupRecord
@Serializable @SerialName("conversation") internal data class BThread(val id:Long,val scope:String,val title:String,val updatedAt:Long,val lastMessage:Long,val scopeKey:String?):BackupRecord
@Serializable @SerialName("message") internal data class BMessage(val id:Long,val thread:Long,val role:HistoryRole,val text:String,val event:ChatEvent?):BackupRecord
@Serializable @SerialName("image") internal data class BImage(val id:String,val message:Long,val ordinal:Int,val mime:String,val width:Int,val height:Int,val bytes:Long):BackupRecord {
    fun attachment() = ChatImage(id,mime,width,height,bytes)
}
@Serializable @SerialName("document") internal data class BDocument(val id:String,val message:Long,val ordinal:Int,val mime:String,val name:String,val bytes:Long):BackupRecord {
    fun attachment() = ChatDocument(id,mime,name,bytes)
}
@Serializable @SerialName("appearance") internal data class BAppearance(@Serializable(with = PortableAppearance::class) val value:Appearance):BackupRecord
@Serializable @SerialName("preferences") internal data class BPreferences(val restore:RestorePolicy,val keep:Boolean):BackupRecord

internal fun Owner.authority() = when(this) {
    Owner.QUEUE -> BackupPart.QUEUE; Owner.FRAME -> BackupPart.STACK; Owner.DONE, Owner.REMOVED -> BackupPart.TODAY
}
private fun OccurrenceKey.part() = if(this is OccurrenceKey.Course) BackupPart.COURSES else BackupPart.TODAY
private fun ReminderSubject.part() = if(rootId()==null) BackupPart.COURSES else BackupPart.TODAY
internal fun BackupRecord.part():BackupPart = when(this) {
    is BTask -> owner.authority(); is BFrame,is BItem -> BackupPart.STACK
    is BSchedule,is BSegment,is BException -> BackupPart.TODAY; is BCompletion -> key.part(); is BPolicy -> subject.part(); is BRule -> subject.part()
    is BGraph,is BNode,is BEdge -> BackupPart.GRAPHS; is BTerm,is BClass -> BackupPart.COURSES
    is BCountdown -> BackupPart.COUNTDOWNS; is BThread,is BMessage,is BImage,is BDocument -> BackupPart.HISTORY
    is BAppearance -> BackupPart.APPEARANCE; is BPreferences -> BackupPart.PREFERENCES
}
/** Compound keys are tuples, never text joined with an ambiguous delimiter. */
internal fun BackupRecord.identity(): Pair<Class<out BackupRecord>,Any> = javaClass to when(this) {
    is BTask -> id; is BFrame -> id; is BItem -> frameId to position; is BSchedule -> id
    is BSegment -> scheduleId to value.fromDay; is BException -> scheduleId to originalDay; is BCompletion -> key
    is BPolicy -> subject; is BRule -> subject to rule.id; is BGraph -> id; is BNode -> graphId to node.id
    is BEdge -> graphId to edge; is BTerm -> id to source; is BClass -> CourseOccurrenceId.of(termId,source,meeting)
    is BCountdown -> value.id; is BThread -> id; is BMessage -> id; is BImage -> id; is BDocument -> id
    is BAppearance,is BPreferences -> Unit
}

private fun Appearance.mapPhotos(map:(String)->String):Appearance {
    fun spec(w:WallpaperSpec) = w.imageId?.let { w.copy(imageId=map(it)) } ?: w
    return copy(background=spec(background),pageBackgrounds=pageBackgrounds.mapValues { spec(it.value) })
}
private fun ChangeSnapshot?.mapPhotos(map:(String)->String):ChangeSnapshot? = when(this) {
    is AppearanceSnapshot -> copy(value=value.mapPhotos(map))
    is CountdownSnapshot -> copy(value=value.copy(backgroundId=value.backgroundId?.let(map)))
    else -> this
}
internal fun BackupRecord.remapped(photos:Map<String,String>,attachments:Map<String,String>):BackupRecord {
    fun photo(id:String) = photos[id] ?: id
    return when(this) {
        is BAppearance -> copy(value=value.mapPhotos(::photo))
        is BCountdown -> copy(value=value.copy(backgroundId=value.backgroundId?.let(::photo)))
        is BMessage -> if(event==null) this else copy(event=event.copy(changes=event.changes.map { it.copy(before=it.before.mapPhotos(::photo),after=it.after.mapPhotos(::photo)) }))
        is BImage -> copy(id=attachments[id] ?: id); is BDocument -> copy(id=attachments[id] ?: id)
        else -> this
    }
}
internal fun Iterable<BackupRecord>.fileClosure():BackupFiles {
    val photos=linkedSetOf<String>(); val attachments=linkedMapOf<String,ChatAttachment>()
    fun snapshot(s:ChangeSnapshot?) { when(s) { is CountdownSnapshot -> s.value.backgroundId?.let(photos::add); is AppearanceSnapshot -> photos.addAll(s.value.backgroundIds()); else -> Unit } }
    for(r in this) when(r) {
        is BAppearance -> photos.addAll(r.value.backgroundIds()); is BCountdown -> r.value.backgroundId?.let(photos::add)
        is BMessage -> r.event?.changes?.forEach { snapshot(it.before); snapshot(it.after) }
        is BImage -> require(attachments.put(r.id,r.attachment())==null); is BDocument -> require(attachments.put(r.id,r.attachment())==null)
        else -> Unit
    }
    require(photos.all(privatePhotoId::matches) && attachments.keys.all(privatePhotoId::matches)) { "Invalid private file identity" }
    return BackupFiles(photos,attachments)
}
