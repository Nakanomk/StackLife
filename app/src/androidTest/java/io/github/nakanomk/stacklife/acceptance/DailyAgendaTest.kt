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

    @Test fun undoRestoresOriginalQueuePositionOrFrameButNeverRevivesDeletedHistory() = runBlocking {
        val start = monday.atTime(9, 0).atZone(campusZone).toOffsetDateTime()
        store.apply(TaskAdd(TaskInput("A")), TaskAdd(TaskInput("B", ref = "b")), SlotSet("@b", start, 30), TaskAdd(TaskInput("C")))
        val original = store.state().queue
        val b = original[1].id
        suspend fun tick(done: Boolean) = store.apply(SetScheduledCompletion(b, done, day().revision))
        tick(true); assertEquals(ScheduledState.DONE, day().blocks.single().state)
        tick(false); assertEquals(original, store.state().queue)
        store.apply(FramePush("暂停", listOf(b))); val frame = store.state().frames.single()
        assertTrue(store.api.preview(listOf(DeleteFrameHistory(listOf(frame.id)))) is PreviewResult.Rejected)
        tick(true); val staleDelete = store.proposal(DeleteFrameHistory(listOf(frame.id)))
        tick(false); assertEquals(frame, store.state().frames.single())
        assertEquals(CommitFailure.STALE_PREVIEW, (store.api.commit(staleDelete.proposalId) as CommitResult.Rejected).code)
        tick(true); store.apply(DeleteFrameHistory(listOf(frame.id)))
        store.reopen(); tick(false)
        assertTrue(store.state().frames.isEmpty()); assertEquals(listOf("A", "C", "B"), store.state().queue.map { it.title })
        assertEquals(start.toInstant().toEpochMilli(), day().blocks.single().startMillis); assertEquals(b, day().blocks.single().taskId)
    }

    @Test fun courseAndWakeAtSameTimeCheckOffIndependentlyAndTheNextWeekRemainsOpen() = runBlocking {
        val term = TermSpec("Autumn", monday, 2, BellSchedule.SUMMER)
        val meetings = listOf(1, 2).map { term.meeting(it, 1, "Seminar", "A", 1, 2) }
        val start = meetings.first().startMillis
        val local = Instant.ofEpochMilli(start).atZone(campusZone)
        val draft = store.api.stageCourseImport(term, meetings)
        store.apply(CourseImport(draft), SetRoutineTime(RoutineKind.WAKE, local.hour * 60 + local.minute))
        assertEquals(setOf("Seminar", RoutineKind.WAKE.label()), store.db.nextReminderGroup(start, campusZone).map { it.title }.toSet())
        val first = CourseOccurrenceId.of(term.id, HUST_SOURCE, meetings.first())
        store.apply(SetCourseCompletion(first, true, day().revision))
        assertEquals(RoutineKind.WAKE.label(), store.db.nextReminderGroup(start, campusZone).single().title)
        store.apply(SetRoutineCompletion(RoutineKind.WAKE, monday, true, day().revision), CourseImport(draft))
        store.reopen(); assertEquals(setOf(first), day().completedCourses); assertTrue(day().routines.single().completed)
        val tomorrow = day(monday.plusDays(1)); assertFalse(tomorrow.routines.single().completed)
        assertEquals(tomorrow.routines.single().startMillis, store.db.nextReminderGroup(start, campusZone).single().startMillis)
        assertTrue(day(monday.plusWeeks(1)).completedCourses.isEmpty())
        store.apply(TermRemove(term.id, HUST_SOURCE)); assertTrue(day().courses.isEmpty())
        store.apply(CourseImport(store.api.stageCourseImport(term, meetings))); assertTrue(day().completedCourses.isEmpty())
        store.apply(SetRoutineTime(RoutineKind.WAKE, null)); assertTrue(day().routines.isEmpty())
    }

    @Test fun wallClockRoutinesFollowTheDayAcrossGapsFoldsAndSkippedDates() = runBlocking {
        val ny = ZoneId.of("America/New_York"); val spring = LocalDate.of(2026, 3, 8); val fold = LocalDate.of(2026, 11, 1)
        store.apply(SetRoutineTime(RoutineKind.WAKE, 2 * 60 + 30), SetRoutineTime(RoutineKind.SLEEP, 90))
        assertEquals(spring.atTime(3,30).atZone(ny).toInstant().toEpochMilli(), day(spring, ny).routines.single { it.kind == RoutineKind.WAKE }.startMillis)
        assertEquals(fold.atTime(1,30).atOffset(ZoneOffset.ofHours(-4)).toInstant().toEpochMilli(), day(fold, ny).routines.single { it.kind == RoutineKind.SLEEP }.startMillis)
        assertTrue(day(LocalDate.of(2011,12,30), ZoneId.of("Pacific/Apia")).routines.isEmpty())
        val query = day(spring, ny).day
        store.apply(TaskAdd(TaskInput("At bedtime", ref = "task")), SlotSet("@task", spring.atTime(1,30).atZone(ny).toOffsetDateTime(), 5))
        assertEquals(1, day(spring,ny).blocks.size); assertEquals(2, day(spring,ny).routines.size)
        assertEquals(2, store.db.nextReminderGroup(query.startMillis, ny).size)
    }
}
