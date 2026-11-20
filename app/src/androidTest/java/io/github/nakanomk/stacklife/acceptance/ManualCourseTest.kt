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
        assertEquals(3, discarded.changes.count { it.field.startsWith("新增课程") })
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
        assertEquals(winter, day(nov).courses.single())
        assertTrue(store.api.preview(listOf(add)) is PreviewResult.Rejected)
        assertTrue(store.api.preview(listOf(add.copy(createTerm = null))) is PreviewResult.Rejected)
        store.apply(TaskAdd(TaskInput("排程")))
        val task = store.state().queue.single().id
        val start = nov.atTime(15, 0).atZone(campusZone).toOffsetDateTime()
        assertTrue(store.api.preview(listOf(SlotSet(task, start, 30))) is PreviewResult.Rejected)
        store.apply(SlotSet(task, start.plusMinutes(35), 30))
        assertEquals(task, day(nov).blocks.single().taskId)
    }

    @Test fun identicalHubAndManualOccurrencesStayIndividuallyOwnedOnEditReimportAndRemove() = runBlocking {
        hub(term, "实验") { store.apply(it) }
        val add = ManualCourse(term.id, null, series)
        val proposed = store.proposal(add)
        assertTrue(proposed.changes.any { it.field.startsWith("冲突") })
        assertTrue(store.api.commit(proposed.proposalId) is CommitResult.Applied)
        val overlapping = week(9)
        assertEquals(setOf(MANUAL_SOURCE, HUST_SOURCE), overlapping.classes.map { it.source }.toSet())
        assertTrue(overlapping.classes.all { it.conflicts.isNotEmpty() })
        assertEquals(setOf("实验"), overlapping.colorIndices.keys)
        hub(term.copy(firstMonday = term.firstMonday.plusWeeks(1)), "错误校历") {
            assertTrue(store.api.preview(listOf(it)) is PreviewResult.Rejected)
        }
        val changed = ManualSeries(CoursePattern("实训", "B", 2, 7, 8), setOf(2, 9))
        store.apply(ManualCourse(term.id, series, changed))
        assertTrue(store.api.preview(listOf(ManualCourse(term.id, series, null))) is PreviewResult.Rejected)
        hub(term, "导入课") { store.apply(it) }
        assertEquals(setOf("实训", "导入课"), week(9).classes.map { it.meeting.title }.toSet())
        val read = store.api.observe(PlannerQuery.ManualWeeks(term.id, changed.pattern)).first() as ManualWeeksView
        assertEquals(changed.weeks, read.weeks)
        store.apply(TermRemove(term.id)); assertEquals(MANUAL_SOURCE, week(9).classes.single().source)
        hub(term, "独立课") { store.apply(it) }
        store.apply(ManualCourse(term.id, changed, null)); store.reopen()
        assertEquals("独立课", week(9).classes.single().meeting.title)
        assertEquals(HUST_SOURCE, week(9).classes.single().source)
        assertTrue(week(2).classes.isEmpty())
    }
}
