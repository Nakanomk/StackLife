package io.github.nakanomk.stacklife.storage

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.acceptance.WorkspaceFixture
import io.github.nakanomk.stacklife.archive.*
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestoreTransactionTest {
    @get:Rule val source=WorkspaceFixture()
    @get:Rule val target=WorkspaceFixture()
    private val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"backup-fixture-${UUID.randomUUID()}")
    @After fun clearRecords() { dir.deleteRecursively() }
    private val monday=LocalDate.of(2026,9,7)
    private val day=monday.toEpochDay(); private val moved=(day+2)*86_400_000L+600_000L
    private val photo="00000000-0000-4000-a000-000000000001"; private val image="00000000-0000-4000-a000-000000000002"
    private val doc="00000000-0000-4000-a000-000000000003"; private val newPhoto="00000000-0000-4000-a000-000000000004"
    private val newDoc="00000000-0000-4000-a000-000000000005"
    private val term=TermSpec("T",monday,3,BellSchedule.SUMMER)
    private val meeting=term.meeting(1,1,"Algorithms","101",1,2)
    private val course=OccurrenceKey.Course(CourseOccurrenceId.of(term.id,MANUAL_SOURCE,meeting))
    private val repeatKey=OccurrenceKey.Schedule("A",day)
    private val wallpaper=Appearance(background=WallpaperSpec(imageId=photo),pageBackgrounds=mapOf(Destination.GRAPHS to WallpaperSpec(imageId=photo,scale=2f)))
    private val snapshot=SavedSchedule(schedule=ScheduleSummary("B",SchedulePattern.Once(ExactInterval(moved,moved+1000))))
    private val countdown=Countdown("D","Exam",day+10,CountdownMode.COUNT_DOWN,true,0,photo)
    private val event=ChatEvent(ProposalStatus.PROPOSED,listOf(PlannerChange("look",AppearanceSnapshot(wallpaper),AppearanceSnapshot(Appearance())),
        PlannerChange("D",null,CountdownSnapshot(countdown))))
    private val all=BackupPart.entries.toSet()-BackupPart.CONNECTION

    private suspend fun fixture()=source.db.withTransaction {
        val db=source.db; val p=db.plannerDao(); val s=db.scheduleDao()
        p.saveSettings(SettingsRow(revision=41,restorePolicy=RestorePolicy.APPEND,appearance=encodeAppearance(wallpaper),keepRestoredFrames=true))
        p.insertFrames(listOf(FrameRow("z-first","older",1000,FrameSource.PUSH),FrameRow("a-last","newer",1000,FrameSource.PUSH)))
        p.insertTasks(listOf(TaskRow("A","Queued","actual",30,Owner.QUEUE,null,0),TaskRow("B","Edited B","live",40,Owner.FRAME,"z-first",null,icon=TaskIcon.PERSON),
            TaskRow("C","Another","",5,Owner.FRAME,"a-last",null),TaskRow("retired","Removed","",0,Owner.REMOVED,null,null),
            TaskRow("done","Done","",0,Owner.DONE,null,null,completedFromPosition=0)))
        p.insertItems(listOf(FrameItemRow("z-first",0,"B","Saved B","historic",15,snapshotJson.encodeToString(snapshot),icon=TaskIcon.EVENT),
            FrameItemRow("z-first",1,"retired","Old title","",0,null),FrameItemRow("a-last",0,"C","Another","",5,null),
            FrameItemRow("a-last",1,"A","Reference only","",30,null,referenceOnly=true)))
        s.write(ScheduleRow("A","A",null,null,true,null,41)); s.writeSegments(listOf(SegmentRow("A",DailySegment(day,540,"UTC",30))))
        s.write(ExceptionRow("A",day,moved,moved+1_800_000L,41)); s.complete(CompletionRow(repeatKey.storageKey()))
        s.putPolicy(ReminderSubject.Occurrence(repeatKey),ReminderPolicy(false,listOf(ReminderRule("start",ReminderWhen.BeforeStart(0)))))
        db.courseDao().put(TermRow(term.id,MANUAL_SOURCE,day,term.weeks,term.initial,null)); db.courseDao().insert(listOf(CourseRow(term.id,MANUAL_SOURCE,meeting).apply { occurrenceVersion=41 }))
        db.courseDao().put(TermRow(term.id,HUST_SOURCE,day,term.weeks,term.initial,null)); db.courseDao().insert(listOf(CourseRow(term.id,HUST_SOURCE,meeting)))
        s.complete(CompletionRow(course.storageKey())); s.putPolicy(ReminderSubject.Occurrence(course),ReminderPolicy(false,listOf(ReminderRule("bell",ReminderWhen.AtInstant(meeting.startMillis)))))
        val g=db.graphDao(); g.putGraph(GraphRow("g","Goal")); val link=GraphLink.Occurrence("A",day)
        g.putNodes(listOf(GraphNodeRow("g","source","Queued","",30,0,0f,0f,link.storageKey(),"A"),GraphNodeRow("g","template","Template","never generated",10,1,1f,1f)))
        g.putEdges(listOf(GraphEdgeRow("g","source","template")))
        db.countdownDao().put(listOf(CountdownRow("D",countdown.title,countdown.targetDay,countdown.mode,true,0,photo,countdown.maskOpacity)))
        val history=db.conversationHistory(); val thread=history.sendUser(null,"day","chat",null,listOf(ChatDocument(doc,"text/plain","notes.txt",4),ChatImage(image,"image/png",1,1,70)))
        history.propose(thread.conversationId,event)
        db.alarmDao().acknowledge(ReminderReceiptRow(repeatKey.storageKey(),"old",moved,DeliveryStatus.DELIVERED))
        Unit
    }
    private suspend fun transfer(parts:Set<BackupPart>):IncomingTables {
        val tables=BackupTables(source.db); val exported=source.db.withTransaction { tables.export(parts,dir) }; val data=tables.read(parts,dir)
        assertEquals(exported.counts,data.counts); assertEquals(exported.files,data.files); return data
    }
    private suspend fun preview(data:IncomingTables,photos:Map<String,String> = emptyMap(),attachments:Map<String,String> = emptyMap())=target.db.withTransaction {
        BackupTables(target.db).plan(data,photos,attachments)
    }
    private suspend fun apply(data:IncomingTables,photos:Map<String,String> = emptyMap(),attachments:Map<String,String> = emptyMap())=target.db.withTransaction {
        val db=target.db; val plan=BackupTables(db).plan(data,photos,attachments); assertEquals(emptyList<String>(),plan.conflicts)
        val revision=(db.plannerDao().settings()?.revision ?: 0L)+1
        plan.apply(revision); db.plannerDao().saveSettings((db.plannerDao().settings() ?: SettingsRow()).copy(revision=revision))
    }

    @Test fun fullRoundTripReopensWithOriginalOccurrenceOrderSnapshotsAndChatFiles()=runBlocking {
        fixture(); val data=transfer(all); assertEquals(setOf(photo),data.files.backgrounds); assertEquals(setOf(image,doc),data.files.attachments.keys)
        target.db.withTransaction { target.db.alarmDao().acknowledge(ReminderReceiptRow(repeatKey.storageKey(),"keep",moved,DeliveryStatus.DISMISSED)) }
        apply(data,mapOf(photo to newPhoto),mapOf(doc to newDoc)); target.reopen(); val db=target.db
        db.withTransaction {
            val p=db.plannerDao(); assertEquals(listOf("A"),p.queueIds()); assertEquals(listOf("a-last","z-first"),p.frames().map { it.id })
            assertEquals("Edited B",p.currentTask("B")!!.title); val saved=p.savedItems("z-first").first(); assertEquals("Saved B",saved.title); assertEquals(TaskIcon.EVENT,saved.icon); assertEquals(snapshot,saved.savedSchedule())
            assertEquals(Owner.REMOVED,p.tasks(listOf("retired")).single().owner); assertEquals(Owner.DONE,p.tasks(listOf("done")).single().owner)
            assertEquals(listOf("B"),p.restorableIds("z-first")); assertEquals(listOf("C"),p.restorableIds("a-last"))
            val schedule=ScheduleReader(db.scheduleDao()); val actual=schedule.resolve(repeatKey,ZoneOffset.UTC)!!; assertEquals(moved,actual.startMillis); assertTrue(actual.completed); assertEquals(1L,actual.version)
            assertEquals(listOf("start"),db.scheduleDao().policy(ReminderSubject.Occurrence(repeatKey)).rules.map { it.id }); assertNull(db.alarmDao().status(repeatKey.storageKey(),"old",moved))
            assertEquals(DeliveryStatus.DISMISSED,db.alarmDao().status(repeatKey.storageKey(),"keep",moved))
            assertEquals(meeting,db.courseDao().meetings("T",MANUAL_SOURCE).single().meeting); assertTrue(db.scheduleDao().completed(course.storageKey())); assertEquals("bell",db.scheduleDao().policy(ReminderSubject.Occurrence(course)).rules.single().id)
            assertEquals(meeting,db.courseDao().meetings("T",HUST_SOURCE).single().meeting); assertFalse(db.scheduleDao().completed(OccurrenceKey.Course(CourseOccurrenceId.of(term.id,HUST_SOURCE,meeting)).storageKey()))
            assertEquals(listOf(GraphEdge("source","template")),db.graphDao().edges("g")); assertEquals(GraphLink.Occurrence("A",day),db.graphDao().nodes("g").first().link); assertNull(db.graphDao().nodes("g").last().link)
            assertEquals(newPhoto,db.countdownDao().one("D")!!.backgroundId); val settings=p.settings()!!; assertEquals(1L,settings.revision); assertTrue(settings.keepRestoredFrames); assertEquals(RestorePolicy.APPEND,settings.restorePolicy); assertEquals(setOf(newPhoto),decodeAppearance(settings.appearance).backgroundIds()); assertTrue(settings.onboardingVersion>0)
        }
        val history=db.conversationHistory(); val thread=history.observeConversations().first().single(); val messages=history.observeMessages(thread.id).first(); assertEquals(listOf(newDoc,image),messages.first().attachments.map { it.id })
        val savedEvent=messages.last().event!!; assertEquals(ProposalStatus.PROPOSED,savedEvent.status)
        assertEquals(newPhoto,(savedEvent.changes.first().before as AppearanceSnapshot).value.background.imageId); assertEquals(newPhoto,(savedEvent.changes.last().after as CountdownSnapshot).value.backgroundId)
        assertEquals(emptyList<String>(),preview(data,mapOf(photo to newPhoto),mapOf(doc to newDoc)).conflicts)
        assertTrue(preview(data,mapOf(photo to newPhoto),mapOf(doc to newDoc)).counts.values.all { it==BackupCounts() })
    }

    @Test fun todayOnlyBindsToExistingFramedTaskWithoutMovingOrOverwritingText()=runBlocking {
        fixture(); val data=transfer(setOf(BackupPart.TODAY)); target.db.withTransaction { val p=target.db.plannerDao(); p.insertFrames(listOf(FrameRow("local","local",1,FrameSource.PUSH)))
            p.insertTasks(listOf(TaskRow("A","Local A","keep",2,Owner.FRAME,"local",null))); p.insertItems(listOf(FrameItemRow("local",0,"A","Old","",1,null))) }
        apply(data); target.reopen(); target.db.withTransaction { val db=target.db; val p=db.plannerDao(); assertTrue(p.queueIds().isEmpty()); assertEquals(listOf("A"),p.restorableIds("local")); assertEquals("Local A",p.currentTask("A")!!.title)
            assertEquals(moved,ScheduleReader(db.scheduleDao()).resolve(repeatKey,ZoneOffset.UTC)!!.startMillis); assertFalse(db.scheduleDao().completed(course.storageKey())) }
    }
    @Test fun queueOnlyRetainsLiveScheduleAndImmutableUnselectedFrameHistory()=runBlocking {
        fixture(); val data=transfer(setOf(BackupPart.QUEUE)); target.db.withTransaction { val db=target.db; val p=db.plannerDao(); p.insertFrames(listOf(FrameRow("history","old",1,FrameSource.PUSH)))
            p.insertTasks(listOf(TaskRow("A","Old","",1,Owner.QUEUE,null,1),TaskRow("lost","Lost","",1,Owner.QUEUE,null,0)))
            p.insertItems(listOf(FrameItemRow("history",0,"lost","Immutable","saved note",7,null))); db.scheduleDao().write(ScheduleRow("A","A",500,700,true,null,18)) }
        val plan=preview(data); assertTrue(plan.impacts.isNotEmpty()); assertEquals(BackupCounts(updated=1,removed=1),plan.counts[BackupPart.QUEUE]); apply(data)
        target.db.withTransaction { val db=target.db; val p=db.plannerDao(); assertEquals(listOf("A"),p.queueIds()); assertEquals("Queued",p.currentTask("A")!!.title); assertEquals(500L,db.scheduleDao().header("A")!!.root.startMillis)
            assertEquals("Immutable",p.savedItems("history").single().title); assertEquals(Owner.REMOVED,p.tasks(listOf("lost")).single().owner) }
    }
    @Test fun stackOnlyRestoresOwnersAndHistoricalMissingSourcesWithoutCreatingQueueTasks()=runBlocking {
        fixture(); val data=transfer(setOf(BackupPart.STACK)); apply(data); val db=target.db
        db.withTransaction { val p=db.plannerDao(); assertEquals(listOf("B"),p.restorableIds("z-first")); assertEquals(listOf("C"),p.restorableIds("a-last")); assertTrue(p.queueIds().isEmpty()); assertNull(p.currentTask("A")); assertNull(db.scheduleDao().header("B")); assertEquals(snapshot,p.savedItems("z-first").first().savedSchedule()); assertTrue(p.savedItems("a-last").last().referenceOnly) }
    }
    @Test fun sameFrameIdWithoutAnOwningHistoricalItemReopensCompletionIntoQueue()=runBlocking {
        fixture(); val data=transfer(setOf(BackupPart.STACK))
        target.db.withTransaction {
            val p=target.db.plannerDao()
            p.insertFrames(listOf(FrameRow("z-first","old frame",0,FrameSource.PUSH)))
            p.insertTasks(listOf(TaskRow("old-member","Finished","",1,Owner.DONE,null,null,completedFromFrame="z-first")))
            p.insertItems(listOf(FrameItemRow("z-first",0,"old-member","Finished","",1,null)))
            target.db.scheduleDao().write(ScheduleRow("old-member","old-member",500,600,true,null,0))
        }
        apply(data); target.reopen()
        target.apply(PlannerCommand.SetOccurrenceCompletion(OccurrenceKey.Schedule("old-member"),false))
        target.db.withTransaction {
            assertEquals(listOf("old-member"),target.db.plannerDao().queueIds())
            assertEquals(listOf("B"),target.db.plannerDao().restorableIds("z-first"))
        }
    }
    @Test fun missingScheduleSourceAndCrossOwnerConflictLeaveAllRowsUntouched()=runBlocking {
        fixture(); val full=transfer(all); val today=full.select(setOf(BackupPart.TODAY))
        val missing=preview(today); assertTrue(missing.conflicts.any { "A" in it && io.github.nakanomk.stacklife.i18n.uiText("Queue","队列") in it })
        assertTrue(today.files.backgrounds.isEmpty()); assertTrue(today.files.attachments.isEmpty())
        val queue=full.select(setOf(BackupPart.QUEUE))
        target.db.withTransaction { val p=target.db.plannerDao(); p.insertFrames(listOf(FrameRow("f","x",1,FrameSource.PUSH))); p.insertTasks(listOf(TaskRow("A","local","",1,Owner.FRAME,"f",null))); p.insertItems(listOf(FrameItemRow("f",0,"A","historical","",1,null))) }
        target.db.withTransaction { val plan=BackupTables(target.db).plan(queue,emptyMap(),emptyMap()); assertTrue(plan.conflicts.any { "A" in it }); assertTrue(runCatching { plan.apply(1) }.isFailure); assertNull(target.db.plannerDao().settings()); assertEquals(listOf("A"),target.db.plannerDao().restorableIds("f")) }
    }
    @Test fun deletingSourceIsBlockedByWorkingUnselectedGraphAndUnselectedSchedule()=runBlocking {
        fixture(); val queue=transfer(setOf(BackupPart.QUEUE)); target.db.withTransaction { val db=target.db; db.plannerDao().insertTasks(listOf(TaskRow("local","Y","",1,Owner.QUEUE,null,0))); db.scheduleDao().write(ScheduleRow("local","local",2,3,true,null,0)); val g=db.graphDao(); g.putGraph(GraphRow("g","Y")); g.putNodes(listOf(GraphNodeRow("g","n","Y","",1,0,0f,0f,GraphLink.Task("local").storageKey(),"local"))) }
        val plan=preview(queue); assertEquals(2,plan.conflicts.size); target.db.withTransaction { assertEquals(listOf("local"),target.db.plannerDao().queueIds()); assertNotNull(target.db.scheduleDao().header("local")); assertNotNull(GraphLinkReader(target.db).source(GraphLink.Task("local"))) }
    }
    @Test fun lateConstraintFailureRollsBackAllSelectedCategoriesAndSettings()=runBlocking {
        fixture(); val data=transfer(all); target.db.withTransaction { target.db.plannerDao().insertTasks(listOf(TaskRow("keep","before","",1,Owner.QUEUE,null,0)))
            target.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_backup BEFORE INSERT ON graph_edges BEGIN SELECT RAISE(ABORT,'injected write failure'); END") }
        assertTrue(preview(data).conflicts.isEmpty())
        val error=runCatching { apply(data) }.exceptionOrNull()
        assertTrue(generateSequence(error) { it.cause }.any { it.message?.contains("injected write failure")==true })
        target.reopen(); target.db.withTransaction { val db=target.db; assertEquals(listOf("keep"),db.plannerDao().queueIds()); assertTrue(db.plannerDao().frames().isEmpty()); assertNull(db.plannerDao().settings()); assertNull(db.scheduleDao().header("A")); assertTrue(db.courseDao().terms().isEmpty()); assertTrue(db.graphDao().list().isEmpty()) }
    }
    @Test fun confirmStampDetectsStatusOnlyChatChangeAndDeletionWithoutPlannerRevision()=runBlocking {
        fixture(); val data=transfer(setOf(BackupPart.QUEUE)); val history=target.db.conversationHistory(); val sent=history.sendUser(null,"day","old",null,emptyList()); val id=history.propose(sent.conversationId,event)
        val first=preview(data).stamp; history.updateEvent(id,Json.encodeToString(event.copy(status=ProposalStatus.DISCARDED))); val second=preview(data).stamp; assertNotEquals(first,second); history.delete(sent.conversationId); assertNotEquals(second,preview(data).stamp)
    }
    @Test fun explicitEmptyQueueClearsOnlyQueueAndOversizedOrInvalidUtf8NeverPreviews()=runBlocking {
        target.db.withTransaction {
            target.db.plannerDao().insertTasks(listOf(TaskRow("only","Only","",1,Owner.QUEUE,null,0)))
            target.db.countdownDao().put(listOf(CountdownRow("keep","Keep",day,CountdownMode.COUNT_DOWN,false,0,null,.3f)))
        }
        val empty=transfer(setOf(BackupPart.QUEUE)); assertEquals(0,empty.counts[BackupPart.QUEUE]); apply(empty)
        target.db.withTransaction { assertTrue(target.db.plannerDao().queueIds().isEmpty()); assertEquals("Keep",target.db.countdownDao().one("keep")!!.title) }
        val f=File(dir,"queue.jsonl"); f.writeBytes(byteArrayOf(0xc3.toByte(),0x28)); val tables=BackupTables(target.db)
        assertTrue(runCatching { tables.read(setOf(BackupPart.QUEUE),dir) }.isFailure)
        java.io.RandomAccessFile(f,"rw").use { it.setLength(8L*1024*1024+1) }
        assertTrue(runCatching { tables.read(setOf(BackupPart.QUEUE),dir) }.isFailure)
    }
    @Test fun invalidDagAndDuplicateOrMalformedRowsCannotBecomeAPreview()=runBlocking {
        fixture(); transfer(setOf(BackupPart.GRAPHS)); val file=File(dir,"graphs.jsonl"); file.appendText(backupFormat.encodeToString<BackupRecord>(BEdge("g",GraphEdge("template","source")))+"\n")
        val tables=BackupTables(target.db); assertTrue(runCatching { tables.read(setOf(BackupPart.GRAPHS),dir) }.isFailure)
        transfer(setOf(BackupPart.QUEUE)); val queue=File(dir,"queue.jsonl"); val line=queue.readText(); queue.appendText(line); assertTrue(runCatching { tables.read(setOf(BackupPart.QUEUE),dir) }.isFailure)
        queue.writeText(line.replace("\"minutes\":30","\"minutes\":30,\"minutes\":1")); assertTrue(runCatching { tables.read(setOf(BackupPart.QUEUE),dir) }.isFailure)
        target.db.withTransaction { assertTrue(target.db.plannerDao().queueIds().isEmpty()); assertTrue(target.db.graphDao().list().isEmpty()) }
    }
}
