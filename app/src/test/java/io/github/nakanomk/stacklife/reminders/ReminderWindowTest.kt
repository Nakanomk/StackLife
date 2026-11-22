package io.github.nakanomk.stacklife.reminders

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReminderWindowTest {
    private val now = 1_800_000_000_000L
    private fun item(key: String, offset: Long) = ReminderOccurrence(key, key, now + offset)

    @Test fun includesTiesAndNewShortNoticeItemsButNeverStartedItemsOrDistantDates() = runBlocking {
        val tooLate = item("already started", 0)
        val soon = item("task", 1)
        val course = item("course", REMINDER_LEAD_MILLIS)
        val routine = course.copy(key = "wake-up")
        val nextWeek = item("next week", 7 * 86_400_000L)
        val groups = listOf(listOf(tooLate), listOf(soon), listOf(course, routine), listOf(nextWeek))
        val cursors = mutableListOf<Long>()
        val window = reminderWindow(now) { cursor ->
            cursors += cursor
            groups.firstOrNull { it.first().startMillis >= cursor }.orEmpty()
        }
        assertEquals(listOf(listOf(soon), listOf(course, routine)), window.due)
        assertEquals(nextWeek.startMillis - REMINDER_LEAD_MILLIS, window.nextAlarmMillis)
        assertEquals(listOf(now + 1, soon.startMillis + 1, course.startMillis + 1), cursors)
    }

    @Test fun movingTheFirstItemLaterReplacesItsDeadlineAndRemovingLastItemDisarms() = runBlocking {
        var starts = listOf(item("A", 30 * 60_000), item("B", 50 * 60_000))
        suspend fun next() = reminderWindow(now) { cursor -> starts.filter { it.startMillis >= cursor }
            .minByOrNull { it.startMillis }?.let { listOf(it) }.orEmpty() }.nextAlarmMillis
        assertEquals(now + 15 * 60_000, next())
        starts = listOf(starts[0].copy(startMillis = now + 100 * 60_000), starts[1])
        assertEquals(now + 35 * 60_000, next())
        starts = emptyList()
        assertNull(next())
    }
}
