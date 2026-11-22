package io.github.nakanomk.stacklife.reminders

import io.github.nakanomk.stacklife.planner.*
import kotlinx.serialization.Serializable

internal data class ReminderOccurrence(val occurrence: AgendaOccurrence, val rule: ReminderRule, val triggerMillis: Long) {
    fun stamp() = AlarmStamp(occurrence.key, rule.id, triggerMillis, occurrence.version, rule.time)
}

/** The installed alarm remembers exactly which occurrence versions it can deliver. */
@Serializable
internal data class AlarmStamp(val key: OccurrenceKey, val ruleId: String, val triggerMillis: Long, val version: Long, val time: ReminderWhen)

internal val reminderOrder = Comparator<ReminderOccurrence> { a, b ->
    val event = agendaOrder.compare(a.occurrence, b.occurrence)
    if (event != 0) event else a.rule.id.compareTo(b.rule.id)
}

internal fun ReminderWhen.triggerOrNull(start: Long): Long? =
    if (this is ReminderWhen.BeforeStart && start < Long.MIN_VALUE + leadMillis) null else trigger(start)

internal fun startAtOrAfter(from: Long, lead: Long): Long? =
    if (from > Long.MAX_VALUE - lead) null else from + lead
