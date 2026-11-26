package io.github.nakanomk.stacklife.reminders

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.time.format.DateTimeFormatter
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.i18n.AppLanguage
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
    private class Node(val id: String?, val text: String?, val description: String?, val bounds: Rect,
        val visible: Boolean, val children: List<Node>) {
        fun tagged(tag: String): Node? {
            if (!visible) return null
            if (id == tag && !bounds.isEmpty) return this
            return children.firstNotNullOfOrNull { it.tagged(tag) }
        }
        fun hasText(value: String): Boolean = visible && (text?.contains(value) == true || children.any { it.hasText(value) })
        fun hasDescription(value: String): Boolean = visible && (description?.contains(value) == true || children.any { it.hasDescription(value) })
        fun dump(out: Appendable, depth: Int = 0) {
            repeat(depth) { out.append("  ") }
            out.appendLine("id=$id visible=$visible bounds=$bounds text=$text description=$description")
            children.forEach { it.dump(out, depth + 1) }
        }
    }

    // Fetch one root and visit each node once. Dates, labels, bounds and both IDs are
    // subsequently compared in this immutable tree, not in successive binder queries.
    private fun snapshot(): Node? {
        val automation = instrument.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        fun copy(node: AccessibilityNodeInfo): Node? {
            if (Build.VERSION.SDK_INT < 34 && !node.refresh()) return null
            return Node(node.viewIdResourceName, node.text?.toString(), node.contentDescription?.toString(),
                Rect().also(node::getBoundsInScreen), node.isVisibleToUser, buildList {
                    for (i in 0 until node.childCount) node.getChild(i)?.let(::copy)?.let(::add)
                })
        }
        return automation.rootInActiveWindow?.let(::copy)
    }

    private fun screenshot(file: File) {
        val image = checkNotNull(instrument.uiAutomation.takeScreenshot())
        try { file.outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }
    private fun waitFor(label: String, test: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < until) { if (test()) return; SystemClock.sleep(65) }
        fail(label)
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
        val dates = listOf(2L, 5L).map(LocalDate.now(zone)::plusDays)
        val times = listOf(java.time.LocalTime.of(11, 15), java.time.LocalTime.of(17, 40))
        val starts = dates.mapIndexed { i, date -> date.atTime(times[i]).atZone(zone).toInstant().toEpochMilli() }
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
        var phase = "delivery"
        var lastTree: Node? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            SystemClock.sleep((trigger + 100 - System.currentTimeMillis()).coerceAtLeast(0))
            app.reminders.receive(Intent(ALARM_ACTION))
            fun notices() = system.activeNotifications.map { it.notification }.associateBy { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() }
            waitFor("Both reminders should appear in the system shade") { names.all { it in notices() } }
            val intents = names.map { notices().getValue(it).contentIntent }
            assertNotEquals("Each occurrence must have its own PendingIntent", intents[0], intents[1])
            var cold: MainActivity? = null
            for (i in names.indices) {
                phase = if (i == 0) "cold" else "warm"
                lastTree = null
                intents[i].send()
                val tag = "block:" + handles.getValue("r$i")
                val other = "block:" + handles.getValue("r${1 - i}")
                waitFor("MainActivity must be resumed for notification $i") { activity() != null }
                val receiver = requireNotNull(activity())
                fun dateText(pattern: Int) = dates[i].format(DateTimeFormatter.ofPattern(receiver.getString(pattern), AppLanguage.locale))
                val formattedDate = dateText(R.string.today_full_date_pattern)
                val caption = if (receiver.resources.configuration.fontScale >= 1.35f) "${dates[i].monthValue}/${dates[i].dayOfMonth}"
                    else dateText(R.string.today_date_pattern)
                waitFor("Notification $i must show ${names[i]} at ${dates[i]} ${times[i]} ($tag)") {
                    val tree = snapshot() ?: return@waitFor false
                    lastTree = tree
                    val date = tree.tagged("day-date")
                    val block = tree.tagged(tag)
                    date != null && date.hasDescription(formattedDate) && date.hasText(caption) &&
                        block != null && block.hasText(names[i]) && block.hasText(times[i].toString()) && tree.tagged(other) == null
                }
                if (hidden) { assertNull(lastTree?.tagged("nav:today")); assertEquals(setOf(Destination.WORKSPACE), (app.planner.observe(PlannerQuery.Settings).first() as PolicyView).appearance.visible) }
                if (i == 0) cold = requireNotNull(activity()) else assertSame(cold, activity())
                if (InstrumentationRegistry.getArguments().getString("captureNavigation") == "true") {
                    automation.waitForIdle(550, 5_000)
                    screenshot(File(app.cacheDir, "u5-notification-$i.png"))
                }
            }
        } catch (failure: AssertionError) {
            val dir = requireNotNull(app.getExternalFilesDir(null))
            val name = "notification-${if (hidden) "hidden" else "visible"}-$phase"
            (lastTree ?: snapshot())?.let { tree ->
                File(dir, "$name-tree.txt").bufferedWriter().use { tree.dump(it) }
            }
            runCatching { screenshot(File(dir, "$name.png")) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw AssertionError("${failure.message}; evidence: $dir/$name", failure)
        } finally {
            activity()?.let { a -> instrument.runOnMainSync { a.finish() } }
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            apply(handles.values.map(::TaskRemove) + if (hidden) listOf(EditAppearance(AppearancePatch.Replace(original))) else emptyList())
        }
        Unit
    }
}
