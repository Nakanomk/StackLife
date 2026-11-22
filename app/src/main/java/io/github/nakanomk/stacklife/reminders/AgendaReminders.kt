package io.github.nakanomk.stacklife.reminders

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.room.withTransaction
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.storage.*
import kotlinx.serialization.json.Json
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
    private val db: PlannerDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val planner: () -> PlannerApi,
) {
    private val alarms = checkNotNull(app.getSystemService(AlarmManager::class.java))
    private val notices = ReminderNotices(app)
    // Installed occurrence/rule stamps only. Delivery acknowledgements and dismissals live in Room.
    private val armed = app.getSharedPreferences("agenda_armed", Application.MODE_PRIVATE)
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

    fun refreshAsync(finished: () -> Unit = {}) = receiveAsync(Intent(Intent.ACTION_MAIN), finished)

    fun receiveAsync(intent: Intent?, finished: () -> Unit) {
        scope.launch { try { receive(intent) } finally { finished() } }
    }

    suspend fun refresh() = receive(null)

    internal suspend fun receive(intent: Intent?) = withContext(Dispatchers.IO + NonCancellable) {
        try {
            // Planner commit's post-write hook can call refresh(); never hold the scheduling mutex across a command.
            if (intent?.action == COMPLETE_ACTION) intent.data.alarmStamp()?.let { complete(it) }
            mutex.withLock {
                if (notices.importLegacy(db)) for (code in 0..1) {
                    // v9 used a class-only PendingIntent. Retire it after the bridge succeeds.
                    PendingIntent.getBroadcast(app, code, Intent(app, ReminderReceiver::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
                }
                val zone = ZoneId.systemDefault()
                if (intent?.action == DISMISS_ACTION) intent.data.alarmStamp()?.let {
                    db.withTransaction { db.alarmDao().dismiss(it.key.storageKey(), it.ruleId, it.triggerMillis) }
                }
                if (canPost()) {
                    val previous = armed.getString("group", null)
                    val now = clock()
                    // A write can beat the receiver by one main-loop turn. Do not erase its
                    // installed, still-valid deadline. Boot, foreground and permission recovery
                    // re-arm future triggers only; they do not replay past alarms.
                    val localAction = intent == null || intent.action == COMPLETE_ACTION || intent.action == DISMISS_ACTION
                    val mayDeliver = intent?.action == ALARM_ACTION || localAction && operation(false, false) != null
                    val due = if (mayDeliver && previous != null && armed.getLong("deadline", Long.MAX_VALUE) <= now) {
                        Json.decodeFromString<List<AlarmStamp>>(previous).filter { it.triggerMillis <= now }
                    } else emptyList()
                    notices.reconcile(db, zone, due)
                    val next = db.alarmFrontier(now, zone)
                    val deadline = next.minOfOrNull { it.triggerMillis }
                    val encoded = if (next.isEmpty()) null else Json.encodeToString(next)
                    if (previous != encoded) check(armed.edit().putString("group", encoded)
                        .putLong("deadline", deadline ?: Long.MAX_VALUE).commit())
                    replaceAlarm(deadline)
                } else {
                    replaceAlarm(null)
                    if (armed.contains("group")) check(armed.edit().clear().commit())
                }
                failed.value = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The business transaction has already committed. Only the delivery state reports this failure.
            failed.value = true
            Log.e("StackLifeReminders", "Could not reconcile reminders (${e.javaClass.simpleName})")
        }
    }

    private suspend fun complete(stamp: AlarmStamp) {
        suspend fun valid() = db.withTransaction {
            db.alarmDao().status(stamp.key.storageKey(), stamp.ruleId, stamp.triggerMillis) == DeliveryStatus.DELIVERED &&
                db.currentReminder(stamp, ZoneId.systemDefault(), requireVersion = true) != null
        }
        if (!valid()) return
        val api = planner()
        val preview = api.preview(listOf(PlannerCommand.SetOccurrenceCompletion(stamp.key, true, expectedVersion = stamp.version)))
        if (preview is PreviewResult.Ready) try {
            if (valid()) api.commit(preview.proposalId)
        } finally {
            api.discard(preview.proposalId)
        }
    }

    private fun operation(exact: Boolean, create: Boolean): PendingIntent? = PendingIntent.getBroadcast(
        app, if (exact) 1 else 0, Intent(app, ReminderReceiver::class.java).setAction(ALARM_ACTION),
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
