package io.github.nakanomk.stacklife.reminders

internal data class ReminderOccurrence(val key: String, val title: String, val startMillis: Long)

internal const val REMINDER_LEAD_MILLIS = 15 * 60_000L
internal data class ReminderWindow(val due: List<List<ReminderOccurrence>>, val nextAlarmMillis: Long?)

/** An item newly scheduled inside its lead window is still useful. An item that started is not. */
internal suspend fun reminderWindow(now: Long, nextGroup: suspend (Long) -> List<ReminderOccurrence>): ReminderWindow {
    val due = mutableListOf<List<ReminderOccurrence>>()
    var cursor = now + 1
    while (true) {
        val group = nextGroup(cursor)
        val start = group.firstOrNull()?.startMillis ?: return ReminderWindow(due, null)
        check(start >= cursor)
        val trigger = start - REMINDER_LEAD_MILLIS
        if (trigger > now) return ReminderWindow(due, trigger)
        due += group
        cursor = start + 1
    }
}
