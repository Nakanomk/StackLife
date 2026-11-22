package io.github.nakanomk.stacklife.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.text.format.DateFormat
import io.github.nakanomk.stacklife.MainActivity
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.i18n.AppLanguage
import java.util.Date

internal const val REMINDER_CHANNEL = "agenda_reminders"
private const val NOTIFICATION_ID = 1
private const val TAG_PREFIX = "agenda:"

internal class ReminderNotices(private val app: Context) {
    private val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
    private val receipts = app.getSharedPreferences("agenda_reminder_receipts", Context.MODE_PRIVATE)

    private fun localized(): Context = if (app.resources.configuration.locales[0] == AppLanguage.locale) app
        else app.createConfigurationContext(Configuration().apply { setLocale(AppLanguage.locale) })

    fun createChannel() {
        manager.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL,
            localized().getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun allowed() = (Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED) && manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(REMINDER_CHANNEL)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true

    fun reconcile(due: List<List<ReminderOccurrence>>) {
        val now = System.currentTimeMillis()
        val active = manager.activeNotifications.filter { it.id == NOTIFICATION_ID && it.tag?.startsWith(TAG_PREFIX) == true }
            .associateByTo(mutableMapOf()) { it.tag }
        val context = localized()
        val formatter = DateFormat.getTimeFormat(context)
        val editor = receipts.edit()
        var changed = false
        for (time in receipts.all.keys) {
            // Keep recent acknowledgements across a small backwards clock correction, but not an agenda history.
            if (time.toLong() !in (now - 86_400_000L)..(now + 86_400_000L)) {
                editor.remove(time)
                changed = true
            }
        }
        for (items in due) {
            val start = items.first().startMillis
            val remaining = start - System.currentTimeMillis()
            if (remaining <= 0) continue
            val key = start.toString()
            val tag = TAG_PREFIX + key
            val old = active.remove(tag)?.notification
            val sent = receipts.getStringSet(key, emptySet()).orEmpty()
            val hasNewItem = items.any { it.key !in sent }
            if (old == null && !hasNewItem) continue // Dismissal survives restarts and duplicate broadcasts.
            val title = if (items.size == 1) items.first().title else context.resources.getQuantityString(
                R.plurals.reminder_count, items.size, items.size)
            val at = formatter.format(Date(start))
            val text = if (items.size == 1) context.getString(R.string.reminder_starts_at, at)
                else context.getString(R.string.reminder_group_at, at, items.joinToString(", ", limit = 4) { it.title })
            if (old == null || old.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() != title ||
                old.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() != text || hasNewItem) {
                if (!allowed()) break
                val open = PendingIntent.getActivity(app, 0, Intent.makeMainActivity(ComponentName(app, MainActivity::class.java)),
                    PendingIntent.FLAG_IMMUTABLE)
                val note = Notification.Builder(app, REMINDER_CHANNEL).setSmallIcon(R.drawable.ic_schedule)
                    .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_SECRET)
                    .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true)
                    .setOnlyAlertOnce(true).setTimeoutAfter(remaining).setShowWhen(false).build()
                try {
                    manager.notify(tag, NOTIFICATION_ID, note)
                } catch (e: SecurityException) {
                    if (allowed()) throw e
                    break
                }
            }
            if (hasNewItem) {
                editor.putStringSet(key, items.mapTo(sent.toMutableSet()) { it.key })
                changed = true
            }
        }
        for (tag in active.keys) manager.cancel(tag, NOTIFICATION_ID)
        // Complete the acknowledgement on IO before returning the broadcast to Android.
        if (changed) check(editor.commit()) { "Could not persist reminder acknowledgements" }
    }
}
