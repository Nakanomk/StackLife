package io.github.nakanomk.stacklife.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.room.withTransaction
import io.github.nakanomk.stacklife.MainActivity
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.i18n.AppLanguage
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.storage.*
import java.time.ZoneId
import java.util.Date

internal const val REMINDER_CHANNEL = "agenda_reminders"
private const val NOTIFICATION_ID = 1
private const val TAG_PREFIX = "agenda:"
private const val STAMP = "agendaStamp"

internal class ReminderNotices(private val app: Context) {
    private val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
    private val legacy = app.getSharedPreferences("agenda_reminder_receipts", Context.MODE_PRIVATE)

    private fun localized(): Context = if (app.resources.configuration.locales[0] == AppLanguage.locale) app
        else app.createConfigurationContext(Configuration().apply { setLocale(AppLanguage.locale) })

    fun createChannel() {
        manager.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL,
            localized().getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun allowed() = (Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED) && manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(REMINDER_CHANNEL)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true

    private fun active() = manager.activeNotifications.filter { it.id == NOTIFICATION_ID && it.tag?.startsWith(TAG_PREFIX) == true }

    suspend fun importLegacy(db: PlannerDatabase) = db.importReceipts({ legacy.all }) {
        active().mapNotNullTo(mutableSetOf()) { it.tag?.removePrefix(TAG_PREFIX)?.toLongOrNull() }
    }

    private fun operation(action: String, stamp: AlarmStamp): PendingIntent = PendingIntent.getBroadcast(
        app, 0, Intent(app, ReminderReceiver::class.java).setAction(action).setData(stamp.actionUri()), PendingIntent.FLAG_IMMUTABLE)

    private fun post(item: ReminderOccurrence, old: Notification? = null, migrateSilently: Boolean = false) {
        val stamp = item.stamp()
        val row = item.occurrence
        val context = localized()
        val at = Date(row.startMillis)
        val text = context.getString(R.string.reminder_starts_at,
            "${DateFormat.getMediumDateFormat(context).format(at)} ${DateFormat.getTimeFormat(context).format(at)}")
        val uri = stamp.actionUri()
        if (old != null && old.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == row.title &&
            old.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == text && old.extras.getString(STAMP) == uri.toString()) return
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(ReminderTarget(row.key, row.startMillis).uri()).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(app, REMINDER_CHANNEL).setSmallIcon(R.drawable.ic_schedule)
            .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_SECRET)
            .setContentTitle(row.title).setContentText(text).setContentIntent(open).setAutoCancel(true)
            .setDeleteIntent(operation(DISMISS_ACTION, stamp)).setOnlyAlertOnce(true).setShowWhen(false)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.reminder_complete), operation(COMPLETE_ACTION, stamp)).build())
        note.extras.putString(STAMP, uri.toString())
        if (migrateSilently) note.setGroup("migrated_reminders").setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        val now = System.currentTimeMillis()
        if (!row.point && row.endMillis > now) note.setTimeoutAfter(row.endMillis - now)
        // Version belongs to the action, not the receipt. Editing the title updates the same shade entry.
        val tag = TAG_PREFIX + Uri.Builder().appendPath(row.stableKey).appendPath(stamp.ruleId).appendPath(stamp.triggerMillis.toString()).build()
        manager.notify(tag, NOTIFICATION_ID, note.build())
    }

    suspend fun reconcile(db: PlannerDatabase, zone: ZoneId, due: List<AlarmStamp>) = db.withTransaction {
        val alarms = db.alarmDao()
        for (entry in active()) {
            val stamp = entry.notification.extras.getString(STAMP)?.let(Uri::parse).alarmStamp()
            if (stamp == null) {
                // Replace a still-visible v9 group with actionable entries without alerting again.
                val start = entry.tag?.removePrefix(TAG_PREFIX)?.toLongOrNull()
                if (start != null) for (oldKey in legacy.getStringSet(start.toString(), emptySet()).orEmpty()) {
                    val key = legacyOccurrence(oldKey)
                    val legacyStamp = AlarmStamp(key, initialReminder.id, initialReminder.time.trigger(start), 0, initialReminder.time)
                    if (alarms.status(key.storageKey(), legacyStamp.ruleId, legacyStamp.triggerMillis) == DeliveryStatus.DELIVERED) {
                        db.currentReminder(legacyStamp, zone, requireVersion = false)?.let { post(it, migrateSilently = true) }
                    }
                }
                manager.cancel(entry.tag, entry.id)
                continue
            }
            // Refresh a delivered notice by receipt identity; post() binds its actions to the new version.
            val current = stamp.takeIf { alarms.status(it.key.storageKey(), it.ruleId, it.triggerMillis) == DeliveryStatus.DELIVERED }
                ?.let { db.currentReminder(it, zone, requireVersion = false) }
            if (current == null) {
                alarms.dismiss(stamp.key.storageKey(), stamp.ruleId, stamp.triggerMillis)
                manager.cancel(entry.tag, entry.id)
            } else post(current, entry.notification)
        }
        if (due.isEmpty()) return@withTransaction
        val ready = ArrayList<ReminderOccurrence>(due.size)
        for (stamp in due) {
            val key = stamp.key.storageKey()
            if (alarms.status(key, stamp.ruleId, stamp.triggerMillis) != null) continue
            db.currentReminder(stamp, zone, requireVersion = true)?.let(ready::add)
        }
        ready.sortWith(compareBy<ReminderOccurrence> { it.triggerMillis }.then(reminderOrder))
        for (current in ready) {
            post(current)
            alarms.acknowledge(ReminderReceiptRow(current.occurrence.stableKey, current.rule.id, current.triggerMillis, DeliveryStatus.DELIVERED))
        }
    }
}
