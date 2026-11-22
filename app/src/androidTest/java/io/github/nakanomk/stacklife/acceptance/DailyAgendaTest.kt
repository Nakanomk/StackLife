package io.github.nakanomk.stacklife.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import io.github.nakanomk.stacklife.storage.nextReminderGroup
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DailyAgendaTest {
    @get:Rule val store = WorkspaceFixture()
    private val monday = LocalDate.of(2026, 9, 7)
    private suspend fun day(date: LocalDate = monday, zone: ZoneId = campusZone) = store.api.observe(PlannerQuery.Day(date, zone)).first() as DayPlan

    @Test fun newPastPointDisclosesItsExpiredDefaultButDoesNotScheduleDelivery() = runBlocking {
        val point = System.currentTimeMillis() - 60_000L
        val proposal = store.proposal(TaskAdd(TaskInput("Past", ref = "p")),
            SetSchedule("@p", SchedulePattern.Once(ExactInterval(point, point))))
        val notice = proposal.changes.mapNotNull { it.after as? ReminderSnapshot }.single()
        assertEquals(setOf(initialReminder.id), notice.expiredRuleIds)
        assertTrue(store.api.commit(proposal.proposalId) is CommitResult.Applied)
        assertTrue(store.db.nextReminderGroup(point + 1, ZoneId.systemDefault()).isEmpty())
    }

    @Test fun undoRestoresOriginalQueuePositionOrFrameButNeverRevivesDeletedHistory() = runBlocking {
        val start = monday.atTime(9, 0).atZone(campusZone).toOffsetDateTime()
        store.apply(TaskAdd(TaskInput("A")), TaskAdd(TaskInput("B", ref = "b")), exact("@b", start, 30), TaskAdd(TaskInput("C")))
        val original = store.state().queue
        val b = original[1].id
        suspend fun tick(done: Boolean) = store.apply(SetOccurrenceCompletion(OccurrenceKey.Schedule(b),done,day().revision))
        tick(true); assertEquals(Owner.DONE,day().occurrences.single().owner)
        tick(false); assertEquals(original, store.state().queue)
        store.apply(FramePush("暂停", listOf(b))); val frame = store.state().frames.single()
        assertTrue(store.api.preview(listOf(DeleteFrameHistory(listOf(frame.id)))) is PreviewResult.Rejected)
        tick(true); val staleDelete = store.proposal(DeleteFrameHistory(listOf(frame.id)))
        tick(false); assertEquals(frame, store.state().frames.single())
        assertEquals(CommitFailure.STALE_PREVIEW, (store.api.commit(staleDelete.proposalId) as CommitResult.Rejected).code)
        tick(true); store.apply(DeleteFrameHistory(listOf(frame.id)))
        store.reopen(); tick(false)
        assertTrue(store.state().frames.isEmpty()); assertEquals(listOf("A", "C", "B"), store.state().queue.map { it.title })
        assertEquals(start.toInstant().toEpochMilli(),day().occurrences.single().startMillis); assertEquals(b,day().occurrences.single().taskId)
    }

    private fun role(kind: RoutineKind, minute: Int) = SetSchedule(kind.scheduleId,
        SchedulePattern.Daily(listOf(DailySegment(LocalDate.of(1,1,1).toEpochDay(),minute,null,0))))
    @Test fun courseAndWakeAtSameTimeCheckOffIndependentlyAndTheNextWeekRemainsOpen() = runBlocking {
        val term = TermSpec("Autumn", monday, 2, BellSchedule.SUMMER)
        val meetings = listOf(1, 2).map { term.meeting(it, 1, "Seminar", "A", 1, 2) }
        val start = meetings.first().startMillis
        val local = Instant.ofEpochMilli(start).atZone(campusZone)
        val draft = store.api.stageCourseImport(term, meetings)
        store.apply(CourseImport(draft),role(RoutineKind.WAKE,local.hour * 60 + local.minute))
        val trigger = start - 900_000L
        assertEquals(setOf("Seminar",RoutineKind.WAKE.label()),store.db.nextReminderGroup(trigger,campusZone).map { it.occurrence.title }.toSet())
        val first = OccurrenceKey.Course(CourseOccurrenceId.of(term.id,HUST_SOURCE,meetings.first()))
        store.apply(SetOccurrenceCompletion(first,true,day().revision))
        assertEquals(RoutineKind.WAKE.label(),store.db.nextReminderGroup(trigger,campusZone).single().occurrence.title)
        val wake = OccurrenceKey.Schedule(RoutineKind.WAKE.scheduleId,monday.toEpochDay())
        store.apply(SetOccurrenceCompletion(wake,true,day().revision),CourseImport(draft))
        store.reopen(); assertEquals(setOf(first,wake),day().occurrences.filter { it.completed }.map { it.key }.toSet())
        val tomorrow = day(monday.plusDays(1)); assertFalse(tomorrow.occurrences.single().completed)
        assertEquals(tomorrow.occurrences.single().key,store.db.nextReminderGroup(trigger,campusZone).single().occurrence.key)
        assertTrue(day(monday.plusWeeks(1)).occurrences.none { it.completed })
        store.apply(TermRemove(term.id,HUST_SOURCE))
        assertTrue(day().occurrences.none { it.course != null })
        store.apply(CourseImport(store.api.stageCourseImport(term,meetings)))
        assertFalse(day().occurrences.single { it.key == first }.completed)
        store.apply(SetSchedule(RoutineKind.WAKE.scheduleId,null))
        assertTrue(day().occurrences.none { it.role != null })
    }

    @Test fun wallClockRoutinesFollowTheDayAcrossGapsFoldsAndSkippedDates() = runBlocking {
        val ny = ZoneId.of("America/New_York"); val spring = LocalDate.of(2026, 3, 8); val fold = LocalDate.of(2026, 11, 1)
        store.apply(role(RoutineKind.WAKE,150),role(RoutineKind.SLEEP,90))
        assertEquals(spring.atTime(3,30).atZone(ny).toInstant().toEpochMilli(),day(spring,ny).occurrences.single { it.role == RoutineKind.WAKE }.startMillis)
        assertEquals(fold.atTime(1,30).atOffset(ZoneOffset.ofHours(-4)).toInstant().toEpochMilli(),day(fold,ny).occurrences.single { it.role == RoutineKind.SLEEP }.startMillis)
        assertTrue(day(LocalDate.of(2011,12,30),ZoneId.of("Pacific/Apia")).occurrences.isEmpty())
        val query = day(spring, ny).day
        store.apply(TaskAdd(TaskInput("At bedtime",ref = "task")),exact("@task",spring.atTime(1,30).atZone(ny).toOffsetDateTime(),5))
        assertEquals(1,day(spring,ny).occurrences.count { it.taskId != null }); assertEquals(2,day(spring,ny).occurrences.count { it.role != null })
        assertEquals(2, store.db.nextReminderGroup(query.startMillis, ny).size)
    }

    @Test fun movedPointRetainsOriginalKeyAcrossFutureAndWholeSeriesEditsAndPrivateFrameSnapshot() = runBlocking {
        val start = monday.toEpochDay()
        val first = DailySegment(start,23*60+59,campusZone.id,0)
        val initial = SchedulePattern.Daily(listOf(first))
        store.apply(TaskAdd(TaskInput("Daily","saved note",0,"d")),SetSchedule("@d",initial))
        val id = store.state().queue.single().id
        fun key(n: Int) = OccurrenceKey.Schedule(id,start+n)
        val moved = monday.plusDays(2).atStartOfDay(campusZone).toInstant().toEpochMilli()
        store.apply(SetOccurrenceOverride(key(1),EditScope.THIS,ExactInterval(moved,moved)),SetOccurrenceCompletion(key(1),true),
            FramePush("saved cycle",listOf(id)))
        val frame = store.state().frames.single()
        store.apply(SetOccurrenceOverride(key(2),EditScope.FOLLOWING,segment = first.copy(fromDay = start+2,minute = 8*60,minutes = 60)),
            TaskEdit(id,notes = "new note"))
        assertTrue(day(monday.plusDays(1)).occurrences.isEmpty())
        val same = day(monday.plusDays(2)).occurrences
        assertEquals(listOf(key(1),key(2)),same.map { it.key }); assertTrue(same.first().point && same.first().completed)
        assertEquals(60*60_000L,same.last().endMillis-same.last().startMillis); assertFalse(same.last().completed)
        val changed = store.proposal(SetOccurrenceOverride(key(2),EditScope.ALL,segment = first.copy(minute = 0)))
        assertEquals(1,(changed.changes.single().after as SeriesSnapshot).explicitExceptions)
        assertTrue(store.api.commit(changed.proposalId) is CommitResult.Applied); store.reopen()
        assertEquals(listOf(key(1),key(2)),day(monday.plusDays(2)).occurrences.map { it.key })
        assertEquals(frame.items.single().schedule,store.state().frames.single().items.single().schedule)
        assertEquals("saved note",store.state().frames.single().items.single().notes)
        assertEquals("new note",store.state().frames.single().items.single().current!!.notes)
        assertEquals(key(80_000),day(monday.plusDays(80_000)).occurrences.single().key)
    }
}
