package io.github.nakanomk.stacklife.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeApplication
import io.github.nakanomk.stacklife.i18n.AppLanguage
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.planner.Terms
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class TodayWidgetProvider : ScheduleWidgetProvider()
class WeekWidgetProvider : ScheduleWidgetProvider()

abstract class ScheduleWidgetProvider : AppWidgetProvider() {
    private fun update(context: Context) = WorkspaceWidgets.schedule(context.applicationContext, goAsync())
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(context)
    override fun onDeleted(context: Context, ids: IntArray) = update(context)
    override fun onDisabled(context: Context) = update(context)
}

/** Non-exported recipient of system clock changes, our own immutable controls and the one-shot boundary alarm. */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CONTROL -> return applyControl(context, intent)
            REFRESH, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_LOCALE_CHANGED -> Unit
            else -> return
        }
        WorkspaceWidgets.schedule(context.applicationContext, goAsync())
    }

    private fun applyControl(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val fields = uri.pathSegments
        if (uri.scheme != "stacklife-widget" || uri.host != "control" || fields.size != 4) return
        val id = fields[0].toIntOrNull() ?: return
        val value = fields[2].toLongOrNull() ?: return
        val date = fields[3].toLongOrNull() ?: return
        val manager = AppWidgetManager.getInstance(context)
        val provider = manager.getAppWidgetInfo(id)?.provider ?: return
        val weekly = provider == ComponentName(context, WeekWidgetProvider::class.java)
        if (!weekly && provider != ComponentName(context, TodayWidgetProvider::class.java)) return
        val patch = Bundle()
        when (fields[1]) {
            "week" -> {
                if (!weekly) return
                patch.putLong(PICKED_WEEK, value)
                patch.putInt(PAGE_START, -1)
            }
            "page" -> {
                if (value !in 0..Int.MAX_VALUE.toLong()) return
                patch.putInt(PAGE_START, value.toInt())
                patch.putLong(PAGE_DATE, date)
            }
            else -> return
        }
        // Options belong to this host instance; removing it drops its navigation without leaving a local id behind.
        manager.updateAppWidgetOptions(id, patch)
    }
}

internal object WorkspaceWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active: Job? = null // Both scheduling and publication are serialized on the broadcast (main) thread.

    fun refresh(context: Context) {
        context.sendBroadcast(Intent(context, WidgetRefreshReceiver::class.java).setAction(REFRESH))
    }

    fun schedule(app: Context, pending: BroadcastReceiver.PendingResult) {
        active?.cancel()
        active = scope.launch {
            val manager = AppWidgetManager.getInstance(app)
            val days = manager.getAppWidgetIds(ComponentName(app, TodayWidgetProvider::class.java))
            val weeks = manager.getAppWidgetIds(ComponentName(app, WeekWidgetProvider::class.java))
            if (days.isEmpty() && weeks.isEmpty()) {
                boundary(app, null)
                return@launch
            }
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val context = if (app.resources.configuration.locales[0] == AppLanguage.locale) app else
                app.createConfigurationContext(Configuration().apply { setLocale(AppLanguage.locale) })
            var expiry = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            try {
                withTimeout(8_000L) {
                    withContext(Dispatchers.IO) {
                        val planner = (app as StackLifeApplication).planner
                        val day = if (days.isNotEmpty()) planner.widgetDay(now, zone) else null
                        val terms = if (weeks.isNotEmpty()) planner.observe(PlannerQuery.Terms).first() as Terms else null
                        val weekSnapshots = mutableMapOf<LocalDate?, WidgetSnapshot>()
                        for (id in days + weeks) {
                            ensureActive()
                            val options = manager.getAppWidgetOptions(id)
                            val snapshot = if (id in days) requireNotNull(day) else {
                                val picked = options.getLong(PICKED_WEEK, FOLLOW).takeUnless { it == FOLLOW }?.let(LocalDate::ofEpochDay)
                                weekSnapshots.getOrPut(picked) { planner.widgetWeek(requireNotNull(terms), picked, now, zone) }
                            }
                            val views = widgetViews(context, id, options, snapshot, now.toEpochMilli())
                            // Cancellation of a superseded generation prevents this handoff, including after blocking I/O.
                            withContext(Dispatchers.Main.immediate) {
                                manager.updateAppWidget(id, views)
                                expiry = minOf(expiry, snapshot.nextRefresh)
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                showError(context, manager, days, weeks)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("WorkspaceWidgets", "Widget update failed: ${error.javaClass.simpleName}")
                showError(context, manager, days, weeks)
            }
            boundary(app, expiry)
        }.also { job -> job.invokeOnCompletion { pending.finish() } }
    }

    private fun showError(context: Context, manager: AppWidgetManager, days: IntArray, weeks: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_error)
        views.setTextViewText(R.id.widget_message, context.getString(R.string.widget_retry))
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getBroadcast(context, 0,
            Intent(context, WidgetRefreshReceiver::class.java).setAction(REFRESH), PendingIntent.FLAG_IMMUTABLE))
        manager.updateAppWidget(days + weeks, views)
    }

    /** No periodic work or permission prompt. With exact alarms unavailable the host can update late in idle. */
    private fun boundary(context: Context, millis: Long?) {
        val alarms = checkNotNull(context.getSystemService(AlarmManager::class.java))
        val intent = Intent(context, WidgetRefreshReceiver::class.java).setAction(REFRESH)
        val pending = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or
            if (millis == null) PendingIntent.FLAG_NO_CREATE else 0) ?: return
        if (millis == null) {
            alarms.cancel(pending)
            pending.cancel()
        } else if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC, millis, pending)
        } else {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC, millis, pending) }
            catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC, millis, pending) }
        }
    }
}
