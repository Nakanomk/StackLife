package io.github.nakanomk.stacklife.reminders

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

internal class AgendaReminders(
    private val app: Application,
    private val nextGroup: suspend (Long, ZoneId) -> List<ReminderOccurrence>,
) {
    private val alarms = checkNotNull(app.getSystemService(AlarmManager::class.java))
    private val notices = ReminderNotices(app)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val failed = MutableStateFlow(false)
    val schedulingFailed = failed.asStateFlow()

    fun initialize() {
        notices.createChannel()
        // A force-stop removes alarms. Foregrounding also covers settings changes on older Android.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = refreshAsync()
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun canPost() = notices.allowed()
    fun canBeExact() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun refreshAsync(finished: () -> Unit = {}) {
        scope.launch { try { refresh() } finally { finished() } }
    }

    suspend fun refresh() = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            try {
                if (canPost()) {
                    val zone = ZoneId.systemDefault()
                    val window = reminderWindow(System.currentTimeMillis()) { from -> nextGroup(from, zone) }
                    // Install the successor before updating the shade. Never trust an old title or time.
                    replaceAlarm(window.nextAlarmMillis)
                    notices.reconcile(window.due)
                } else replaceAlarm(null)
                failed.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The agenda transaction already committed. Report a delivery problem, not a failed edit.
                failed.value = true
                Log.e("StackLifeReminders", "Could not reconcile reminders (${e.javaClass.simpleName})")
            }
        }
    }

    private fun operation(exact: Boolean, create: Boolean): PendingIntent? = PendingIntent.getBroadcast(
        app, if (exact) 1 else 0, Intent(app, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or (if (create) 0 else PendingIntent.FLAG_NO_CREATE),
    )

    private fun cancel(exact: Boolean) {
        operation(exact, false)?.let { alarms.cancel(it); it.cancel() }
    }

    private fun replaceAlarm(trigger: Long?) {
        if (trigger == null) {
            cancel(true)
            cancel(false)
            return
        }
        // Revocation stops the app and removes pending *exact* alarms without a broadcast.
        // Keep a separately identified, inexact wake-up for the same deadline in that case.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, checkNotNull(operation(false, true)))
        if (canBeExact()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, checkNotNull(operation(true, true)))
                return
            } catch (_: SecurityException) {
                // Access was withdrawn after the check. The inexact alarm is already installed.
            }
        }
        cancel(true)
    }
}
