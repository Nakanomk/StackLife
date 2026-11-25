package io.github.nakanomk.stacklife.widget

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** On the disposable emulator: bind genuine system ids and receive the provider's Binder-delivered views.
 * Does not seed, clear, or edit the application's planner. Never run on the user's ordinary installed package.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BoundWidgetHostTest {
    @Test fun bothProvidersPublishToTheHostAndWeekControlsStayWithTheirOwnId() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 0x534C5739)
        val providers = manager.getInstalledProvidersForPackage(context.packageName, null).associateBy { it.provider }
        fun bind(type: Class<*>): Pair<Int, AppWidgetHostView> {
            val info = requireNotNull(providers[ComponentName(context, type)])
            val both = android.appwidget.AppWidgetProviderInfo.RESIZE_HORIZONTAL or android.appwidget.AppWidgetProviderInfo.RESIZE_VERTICAL
            assertEquals(both, info.resizeMode and both)
            val id = host.allocateAppWidgetId()
            inst.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
            try {
                assertTrue(manager.bindAppWidgetIdIfAllowed(id, info.provider, Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 210)
                }))
            } finally { inst.uiAutomation.dropShellPermissionIdentity() }
            lateinit var view: AppWidgetHostView
            inst.runOnMainSync { view = host.createView(context, id, info) }
            return id to view
        }
        fun eventually(predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            do {
                var done = false
                inst.runOnMainSync { done = predicate() }
                if (done) return
                SystemClock.sleep(40)
            } while (SystemClock.uptimeMillis() < deadline)
            fail("System host did not receive the expected widget state")
        }
        try {
            host.startListening()
            val (_, today) = bind(TodayWidgetProvider::class.java)
            val (firstId, firstWeek) = bind(WeekWidgetProvider::class.java)
            val (secondId, secondWeek) = bind(WeekWidgetProvider::class.java)
            eventually {
                today.findViewById<TextView>(R.id.widget_title)?.text?.let { it.isNotEmpty() && it != "StackLife" } == true &&
                    listOf(firstWeek, secondWeek).all { it.findViewById<View>(R.id.widget_week_bar)?.visibility == View.VISIBLE }
            }
            inst.runOnMainSync { assertTrue(firstWeek.findViewById<View>(R.id.widget_current).performClick()) }
            eventually { manager.getAppWidgetOptions(firstId).containsKey(PICKED_WEEK) }
            assertEquals(FOLLOW, manager.getAppWidgetOptions(firstId).getLong(PICKED_WEEK))
            assertFalse(manager.getAppWidgetOptions(secondId).containsKey(PICKED_WEEK))
        } finally {
            host.stopListening()
            host.deleteHost()
        }
    }
}
