package io.github.nakanomk.stacklife.reminders

import androidx.room.withTransaction
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.storage.*
import java.time.LocalDate

/** v9 used length-prefixed UTF-16 fields (not a colon split) for course identity. */
internal fun legacyOccurrence(text: String): OccurrenceKey = when {
    text.startsWith("task:") -> OccurrenceKey.Schedule(text.substring(5).also { require(it.isNotEmpty()) })
    text.startsWith("routine:") -> {
        val fields = text.split(':', limit = 3)
        OccurrenceKey.Schedule(RoutineKind.valueOf(fields[1]).scheduleId, LocalDate.parse(fields[2]).toEpochDay())
    }
    text.startsWith("course:") -> {
        var pos = 7
        fun number(): Int {
            val end = text.indexOf(':', pos)
            require(end > pos)
            return text.substring(pos, end).toInt().also { pos = end + 1 }
        }
        fun field(): String {
            val length = number()
            require(length in 0..(text.length - pos))
            return text.substring(pos, pos + length).also { pos += length }
        }
        val week = number(); val day = number(); val first = number(); val last = number()
        val course = CourseOccurrenceId(field(), field(), week, day, first, last, field(), field())
        require(pos == text.length)
        OccurrenceKey.Course(course)
    }
    else -> throw IllegalArgumentException("Unknown legacy reminder key")
}

/** Nothing is removed from preferences. A malformed entry or failed write rolls the entire bridge back. */
internal suspend fun PlannerDatabase.importReceipts(legacy: () -> Map<String, *>, activeStarts: () -> Set<Long>) = withTransaction {
    val dao = alarmDao()
    if (dao.imported()) return@withTransaction false
    val ledger = legacy()
    if (ledger.isNotEmpty()) {
        val active = activeStarts()
        for ((time, values) in ledger) {
            val start = time.toLong()
            val trigger = initialReminder.time.trigger(start)
            val status = if (start in active) DeliveryStatus.DELIVERED else DeliveryStatus.DISMISSED
            for (value in values as Set<*>) {
                dao.acknowledge(ReminderReceiptRow(legacyOccurrence(value as String).storageKey(), initialReminder.id, trigger, status))
            }
        }
    }
    // The settings singleton legitimately does not exist until the first write on a fresh database.
    if (plannerDao().settings() == null) plannerDao().saveSettings(SettingsRow())
    check(dao.finishImport() == 1)
    true
}
