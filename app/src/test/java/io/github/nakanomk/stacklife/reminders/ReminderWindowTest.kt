package io.github.nakanomk.stacklife.reminders

import io.github.nakanomk.stacklife.planner.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReminderWindowTest {
    @Test fun leadWindowStartsAtTriggerPlusLeadWithoutWrappingEpoch() {
        val start = 1_800_000_000_000L
        for (minutes in listOf(0, 5, 90)) {
            val lead = minutes * 60_000L
            assertEquals(start - lead, ReminderWhen.BeforeStart(lead).triggerOrNull(start))
            assertEquals(start, startAtOrAfter(start - lead, lead))
        }
        assertNull(startAtOrAfter(Long.MAX_VALUE, 1))
        assertNull(ReminderWhen.BeforeStart(1).triggerOrNull(Long.MIN_VALUE))
        assertEquals(Long.MIN_VALUE, ReminderWhen.AtInstant(Long.MIN_VALUE).triggerOrNull(Long.MAX_VALUE))
    }

    @Test fun legacyDecoderPreservesDelimitersEmojiFullCourseAndOriginalDailyDate() {
        val id = CourseOccurrenceId("term:1", "😀:hub", 12, 3, 2, 4, "A:B😀", "")
        fun field(s: String) = "${s.length}:$s"
        val legacy = "course:12:3:2:4:" + listOf(id.termId, id.source, id.title, id.room).joinToString("", transform = ::field)
        assertEquals(OccurrenceKey.Course(id), legacyOccurrence(legacy))
        assertEquals(OccurrenceKey.Schedule("a:b"), legacyOccurrence("task:a:b"))
        assertEquals(OccurrenceKey.Schedule(RoutineKind.SLEEP.scheduleId, LocalDate.of(2026, 11, 4).toEpochDay()),
            legacyOccurrence("routine:SLEEP:2026-11-04"))
        assertThrows(IllegalArgumentException::class.java) { legacyOccurrence("$legacy:extra") }
        assertThrows(IllegalArgumentException::class.java) { legacyOccurrence("course:1:2:3:4:999:bad") }
    }

    @Test fun tiedTriggersUseExactlyTheAgendaOrderingThenStableRuleId() {
        fun item(key: String, start: Long, end: Long, rule: String) = ReminderOccurrence(
            AgendaOccurrence(OccurrenceKey.Schedule(key), "Same title", "", start, end, false, 1), ReminderRule(rule, ReminderWhen.AtInstant(0)), 0)
        val sorted = listOf(item("b", 7, 9, "b"), item("b", 7, 9, "a"), item("b", 7, 7, "c"),
            item("a", 7, 9, "d"), item("z", 3, 8, "e")).sortedWith(reminderOrder)
        assertEquals(listOf("e", "c", "d", "a", "b"), sorted.map { it.rule.id })
    }
}
