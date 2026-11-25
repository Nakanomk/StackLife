package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.assistant.ChatEvent
import io.github.nakanomk.stacklife.courses.TermSpec
import io.github.nakanomk.stacklife.courses.problem
import io.github.nakanomk.stacklife.graphs.GoalDag
import io.github.nakanomk.stacklife.planner.*
import java.time.LocalDate
import java.time.ZoneId

/** A stored event cannot execute, but every side must still be safe to open in ChangePreview. Do
 * not resolve historical IDs against live tables: removal or rescheduling does not edit history. */
internal fun validateBackupEvent(event:ChatEvent) {
    fun date(day:Long) { require(LocalDate.ofEpochDay(day).year in 1..9999) }
    fun course(c:CourseOccurrenceId) { require(c.termId.isNotBlank() && c.source.isNotBlank() && c.week in 1..53 && c.day in 1..7 && c.firstPeriod>0 && c.lastPeriod>=c.firstPeriod && c.title.isNotBlank()) }
    fun key(k:OccurrenceKey) { when(k) {
        is OccurrenceKey.Course -> course(k.occurrence); is OccurrenceKey.Schedule -> { require(k.scheduleId.isNotBlank()); k.originalDay?.let(::date) }
    } }
    fun nodes(ns:List<GraphNode>) {
        val ids=HashSet<String>(); for(n in ns) { require(n.id.isNotBlank() && ids.add(n.id) && n.title.isNotBlank() && n.minutes>=0 && n.x.isFinite() && n.y.isFinite()); (n.link as? GraphLink.Occurrence)?.let { date(it.originalDate) } }
    }
    fun overlap(o:Overlap) { require(o.endMillis>=o.startMillis); o.course?.let(::course) }
    fun snapshot(s:ChangeSnapshot?) { when(s) {
        null,is AppearanceSnapshot,is RestorePolicySnapshot,is KeepRestoredFramesSnapshot -> Unit
        is TaskSnapshot -> require(s.title.isNotBlank() && s.minutes>=0)
        is QueueSnapshot -> require(s.items.all { it.id.isNotBlank() && it.title.isNotBlank() } && s.items.map { it.id }.distinct().size==s.items.size)
        is FrameSnapshot -> for(i in s.items) { require(i.originalPosition>=0 && i.minutes>=0 && i.title.isNotBlank()); i.occurrenceDay?.let(::date); require((i.startMillis==null)==(i.endMillis==null)); if(i.startMillis!=null) require(requireNotNull(i.endMillis)>=i.startMillis) }
        is GraphSnapshot -> { nodes(s.nodes); GoalDag(s.nodes.associateBy { it.id },s.edges) }
        is GoalGenerationSnapshot -> { require(s.order.isNotEmpty()); nodes(s.order) }
        is CountdownSnapshot -> require(s.value.problem()==null)
        is ScheduleSnapshot -> { ZoneId.of(s.zone); require(s.startMillis<=s.endMillis); s.overlaps.forEach(::overlap) }
        is SeriesSnapshot -> require(s.explicitExceptions>=0)
        is OccurrenceSnapshot -> { ZoneId.of(s.zone); key(s.key) }
        is ReminderSnapshot -> { val who=s.subject; if(who is ReminderSubject.Occurrence) key(who.key) }
        is RoutineTimeSnapshot -> require(s.minute==null || s.minute in 0..1439)
        is RoutineCompletionSnapshot -> date(s.epochDay)
        is CourseCompletionSnapshot -> course(s.occurrence)
        is CourseSetSnapshot -> {
            ZoneId.of(s.zone); val term=TermSpec(s.termId,LocalDate.ofEpochDay(s.firstMonday),s.weeks,s.initial,s.switchDay?.let(LocalDate::ofEpochDay)); require(term.problem()==null)
            for(m in s.meetings) { course(CourseOccurrenceId.of(s.termId,s.source,m)); require(m.week<=s.weeks && m.endMillis>m.startMillis) }
            for(c in s.conflicts) { course(c.occurrence); require(c.endMillis>=c.startMillis); overlap(c.other) }
            for(pair in s.replacements) { course(pair.old); course(pair.new) }
            require(s.conflictCount>=s.conflicts.size && s.cancelledReminders>=0)
        }
    } }
    for(change in event.changes) { require(change.entityId.isNotBlank() && (change.before!=null || change.after!=null));
        if(change.before is AppearanceSnapshot || change.after is AppearanceSnapshot) require(change.before is AppearanceSnapshot && change.after is AppearanceSnapshot)
        snapshot(change.before); snapshot(change.after)
    }
}
