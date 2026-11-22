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
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import io.github.nakanomk.stacklife.storage.nextReminderGroup
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
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
    private fun scheduler() = AgendaReminders(app, store.db::nextReminderGroup)
    private fun alarmToken(exact: Boolean = false) = PendingIntent.getBroadcast(app, if (exact) 1 else 0, Intent(app, ReminderReceiver::class.java),
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

    @Test fun commitReplacesAlarmAndRealNotificationDismissalSurvivesReconstruction() = runBlocking {
        val reminders = scheduler()
        assumeTrue("Enable schedule notifications on the test device", reminders.canPost())
        val api = RoomPlannerApi(store.db, afterCommit = { reminders.refresh() })
        val now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS)
        commit(api, TaskAdd(TaskInput(title, ref = "a")), SlotSet("@a", now.plusMinutes(40), 5))
        val id = store.state().queue.single().id
        assertNotNull(alarmToken())
        assertEquals(reminders.canBeExact(), alarmToken(exact = true) != null)
        assertTrue(visible().isEmpty())

        commit(api, SlotSet(id, now.plusMinutes(6), 5))
        eventually { visible().size == 1 }
        assertNull(alarmToken())
        val posted = visible().single()
        assertEquals(Notification.VISIBILITY_SECRET, posted.notification.visibility)
        assertNotNull(posted.notification.contentIntent)
        manager.cancel(posted.tag, posted.id)
        eventually { visible().isEmpty() }
        scheduler().refresh()
        SystemClock.sleep(250)
        assertTrue("A repeated wake-up must not undo dismissal", visible().isEmpty())

        commit(api, SlotSet(id, now.plusMinutes(10), 5))
        eventually { visible().size == 1 }
        val moved = visible().single()
        assertNotEquals(posted.tag, moved.tag)
        commit(api, TaskFinish(id))
        eventually { visible().isEmpty() }
        assertNull(alarmToken())
    }

    @Test fun simultaneousRoutineAndTaskShareOneNotificationAndCompletionUpdatesIt() = runBlocking {
        val reminders = scheduler()
        assumeTrue(reminders.canPost())
        val api = RoomPlannerApi(store.db, afterCommit = { reminders.refresh() })
        val local = java.time.ZonedDateTime.now().plusMinutes(8).truncatedTo(ChronoUnit.MINUTES)
        assumeTrue(local.zone.rules.getValidOffsets(local.toLocalDateTime()).size == 1)
        commit(api, SetRoutineTime(RoutineKind.WAKE, local.hour * 60 + local.minute),
            TaskAdd(TaskInput(title, ref = "a")), SlotSet("@a", local.toOffsetDateTime(), 5))
        val tag = "agenda:${local.toInstant().toEpochMilli()}"
        fun group() = manager.activeNotifications.singleOrNull { it.tag == tag }?.notification
        eventually { group()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.contains(title) == true }
        assertFalse(group()?.extras?.getCharSequence(Notification.EXTRA_TITLE) == title)
        commit(api, TaskFinish(store.state().queue.single().id))
        eventually { group()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == RoutineKind.WAKE.label() }
        commit(api, SetRoutineTime(RoutineKind.WAKE, null))
        eventually { group() == null }
    }

    @Test fun exactReplacementWakesManifestReceiverWhichRejectsAStaleAlarm() = runBlocking {
        val reminders = scheduler()
        assumeTrue("Enable notifications and exact alarms on the test device", reminders.canPost() && reminders.canBeExact())
        // The real receiver sees the empty application DB, NOT this isolated fixture. A stale wake-up
        // therefore disarms both tokens and cannot post regardless of the original fixture's title.
        app.reminders.refresh()
        assumeTrue("Use an empty test agenda", alarmToken() == null)
        val api = RoomPlannerApi(store.db, afterCommit = { reminders.refresh() })
        val first = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS).plusHours(1)
        commit(api, TaskAdd(TaskInput(title, ref = "a")), SlotSet("@a", first, 5))
        val replacement = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS).plusMinutes(15).plusSeconds(8)
        commit(api, SlotSet(store.state().queue.single().id, replacement, 5))
        assertNotNull(alarmToken())
        eventually(30_000) { alarmToken() == null }
        assertTrue(visible().isEmpty())
    }

    @Test fun deniedNotificationsInstallNoAlarmAndPostNothing() = runBlocking {
        val reminders = scheduler()
        assumeFalse("Block notifications before running the denied scenario", reminders.canPost())
        val api = RoomPlannerApi(store.db, afterCommit = { reminders.refresh() })
        val now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS)
        commit(api, TaskAdd(TaskInput(title, ref = "a")), SlotSet("@a", now.plusMinutes(6), 5),
            TaskAdd(TaskInput("Later", ref = "b")), SlotSet("@b", now.plusMinutes(40), 5))
        assertNull(alarmToken())
        assertTrue(visible().isEmpty())
        assertEquals(2, store.state().queue.size)
    }
}
