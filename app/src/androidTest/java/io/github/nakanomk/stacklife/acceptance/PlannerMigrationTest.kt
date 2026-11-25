package io.github.nakanomk.stacklife.acceptance

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.storage.*
import io.github.nakanomk.stacklife.reminders.importReceipts
import io.github.nakanomk.stacklife.planner.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerMigrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun oldDatabase(name: String, version: Int = 8, fill: (SQLiteDatabase) -> Unit) {
        val schema = instrumentation.context.assets.open("${PlannerDatabase::class.java.name}/$version.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject }
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.beginTransaction()
            try {
                for (entity in schema.getValue("entities").jsonArray) {
                    val e = entity.jsonObject; val table = e.getValue("tableName").jsonPrimitive.content
                    fun execute(template: JsonElement) = db.execSQL(template.jsonPrimitive.content.replace("\u0024{TABLE_NAME}", table))
                    execute(e.getValue("createSql"))
                    for (index in e["indices"]?.jsonArray.orEmpty()) execute(index.jsonObject.getValue("createSql"))
                }
                for (q in schema.getValue("setupQueries").jsonArray) db.execSQL(q.jsonPrimitive.content)
                fill(db); db.version = version; db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    private inline fun database(name: String, read: (PlannerDatabase) -> Unit) {
        val db = PlannerDatabase.open(context, name)
        try { read(db) } finally { db.close() }
    }

    @Test fun upgradeKeepsLiteralHistoryAndNeverRetriggersOnboarding() {
        val name = "upgrade-${UUID.randomUUID()}.db"
        try {
            oldDatabase(name) { db ->
                db.execSQL("INSERT INTO planner_settings(id,revision,restorePolicy,appearance) VALUES (1,17,'APPEND','{}')")
                db.execSQL("INSERT INTO conversations(id,scope,title,updatedAtMillis,lastMessageId,scopeKey) VALUES (7,'Queue','Original',1000,19,'queue')")
                db.execSQL("INSERT INTO conversation_messages(id,conversationId,role,text) VALUES (19,7,'NOTICE','**historical literal**')")
            }
            repeat(2) { database(name) { db ->
                val settings = requireNotNull(db.plannerDao().settings())
                assertEquals(1, settings.onboardingVersion); assertEquals(17L, settings.revision); assertEquals("APPEND", settings.restorePolicy.name)
                db.openHelper.readableDatabase.query("SELECT id,conversationId,role,text,payloadVersion,eventJson FROM conversation_messages").use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(19L, c.getLong(0)); assertEquals(7L, c.getLong(1))
                    assertEquals("NOTICE", c.getString(2)); assertEquals("**historical literal**", c.getString(3))
                    assertEquals(0, c.getInt(4)); assertTrue(c.isNull(5)); assertFalse(c.moveToNext())
                }
            } }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun oldSchedulesFrameSnapshotsAndClosedRoutineTicksKeepTheirIdentitiesAcrossOpen() = runBlocking {
        val name = "agenda-upgrade-${UUID.randomUUID()}.db"
        val fixed = CourseOccurrenceId("term","hust",1,1,1,2,"Algebra","A")
        try {
            oldDatabase(name) { old ->
                val layout = """{"order":["GRAPHS","COURSES","WORKSPACE","TODAY"],"start":"WORKSPACE","compact":true}"""
                old.execSQL("INSERT INTO planner_settings(id,revision,restorePolicy,appearance,wakeMinute,sleepMinute) VALUES (1,25,'APPEND',?,420,NULL)", arrayOf(layout))
                old.execSQL("INSERT INTO conversations(id,scope,title,updatedAtMillis,lastMessageId) VALUES (7,'Queue','Old',1000,19)")
                old.execSQL("INSERT INTO conversation_messages(id,conversationId,role,text) VALUES (19,7,'USER','Historic **literal**')")
                old.execSQL("INSERT INTO frames VALUES ('f','unchanged',0,'PUSH')")
                old.execSQL("INSERT INTO tasks(id,title,notes,minutes,owner,queuePosition) VALUES ('a','One','kept',8,'QUEUE',0)")
                old.execSQL("INSERT INTO tasks(id,title,notes,minutes,owner,frameId) VALUES ('b','Two','current',9,'FRAME','f')")
                old.execSQL("INSERT INTO frame_items VALUES ('f',0,'b','Original','historic',25,1000,5000)")
                old.execSQL("INSERT INTO time_blocks VALUES ('a',60000,120000),('b',60000,180000)")
                old.execSQL("INSERT INTO course_terms VALUES ('term','hust',0,2,'WINTER',NULL)")
                old.execSQL("INSERT INTO course_meetings VALUES ('term','hust',1,1,'Algebra','A',1,2,90000,150000)")
                old.execSQL("INSERT INTO course_ticks VALUES ('term','hust',1,1,1,2,'Algebra','A')")
                old.execSQL("INSERT INTO routine_ticks VALUES (13,'SLEEP')")
                old.execSQL("INSERT INTO plan_graphs VALUES ('g','Templates')")
                old.execSQL("INSERT INTO graph_nodes VALUES ('g','a','First','original note',8,0,1.5,2.0),('g','b','Second','',10,1,2.5,5.0)")
                old.execSQL("INSERT INTO graph_edges VALUES ('g','a','b')")
            }
            repeat(2) { database(name) { db ->
                val api = RoomPlannerApi(db)
                val imported = db.importReceipts({ mapOf("60000" to setOf("task:a", "task:b")) }, { emptySet() })
                assertEquals(it == 0, imported)
                assertEquals(DeliveryStatus.DISMISSED, db.alarmDao().status(OccurrenceKey.Schedule("a").storageKey(), initialReminder.id, initialReminder.time.trigger(60000)))
                assertEquals("Historic **literal**", db.conversationHistory().recentDialogue(7, 1).single().text)
                val settings = api.observe(PlannerQuery.Settings).first() as PolicyView
                assertTrue(settings.appearance.compact); assertEquals(Destination.WORKSPACE,settings.appearance.start)
                assertEquals(listOf(Destination.GRAPHS,Destination.COURSES,Destination.WORKSPACE,Destination.TODAY,Destination.COUNTDOWN), settings.appearance.visibleOrder)
                assertEquals(1,settings.onboardingVersion); assertTrue((api.observe(PlannerQuery.Countdowns).first() as CountdownsView).items.isEmpty())
                val state = api.observe(PlannerQuery.Workspace).first() as Workspace
                assertEquals(25L,state.revision)
                val order = api.observe(PlannerQuery.Goal("g","b")).first() as GoalOrder
                assertEquals(listOf("a","b"), order.nodes.map { it.id }); assertTrue(order.nodes.all { it.link == null })
                assertEquals("original note", order.nodes.first().notes); assertEquals(1.5f, order.nodes.first().x, 0f)
                assertEquals(QueueTask("a","One","kept",8,ScheduleSummary("a",SchedulePattern.Once(ExactInterval(60000,120000)))),state.queue.single())
                val saved = state.frames.single().items.single()
                assertEquals("Original",saved.title); assertEquals("historic",saved.notes); assertEquals(25,saved.minutes)
                assertTrue(saved.restorable); assertFalse(saved.referenceOnly); assertNull(saved.link)
                assertEquals(ExactInterval(1000,5000),(saved.schedule!!.pattern as SchedulePattern.Once).interval)
                assertEquals(ExactInterval(60000,180000),(saved.current!!.schedule!!.pattern as SchedulePattern.Once).interval)
                val dao = db.scheduleDao()
                assertTrue(dao.completed(OccurrenceKey.Course(fixed).storageKey()))
                assertTrue(dao.completed(OccurrenceKey.Schedule(RoutineKind.SLEEP.scheduleId,13).storageKey()))
                assertFalse(dao.header(RoutineKind.SLEEP.scheduleId)!!.root.enabled)
                assertTrue(dao.header(RoutineKind.WAKE.scheduleId)!!.root.enabled)
                assertEquals(0,dao.segments(listOf(RoutineKind.WAKE.scheduleId)).single().segment.minutes)
                assertEquals(initialReminderPolicy,dao.policy(ReminderSubject.Schedule("b")))
                db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('time_blocks','routine_ticks','course_ticks')").use { assertFalse(it.moveToFirst()) }
            } }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun untouchedOlderInstallIsNotMistakenForAFreshInstall() {
        val old = "untouched-${UUID.randomUUID()}.db"; val fresh = "fresh-${UUID.randomUUID()}.db"
        try {
            oldDatabase(old) { }
            database(old) { assertEquals(1, it.plannerDao().settings()?.onboardingVersion) }
            database(fresh) { assertEquals(0, (it.plannerDao().settings() ?: SettingsRow()).onboardingVersion) }
        } finally { context.deleteDatabase(old); context.deleteDatabase(fresh) }
    }

    @Test fun version12WithNoSettingsOrAnUnfinishedGuideNeverReceivesPracticeData() = runBlocking {
        for (unfinished in listOf(false, true)) {
            val name = "starter-upgrade-${UUID.randomUUID()}.db"
            val original = if (unfinished) listOf(QueueTask("mine", "Keep my task", "My note", 9)) else emptyList()
            try {
                oldDatabase(name, version = 12) { old ->
                    if (unfinished) {
                        old.execSQL("INSERT INTO planner_settings(id,revision,restorePolicy,onboardingVersion) VALUES (1,4,'APPEND',0)")
                        old.execSQL("INSERT INTO tasks(id,title,notes,minutes,owner,queuePosition) VALUES ('mine','Keep my task','My note',9,'QUEUE',0)")
                    }
                }
                database(name) { db ->
                    val api = RoomPlannerApi(db)
                    val settings = api.observe(PlannerQuery.Settings).first() as PolicyView
                    assertEquals(1, settings.onboardingVersion)
                    assertFalse(settings.keepRestoredFrames)
                    assertEquals(if (unfinished) RestorePolicy.APPEND else RestorePolicy.PREPEND, settings.policy)
                    val offer = PlannerCommand.CompleteOnboarding(starterDay = java.time.LocalDate.of(2028, 2, 28))
                    val replay = api.preview(listOf(offer)) as PreviewResult.Ready
                    assertTrue(api.commit(replay.proposalId) is CommitResult.Applied)
                }
                database(name) { db ->
                    val api = RoomPlannerApi(db)
                    val state = api.observe(PlannerQuery.Workspace).first() as Workspace
                    assertEquals(original, state.queue); assertTrue(state.frames.isEmpty())
                    assertTrue((api.observe(PlannerQuery.Graphs).first() as GraphList).graphs.isEmpty())
                    assertTrue((api.observe(PlannerQuery.Countdowns).first() as CountdownsView).items.isEmpty())
                }
            } finally { context.deleteDatabase(name) }
        }
    }
}
