package io.github.nakanomk.stacklife.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import io.github.nakanomk.stacklife.StackLifeApplication

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminders = (context.applicationContext as StackLifeApplication).reminders
        // AlarmManager holds its wake lock only inside onReceive; Room and notification I/O run off main.
        val lock = checkNotNull(context.getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StackLife:agenda-reminder").apply {
                setReferenceCounted(false)
                acquire(9_000L)
            }
        val result = goAsync()
        reminders.refreshAsync {
            try { if (lock.isHeld) lock.release() } finally { result.finish() }
        }
    }
}
