package io.github.nakanomk.stacklife.reminders

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.StackLifeApplication
import io.github.nakanomk.stacklife.acceptance.WorkspaceFixture
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import androidx.room.withTransaction
import io.github.nakanomk.stacklife.storage.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on the dedicated, empty test device: once with notifications on and once with them off.
 * Test agenda mutations go to the fixture database; notification and alarm APIs are the real system services. */
@RunWith(AndroidJUnit4::class)
class ReminderDeliveryTest {
    @get:Rule val store = WorkspaceFixture()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StackLifeApplication
    private val manager get() = checkNotNull(app.getSystemService(NotificationManager::class.java))
    private var now = System.currentTimeMillis() + 86_400_000L // test clock is ahead of real OS deadlines
    private fun scheduler() = AgendaReminders(app, store.db, { now }) { store.api }
    private fun alarmToken(exact: Boolean = false) = PendingIntent.getBroadcast(app, if (exact) 1 else 0, Intent(app, ReminderReceiver::class.java).setAction(ALARM_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
    private val title = "Reminder test ${UUID.randomUUID()}"
    private fun visible() = manager.activeNotifications.filter { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == title }
    private fun eventually(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40)
        assertTrue(condition())
    }
    private suspend fun commit(api: PlannerApi, vararg commands: PlannerCommand) {
        val preview = api.preview(commands.toList())
        assertTrue(preview.toString(), preview is PreviewResult.Ready)
        assertTrue(api.commit((preview as PreviewResult.Ready).proposalId) is CommitResult.Applied)
    }

    @After fun restoreDeviceSchedule(): Unit = runBlocking {
        visible().forEach { manager.cancel(it.tag, it.id) }
        app.reminders.refresh()
    }

    private fun once(id: String, start: Long) = SetSchedule(id, SchedulePattern.Once(ExactInterval(start, start)))
    private fun before(id: String, minutes: Int) = ReminderRule(id, ReminderWhen.BeforeStart(minutes * 60_000L))

    @Test fun multipleRulesDeliverByTriggerEvenAtOrJustPastPointAndNeverResurrectDismissals() = runBlocking {
        val reminders = scheduler()
        assumeTrue(reminders.canPost())
        val start = now + 2 * 3_600_000L
        store.apply(TaskAdd(TaskInput(title, ref = "t")), once("@t", start))
        val key = OccurrenceKey.Schedule(store.state().queue.single().id)
        val rules = listOf(before("90", 90), before("5", 5), before("0", 0), ReminderRule("abs", ReminderWhen.AtInstant(start - 3_600_000)))
        store.apply(SetReminders(ReminderSubject.Occurrence(key), ReminderPolicy(false, rules)))
        reminders.refresh()
        assertNotNull(alarmToken())
        assertEquals(reminders.canBeExact(), alarmToken(true) != null)
        val group = rules.sortedBy { it.time.trigger(start) }
        for ((index, rule) in group.withIndex()) {
            val trigger = rule.time.trigger(start)
            val candidate = store.db.nextReminderGroup(now, ZoneOffset.UTC).single()
            assertEquals(rule.id, candidate.rule.id)
            assertEquals(trigger, candidate.triggerMillis)
            now = trigger + if (rule.id == "0") 1L else 0L
            reminders.receive(Intent(ALARM_ACTION))
            reminders.receive(Intent(ALARM_ACTION))
            eventually { visible().size == index + 1 }
            assertEquals(DeliveryStatus.DELIVERED, store.db.withTransaction { store.db.alarmDao().status(key.storageKey(), rule.id, trigger) })
        }
        assertNull(alarmToken())
        val item = visible().first()
        assertNotNull(item.notification.contentIntent)
        assertEquals(Notification.VISIBILITY_SECRET, item.notification.visibility)
        assertEquals(1, item.notification.actions.size)
        val stamp = android.net.Uri.parse(item.notification.extras.getString("agendaStamp")).alarmStamp()!!
        manager.cancel(item.tag, item.id)
        scheduler().receive(Intent(DISMISS_ACTION, stamp.actionUri()))
        scheduler().receive(Intent(ALARM_ACTION))
        eventually { visible().size == group.size - 1 }
        assertEquals(DeliveryStatus.DISMISSED, store.db.withTransaction { store.db.alarmDao().status(key.storageKey(), stamp.ruleId, stamp.triggerMillis) })
        val other = visible().first().notification
        val old = android.net.Uri.parse(other.extras.getString("agendaStamp")).alarmStamp()!!
        val changed = when (old.time) {
            is ReminderWhen.BeforeStart -> ReminderWhen.AtInstant(old.triggerMillis)
            is ReminderWhen.AtInstant -> ReminderWhen.BeforeStart(start - old.triggerMillis)
        }
        store.apply(SetReminders(ReminderSubject.Occurrence(key), ReminderPolicy(false,
            rules.map { if (it.id == old.ruleId) it.copy(time = changed) else it })))
        reminders.receive(Intent(COMPLETE_ACTION, old.actionUri()))
        assertEquals(key.scheduleId, store.state().queue.single().id)
    }

    @Test fun lateWakeDeliversEveryPreviouslyArmedRuleButNotAnAddedPastRule() = runBlocking {
        val reminders = scheduler(); assumeTrue(reminders.canPost())
        val start = now + 7_200_000L
        store.apply(TaskAdd(TaskInput(title, ref = "t")), once("@t", start))
        val key = OccurrenceKey.Schedule(store.state().queue.single().id)
        val subject = ReminderSubject.Occurrence(key)
        val rules = listOf(before("90", 90), before("5", 5), before("0", 0))
        store.apply(SetReminders(subject, ReminderPolicy(false, rules)))
        reminders.refresh()
        now = start + 1
        val missed = ReminderRule("not-armed", ReminderWhen.AtInstant(start - 10_000L))
        store.apply(SetReminders(subject, ReminderPolicy(false, rules + missed)))
        reminders.receive(Intent(ALARM_ACTION))
        reminders.receive(Intent(ALARM_ACTION))
        eventually { visible().size == 3 }
        store.db.withTransaction {
            for (rule in rules) assertEquals(DeliveryStatus.DELIVERED,
                store.db.alarmDao().status(key.storageKey(), rule.id, rule.time.trigger(start)))
            assertNull(store.db.alarmDao().status(key.storageKey(), missed.id, missed.time.trigger(start)))
        }
    }

    @Test fun tiedItemsKeepSeparateActionsAndReconcileEditMoveCompleteWithoutToggling() = runBlocking {
        val reminders = scheduler(); assumeTrue(reminders.canPost())
        val start = now + 1_800_000L
        store.apply(TaskAdd(TaskInput(title, ref = "a")), once("@a", start), TaskAdd(TaskInput(title, ref = "b")), once("@b", start))
        reminders.refresh()
        now = initialReminder.time.trigger(start)
        val candidates = store.db.nextReminderGroup(now, ZoneOffset.UTC)
        assertEquals(2, candidates.size)
        val (a,b) = candidates.map { it.stamp() }
        reminders.receive(Intent(ALARM_ACTION))
        eventually { visible().map { it.tag }.distinct().size == 2 }
        store.apply(once((a.key as OccurrenceKey.Schedule).scheduleId, start + 120_000L))
        reminders.receive(Intent(COMPLETE_ACTION, a.actionUri()))
        eventually { visible().size == 1 }
        assertEquals(2, store.state().queue.size)
        val updatedTitle = "$title (updated)"
        val tag = visible().single().tag
        store.apply(TaskEdit((b.key as OccurrenceKey.Schedule).scheduleId, title = updatedTitle))
        reminders.refresh()
        fun updated() = manager.activeNotifications.singleOrNull { it.tag == tag && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == updatedTitle }
        eventually { updated() != null }
        val current = android.net.Uri.parse(updated()!!.notification.extras.getString("agendaStamp")).alarmStamp()!!
        assertEquals(b.copy(version = current.version), current)
        assertNotEquals(b.version, current.version)
        reminders.receive(Intent(COMPLETE_ACTION, b.actionUri()))
        assertTrue(store.state().queue.any { it.id == (b.key as OccurrenceKey.Schedule).scheduleId })
        store.apply(TaskAdd(TaskInput("Unrelated change")))
        reminders.receive(Intent(COMPLETE_ACTION, current.actionUri()))
        reminders.receive(Intent(COMPLETE_ACTION, current.actionUri()))
        eventually { updated() == null }
        assertFalse(store.state().queue.any { it.id == (b.key as OccurrenceKey.Schedule).scheduleId })
        assertTrue(store.state().queue.any { it.id == (a.key as OccurrenceKey.Schedule).scheduleId })
        assertNotNull(alarmToken())
    }

    @Test fun exactReplacementWakesManifestReceiverWhichRejectsAStaleAlarm() = runBlocking {
        val reminders = AgendaReminders(app, store.db) { store.api }
        assumeTrue("Enable notifications and exact alarms on the test device", reminders.canPost() && reminders.canBeExact())
        // The real receiver sees the empty application DB, NOT this isolated fixture. A stale wake-up
        // therefore disarms both tokens and cannot post regardless of the original fixture's title.
        app.reminders.refresh()
        assumeTrue("Use an empty test agenda", alarmToken() == null)
        val api = RoomPlannerApi(store.db, afterCommit = { reminders.refresh() })
        commit(api, TaskAdd(TaskInput(title, ref = "a")), once("@a", System.currentTimeMillis() + 3_600_000L))
        commit(api, once(store.state().queue.single().id, System.currentTimeMillis() + 900_000 + 8_000))
        assertNotNull(alarmToken())
        eventually(30_000) { alarmToken() == null }
        assertTrue(visible().isEmpty())
    }

    @Test fun deniedNotificationsInstallNoAlarmAndPostNothing() = runBlocking {
        val reminders = scheduler()
        assumeFalse("Block notifications before running the denied scenario", reminders.canPost())
        val api = RoomPlannerApi(store.db, afterCommit = { reminders.refresh() })
        commit(api, TaskAdd(TaskInput(title, ref = "a")), once("@a", now + 2_400_000L),
            TaskAdd(TaskInput("Later", ref = "b")), once("@b", now + 3_600_000L))
        assertNull(alarmToken())
        assertTrue(visible().isEmpty())
        assertEquals(2, store.state().queue.size)
    }

    @Test fun foregroundDoesNotBackfillNewPastRuleOrDeliverAnUnarmedPastOccurrence() = runBlocking {
        val reminders = scheduler(); assumeTrue(reminders.canPost())
        store.apply(TaskAdd(TaskInput(title, ref = "t")), once("@t", now + 1_000L))
        reminders.refresh(); reminders.receive(Intent(ALARM_ACTION))
        assertNull(alarmToken()); assertTrue(visible().isEmpty())
        val id = store.state().queue.single().id
        store.apply(once(id, now + 1_800_000L))
        reminders.refresh()
        assertNotNull(alarmToken())
        now += 1_800_001
        reminders.receive(Intent(Intent.ACTION_BOOT_COMPLETED))
        reminders.receive(Intent(ALARM_ACTION))
        assertNull(alarmToken()); assertTrue(visible().isEmpty())
    }

    @Test fun laterEventCanRingFirstAndExplicitEmptyDailyOverrideDoesNotDisableFollowingDays() = runBlocking {
        val z = ZoneId.of("America/New_York")
        val first = LocalDate.of(2026, 1, 1).toEpochDay()
        val target = LocalDate.of(2242, 9, 15)
        val start = target.atTime(12, 0).atZone(z).toInstant().toEpochMilli()
        val segment = DailySegment(first, 12 * 60, z.id, 0)
        store.apply(TaskAdd(TaskInput("Series", ref = "daily")), SetSchedule("@daily", SchedulePattern.Daily(listOf(segment))),
            TaskAdd(TaskInput("Earlier", ref = "one")), once("@one", start - 600_000L))
        val id = store.state().queue.single { it.title == "Series" }.id
        val key = OccurrenceKey.Schedule(id, target.toEpochDay())
        store.apply(SetReminders(ReminderSubject.Schedule(id), ReminderPolicy(false, listOf(before("90", 90)))))
        val from = start - 2 * 3_600_000L
        suspend fun next(time: Long) = store.db.nextReminderGroup(time, ZoneOffset.UTC).single()
        assertEquals(key, next(from).occurrence.key)
        store.apply(SetReminders(ReminderSubject.Occurrence(key), ReminderPolicy(false, emptyList())))
        assertEquals("Earlier", next(from).occurrence.title)
        val tomorrow = key.copy(originalDay = target.plusDays(1).toEpochDay())
        assertEquals(tomorrow, next(start).occurrence.key)
        store.apply(SetOccurrenceOverride(tomorrow, EditScope.THIS, interval = ExactInterval(start + 10_000, start + 10_000)))
        assertEquals(tomorrow, next(from).occurrence.key)
        store.apply(SetOccurrenceCompletion(tomorrow, true))
        assertEquals("Earlier", next(from).occurrence.title)
    }

    @Test fun malformedLegacyLedgerRollsBackAndValidImportSurvivesReopenWithoutReplay() = runBlocking {
        val db = store.db
        val start = now + 3_600_000L
        val key = OccurrenceKey.Schedule("a:b").storageKey()
        val trigger = initialReminder.time.trigger(start)
        val legacy = linkedMapOf(start.toString() to linkedSetOf("task:a:b", "invalid"))
        try { db.importReceipts({ legacy }, { emptySet() }); fail("Malformed ledger was accepted") }
        catch (_: IllegalArgumentException) { }
        db.withTransaction {
            assertFalse(db.alarmDao().imported())
            assertNull(db.alarmDao().status(key, initialReminder.id, trigger))
        }
        legacy.getValue(start.toString()).remove("invalid")
        val routine = "routine:WAKE:2026-11-05"
        legacy[(start + 1).toString()] = linkedSetOf(routine)
        db.importReceipts({ legacy }, { setOf(start + 1) })
        store.reopen()
        store.db.importReceipts({ error("Imported ledger read again") }, { error("Imported ledger read again") })
        assertEquals(DeliveryStatus.DISMISSED, store.db.withTransaction { store.db.alarmDao().status(key, initialReminder.id, trigger) })
        assertEquals(DeliveryStatus.DELIVERED, store.db.withTransaction {
            store.db.alarmDao().status(legacyOccurrence(routine).storageKey(), initialReminder.id, trigger + 1)
        })
    }

    @Test fun bridgeFailureDoesNotTurnSuccessfulEditIntoFailedEditOrArmFallback() = runBlocking {
        val reminders = scheduler()
        app.reminders.refresh()
        assumeTrue("Use empty application DB", alarmToken() == null)
        store.db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_bridge BEFORE UPDATE OF receiptsImported ON planner_settings
            WHEN NEW.receiptsImported=1 BEGIN SELECT RAISE(ABORT,'receipt bridge'); END""")
        val api = RoomPlannerApi(store.db, afterCommit = { reminders.refresh() })
        commit(api, TaskAdd(TaskInput(title, ref = "a")), once("@a", now + 3_600_000L))
        assertEquals(title, store.state().queue.single().title)
        assertTrue(reminders.schedulingFailed.value)
        assertNull(alarmToken())
    }

    @Test fun openAndActionDataRoundTripFullIdentityButNeverInterchange() {
        val key = OccurrenceKey.Course(CourseOccurrenceId("T/:😀", "a:b", 1, 4, 3, 4, "A/B", "Lab #2"))
        val target = ReminderTarget(key, now)
        val stamp = AlarmStamp(key, "r/a:b", now - 1L, 7L, ReminderWhen.BeforeStart(1L))
        assertEquals(target, Intent(Intent.ACTION_VIEW, target.uri()).reminderTarget())
        assertEquals(stamp, stamp.actionUri().alarmStamp())
        assertNull(Intent(COMPLETE_ACTION, target.uri()).reminderTarget())
        assertNull(Intent(Intent.ACTION_VIEW, stamp.actionUri()).reminderTarget())
        assertNull(target.uri().alarmStamp())
    }
}
