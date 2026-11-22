package io.github.nakanomk.stacklife.storage

import androidx.room.withTransaction
import io.github.nakanomk.stacklife.planner.DailyRoutine
import io.github.nakanomk.stacklife.planner.RoutineKind
import io.github.nakanomk.stacklife.planner.label
import io.github.nakanomk.stacklife.reminders.ReminderOccurrence
import java.time.Instant
import java.time.ZoneId

internal suspend fun PlannerDatabase.nextReminderGroup(fromInclusive: Long, zone: ZoneId): List<ReminderOccurrence> = withTransaction {
    val dao = agendaDao()
    val task = dao.nextTaskStart(fromInclusive)
    val course = dao.nextCourseStart(fromInclusive)
    var earliest = minOf(task ?: Long.MAX_VALUE, course ?: Long.MAX_VALUE)
    val found = ArrayList<ReminderOccurrence>()
    val settings = plannerDao().settings()
    if (settings?.wakeMinute != null || settings?.sleepMinute != null) {
        val routine = DailyRoutine(settings?.wakeMinute, settings?.sleepMinute)
        var date = Instant.ofEpochMilli(fromInclusive).atZone(zone).toLocalDate()
        while (date.atStartOfDay(zone).toInstant().toEpochMilli() <= earliest) {
            val done = dao.routineTicks(date.toEpochDay())
            for (kind in RoutineKind.entries) {
                if (kind in done) continue
                val event = routine.event(kind, date, zone, false) ?: continue
                if (event.startMillis !in fromInclusive..earliest) continue
                if (event.startMillis < earliest) { earliest = event.startMillis; found.clear() }
                found += ReminderOccurrence("routine:${kind.name}:$date", kind.label(), earliest)
            }
            date = date.plusDays(1)
        }
    }
    if (earliest == task) for (row in dao.tasksAt(earliest)) found += ReminderOccurrence("task:${row.id}", row.title, earliest)
    if (earliest == course) for (row in dao.coursesAt(earliest)) {
        val m = row.meeting
        val key = buildString {
            append("course:${m.week}:${m.day}:${m.firstPeriod}:${m.lastPeriod}:")
            fun field(text: String) { append(text.length).append(':').append(text) }
            field(row.termId); field(row.source); field(m.title); field(m.room)
        }
        found += ReminderOccurrence(key, m.title, earliest)
    }
    found
}
