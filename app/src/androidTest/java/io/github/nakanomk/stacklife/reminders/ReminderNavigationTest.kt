package io.github.nakanomk.stacklife.reminders

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.nakanomk.stacklife.MainActivity
import io.github.nakanomk.stacklife.StackLifeApplication
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Dedicated test user only. Both launches use the PendingIntent supplied by NotificationManager. */
class ReminderNavigationTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as StackLifeApplication

    private fun activity(): MainActivity? {
        var result: MainActivity? = null
        instrument.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
        }
        return result
    }
    private fun surface(tag: String, labels: MutableList<String>? = null): Boolean {
        fun visit(n: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            if (!n.isVisibleToUser) return false
            val bounds = Rect().also(n::getBoundsInScreen)
            if (bounds.width() > 0 && bounds.height() > 0 && n.viewIdResourceName == tag) return true
            (n.contentDescription ?: n.text ?: n.viewIdResourceName)?.let { labels?.add(it.toString()) }
            for (i in 0 until n.childCount) n.getChild(i)?.let { if (visit(it)) return true }
            return false
        }
        return instrument.uiAutomation.rootInActiveWindow?.let(::visit) == true
    }
    private fun waitFor(label: String, test: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < until) { if (test()) return; SystemClock.sleep(65) }
        val labels = mutableListOf<String>(); surface("", labels)
        assertTrue(label + ": " + labels.takeLast(55), test())
    }
    private suspend fun apply(commands: List<PlannerCommand>): Map<String, String> {
        val p = app.planner.preview(commands)
        assertTrue(p.toString(), p is PreviewResult.Ready)
        p as PreviewResult.Ready
        assertTrue(app.planner.commit(p.proposalId) is CommitResult.Applied)
        return p.refs
    }
    @Test fun notificationsOpenTheirOwnDatesOnColdAndWarmActivity() = runBlocking {
        assumeTrue("Enable notifications on the dedicated test device", app.reminders.canPost())
        assertNull(activity())
        val system = app.getSystemService(NotificationManager::class.java)!!
        val zone = ZoneId.systemDefault()
        val suffix = UUID.randomUUID().toString().take(8)
        val names = listOf("Cold $suffix", "Warm $suffix")
        val starts = listOf(2L, 5L).map { LocalDate.now(zone).plusDays(it).atTime(11, 15).atZone(zone).toInstant().toEpochMilli() }
        val trigger = System.currentTimeMillis() + 4_000
        val hidden = InstrumentationRegistry.getArguments().getString("hiddenToday") == "yes"
        val original = (app.planner.observe(PlannerQuery.Settings).first() as PolicyView).appearance
        val handles = apply(buildList {
            if (hidden) add(EditAppearance(AppearancePatch.Replace(original.withVisible(setOf(Destination.WORKSPACE)))))
            for (i in names.indices) {
                add(TaskAdd(TaskInput(names[i], minutes = 0, ref = "r$i")))
                add(SetSchedule("@r$i", SchedulePattern.Once(ExactInterval(starts[i], starts[i]))))
                add(SetReminders(ReminderSubject.Schedule("@r$i"), ReminderPolicy(false, emptyList())))
                add(SetReminders(ReminderSubject.Occurrence(OccurrenceKey.Schedule("@r$i")),
                    ReminderPolicy(false, listOf(ReminderRule("open", ReminderWhen.AtInstant(trigger))))))
            }
        })
        val automation = instrument.uiAutomation
        val oldFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            SystemClock.sleep((trigger + 100 - System.currentTimeMillis()).coerceAtLeast(0))
            app.reminders.receive(Intent(ALARM_ACTION))
            fun notices() = system.activeNotifications.map { it.notification }.associateBy { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() }
            waitFor("Both reminders should appear in the system shade") { names.all { it in notices() } }
            val intents = names.map { notices().getValue(it).contentIntent }
            var cold: MainActivity? = null
            for (i in names.indices) {
                intents[i].send()
                waitFor("Notification "+i+" did not reveal its own event on screen") {
                    surface("block:" + handles.getValue("r$i"))
                }
                if (hidden) { assertFalse(surface("nav:today")); assertEquals(setOf(Destination.WORKSPACE), (app.planner.observe(PlannerQuery.Settings).first() as PolicyView).appearance.visible) }
                if (i == 0) cold = requireNotNull(activity()) else assertSame(cold, activity())
                if (InstrumentationRegistry.getArguments().getString("captureNavigation") == "true") {
                    automation.waitForIdle(550, 5_000)
                    val image = checkNotNull(automation.takeScreenshot())
                    try { java.io.File(app.cacheDir, "u5-notification-$i.png").outputStream().use {
                        assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    } } finally { image.recycle() }
                }
            }
        } finally {
            activity()?.let { a -> instrument.runOnMainSync { a.finish() } }
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            apply(handles.values.map(::TaskRemove) + if (hidden) listOf(EditAppearance(AppearancePatch.Replace(original))) else emptyList())
        }
        Unit
    }
}
