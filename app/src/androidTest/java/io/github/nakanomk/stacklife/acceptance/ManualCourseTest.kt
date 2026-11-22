package io.github.nakanomk.stacklife.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualCourseTest {
    @get:Rule val store = WorkspaceFixture()
    private val term = TermSpec("秋季", LocalDate.parse("2026-09-07"), 16, BellSchedule.SUMMER, LocalDate.parse("2026-11-02"))
    private val series = ManualSeries(CoursePattern("实验", "A", 1, 5, 6), setOf(1, 3, 9))
    private suspend fun week(n: Int) = store.api.observe(PlannerQuery.Week(term.id, n)).first() as CourseWeek
    private suspend fun day(date: LocalDate) = store.api.observe(PlannerQuery.Day(date, campusZone)).first() as DayPlan
    private suspend fun hub(spec: TermSpec, title: String, block: suspend (CourseImport) -> Unit) {
        val id = store.api.stageCourseImport(spec, listOf(spec.meeting(9, 1, title, "A", 5, 6)))
        try { block(CourseImport(id)) } finally { store.api.discardCourseImport(id) }
    }

    @Test fun manualCalendarIsAtomicAndSharesTimetableColorDayAndSlotRulesAcrossReopen() = runBlocking {
        val add = ManualCourse(term.id, null, series, term)
        val discarded = store.proposal(add)
        assertEquals(series.weeks, (discarded.changes.single().after as CourseSetSnapshot).meetings.map { it.week }.toSet())
        store.api.discard(discarded.proposalId); assertNull(week(1).term)
        assertTrue(store.api.preview(listOf(add.copy(after = series.copy(weeks = setOf(0))))) is PreviewResult.Rejected)
        assertNull(week(1).term)
        store.apply(add); store.reopen()
        assertEquals(term, week(1).term); assertTrue(week(2).classes.isEmpty())
        assertEquals(week(1).colorIndices, week(9).colorIndices)
        val nov = checkNotNull(term.switchDate)
        val morning = week(1).classes.single(); val winter = week(9).classes.single()
        assertEquals(MANUAL_SOURCE, morning.source)
        assertEquals(term.firstMonday.atTime(14, 30).atZone(campusZone).toInstant().toEpochMilli(), morning.meeting.startMillis)
        assertEquals(nov.atTime(14, 0).atZone(campusZone).toInstant().toEpochMilli(), winter.meeting.startMillis)
        assertEquals(winter, day(nov).occurrences.mapNotNull { it.course }.single())
        assertTrue(store.api.preview(listOf(add)) is PreviewResult.Rejected)
        assertTrue(store.api.preview(listOf(add.copy(createTerm = null))) is PreviewResult.Rejected)
        store.apply(TaskAdd(TaskInput("排程")))
        val task = store.state().queue.single().id
        val start = nov.atTime(15, 0).atZone(campusZone).toOffsetDateTime()
        store.apply(exact(task,start,30)); assertEquals(listOf(winter.meeting.title),day(nov).occurrences.single { it.taskId == task }.conflicts)
        store.apply(exact(task, start.plusMinutes(35), 30))
        assertEquals(task, day(nov).occurrences.single { it.taskId == task }.taskId)
    }

    @Test fun identicalHubAndManualOccurrencesStayIndividuallyOwnedOnEditReimportAndRemove() = runBlocking {
        hub(term, "实验") { store.apply(it) }
        val add = ManualCourse(term.id, null, series)
        val proposed = store.proposal(add)
        val conflict = (proposed.changes.single().after as CourseSetSnapshot).conflicts.single()
        assertEquals(MANUAL_SOURCE, conflict.occurrence.source); assertEquals(HUST_SOURCE, conflict.other.course?.source)
        assertTrue(store.api.commit(proposed.proposalId) is CommitResult.Applied)
        val overlapping = week(9)
        assertEquals(setOf(MANUAL_SOURCE, HUST_SOURCE), overlapping.classes.map { it.source }.toSet())
        assertTrue(overlapping.classes.all { it.conflicts.isNotEmpty() })
        assertEquals(setOf("实验"), overlapping.colorIndices.keys)
        hub(term.copy(firstMonday = term.firstMonday.plusWeeks(1)), "错误校历") {
            assertTrue(store.api.preview(listOf(it)) is PreviewResult.Rejected)
        }
        val initial = overlapping.classes.single { it.source == MANUAL_SOURCE }
        val key = OccurrenceKey.Course(CourseOccurrenceId.of(term.id,initial.source,initial.meeting))
        val custom = ReminderPolicy(false,listOf(ReminderRule("before-lab",ReminderWhen.BeforeStart(90*60_000L))))
        store.apply(SetOccurrenceCompletion(key,true),SetReminders(ReminderSubject.Occurrence(key),custom))
        val changed = ManualSeries(CoursePattern("实训", "B", 2, 7, 8), setOf(2, 9))
        val edit = store.proposal(ManualCourse(term.id, series, changed))
        val linkage = edit.changes.mapNotNull { it.after as? CourseSetSnapshot }.single().replacements.single()
        assertEquals(9, linkage.old.week); assertEquals(9, linkage.new.week)
        assertEquals(series.pattern.title, linkage.old.title); assertEquals(changed.pattern.title, linkage.new.title)
        assertTrue(store.api.commit(edit.proposalId) is CommitResult.Applied)
        store.reopen()
        val replacement = OccurrenceKey.Course(linkage.new)
        assertTrue(week(9).classes.single { it.source == MANUAL_SOURCE }.completed)
        assertFalse(week(9).classes.single { it.source == HUST_SOURCE }.completed)
        assertEquals(custom,(store.api.observe(PlannerQuery.Reminders(ReminderSubject.Occurrence(replacement))).first() as ReminderView).policy)
        assertNull((store.api.observe(PlannerQuery.Occurrence(key,campusZone)).first() as OccurrenceView).occurrence)
        assertFalse(week(2).classes.single { it.source == MANUAL_SOURCE }.completed)
        assertTrue(store.api.preview(listOf(ManualCourse(term.id, series, null))) is PreviewResult.Rejected)
        hub(term, "导入课") { store.apply(it) }
        assertEquals(setOf("实训", "导入课"), week(9).classes.map { it.meeting.title }.toSet())
        val read = store.api.observe(PlannerQuery.CourseWeeks(term.id, MANUAL_SOURCE, changed.pattern)).first() as CourseWeeksView
        assertEquals(changed.weeks, read.weeks)
        val other = week(9).classes.single { it.source == HUST_SOURCE }
        val imported = store.api.observe(PlannerQuery.CourseWeeks(term.id, HUST_SOURCE, other.meeting.pattern())).first() as CourseWeeksView
        assertEquals(setOf(9), imported.weeks)
        store.apply(TermRemove(term.id)); assertEquals(MANUAL_SOURCE, week(9).classes.single().source)
        hub(term, "独立课") { store.apply(it) }
        store.apply(ManualCourse(term.id, changed, null)); store.reopen()
        assertEquals("独立课", week(9).classes.single().meeting.title)
        assertEquals(HUST_SOURCE, week(9).classes.single().source)
        assertTrue(week(2).classes.isEmpty())
    }
}
