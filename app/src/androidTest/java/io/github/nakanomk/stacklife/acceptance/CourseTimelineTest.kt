package io.github.nakanomk.stacklife.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseTimelineTest {
    @get:Rule val store = WorkspaceFixture()
    private val term = TermSpec("2026 秋", LocalDate.of(2026, 9, 7), 18, BellSchedule.SUMMER, LocalDate.of(2026, 11, 2))
    private val winter = term.switchDate!!
    private fun at(date: LocalDate, h: Int, m: Int) = date.atTime(h, m).atZone(campusZone).toOffsetDateTime()
    private suspend fun day(date: LocalDate) = store.api.observe(PlannerQuery.Day(date, campusZone)).first() as DayPlan
    private suspend fun week(n: Int, id: String = term.id) = store.api.observe(PlannerQuery.Week(id, n)).first() as CourseWeek
    private fun fixture(): List<CourseMeeting> {
        val input = InstrumentationRegistry.getInstrumentation().context.assets.open("hust-week-acceptance.json").use(::readWeekFile)
        val draft = HustCourseImporter.parse(input, term)
        assertEquals("实践", draft.issues.single().fields!!.period)
        return draft.resolve(mapOf(draft.issues.single().position to IssueDecision.Exclude))
    }
    private suspend fun proposal(spec: TermSpec, meetings: List<CourseMeeting>, vararg before: PlannerCommand): PreviewResult {
        val id = store.api.stageCourseImport(spec, meetings)
        return try { store.api.preview(before.toList() + CourseImport(id)) } finally { store.api.discardCourseImport(id) }
    }
    private suspend fun commit(result: PreviewResult) {
        assertTrue("Unexpected preview: $result", result is PreviewResult.Ready)
        assertTrue(store.api.commit((result as PreviewResult.Ready).proposalId) is CommitResult.Applied)
    }

    @Test fun rawFileReimportAndRemovalAreScopedToTheTermAndSource() = runBlocking {
        store.apply(TaskAdd(TaskInput("报告提纲")))
        val originalQueue = store.state().queue
        val meetings = fixture(); val preview = proposal(term, meetings) as PreviewResult.Ready
        assertEquals(3, preview.changes.count { it.field.startsWith("新增课程") })
        assertNull(week(1).term); commit(preview)
        val sep = week(1).classes.single().meeting; val nov = week(9).classes.single().meeting
        assertEquals("A", sep.room); assertEquals("B", week(3).classes.single().meeting.room); assertTrue(week(2).classes.isEmpty())
        assertEquals(at(term.firstMonday, 14, 30).toInstant().toEpochMilli(), sep.startMillis)
        assertEquals(at(term.firstMonday, 16, 5).toInstant().toEpochMilli(), sep.endMillis)
        assertEquals(at(winter, 14, 0).toInstant().toEpochMilli(), nov.startMillis)
        assertEquals(at(winter, 15, 35).toInstant().toEpochMilli(), nov.endMillis)
        val repeated = proposal(term, fixture()) as PreviewResult.Ready
        assertTrue(repeated.changes.isEmpty()); commit(repeated)
        store.reopen(); assertEquals(listOf(nov), day(winter).courses.map { it.meeting })
        val other = term.copy(id = "暑期课")
        commit(proposal(other, listOf(other.meeting(2, 2, "独立课", "C", 1, 1))))
        assertTrue(proposal(term, emptyList()) is PreviewResult.Rejected)
        assertTrue(store.api.preview(listOf(CourseImport("nonexistent"))) is PreviewResult.Rejected)
        // Same logical term ID, different source: neither import nor removal may touch it.
        val db = store.db.openHelper.writableDatabase
        db.execSQL("INSERT INTO course_terms SELECT termId,'another-source',monday,weeks,initial,switchDay FROM course_terms WHERE termId=?", arrayOf(term.id))
        db.execSQL("""INSERT INTO course_meetings (termId,source,week,day,firstPeriod,lastPeriod,title,room,startMillis,endMillis)
            SELECT termId,'another-source',week,day,firstPeriod,lastPeriod,'交换学期研讨','D',startMillis,endMillis
            FROM course_meetings WHERE termId=? AND source=? AND week=9""", arrayOf(term.id, HUST_SOURCE))
        commit(proposal(term, meetings))
        val both = day(winter).courses.associateBy { it.source }
        assertTrue(both.getValue(HUST_SOURCE).conflicts.single().contains("交换学期研讨"))
        val foreign = both.getValue("another-source")
        assertTrue(foreign.conflicts.single().contains("操作系统"))
        val removal = store.proposal(TermRemove(term.id)); assertEquals(3, removal.changes.count { it.field.startsWith("移除课程") })
        assertEquals(nov, both.getValue(HUST_SOURCE).meeting)
        store.api.discard(removal.proposalId); assertEquals(term, week(1).term)
        store.apply(TermRemove(term.id)); assertNull(week(1).term)
        assertEquals(listOf(foreign.copy(conflicts = emptyList())), day(winter).courses)
        assertEquals("独立课", week(2, other.id).classes.single().meeting.title)
        db.query("SELECT COUNT(*) FROM course_terms WHERE source='another-source'").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        assertEquals(originalQueue, store.state().queue)
    }

    @Test fun importingOntoAnExistingSlotLabelsBothAndProtectsTheRightEndpoint() = runBlocking {
        store.apply(TaskAdd(TaskInput("报告提纲", ref = "t")), SlotSet("@t", at(winter, 15, 0), 10))
        val id = store.state().queue.single().id
        val before = day(winter); val preview = proposal(term, fixture()) as PreviewResult.Ready
        assertTrue(preview.changes.any { "操作系统" in it.field && it.after.orEmpty().contains("报告提纲") })
        assertEquals(before, day(winter)); commit(preview)
        val collided = day(winter); assertEquals(before.blocks, collided.blocks); assertEquals(setOf(id), collided.conflictingTaskIds)
        assertEquals(listOf("报告提纲"), collided.courses.single().conflicts); assertEquals(collided.courses, week(9).classes)
        val rejected = store.api.preview(listOf(SlotSet(id, at(winter, 15, 0), 30))) as PreviewResult.Rejected
        for (s in listOf("操作系统", "14:00", "15:35", "B")) assertTrue(rejected.message, s in rejected.message)
        assertEquals(collided, day(winter))
        store.apply(SlotSet(id, at(winter, 15, 35), 30)); val adjacent = day(winter)
        assertEquals(id, adjacent.blocks.single().taskId); assertEquals(novEnd(), adjacent.blocks.single().startMillis)
        assertTrue(adjacent.conflictingTaskIds.isEmpty()); assertTrue(adjacent.courses.single().conflicts.isEmpty())
    }
    private fun novEnd() = at(winter, 15, 35).toInstant().toEpochMilli()

    @Test fun orderedImportsCannotBypassConflictsOrSurviveAnInvalidBatch() = runBlocking {
        store.apply(TaskAdd(TaskInput("A"))); val task = store.state().queue.single().id
        val id = store.api.stageCourseImport(term, fixture()); val placement = SlotSet(task, at(winter, 15, 0), 30)
        val before = day(winter)
        val invalid = store.api.preview(listOf(CourseImport(id), placement)) as PreviewResult.Rejected
        assertTrue("操作系统" in invalid.message); assertEquals(before, day(winter))
        val mixed = store.proposal(placement, CourseImport(id)); assertTrue(mixed.changes.any { it.field.startsWith("冲突") })
        store.apply(TaskAdd(TaskInput("第二项")))
        assertEquals(CommitFailure.STALE_PREVIEW, (store.api.commit(mixed.proposalId) as CommitResult.Rejected).code)
        assertTrue(day(winter).courses.isEmpty()); assertTrue(day(winter).blocks.isEmpty())
        store.apply(CourseImport(id)); store.api.discardCourseImport(id)
        store.apply(TermRemove(term.id), placement)
        assertTrue(day(winter).courses.isEmpty()); assertEquals(task, day(winter).blocks.single().taskId)
    }

    @Test fun failedReplacementRollsBackTheWholeImportIncludingEarlierCommands() = runBlocking {
        commit(proposal(term, fixture())); val original = day(winter); val originalWorkspace = store.state()
        val changed = term.copy(initial = BellSchedule.WINTER, switchDate = null)
        val preview = proposal(changed, listOf(changed.meeting(2, 2, "新课", "D", 2, 3)), TaskAdd(TaskInput("不应留下"))) as PreviewResult.Ready
        store.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_meeting BEFORE INSERT ON course_meetings BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertEquals(CommitFailure.STORAGE, (store.api.commit(preview.proposalId) as CommitResult.Rejected).code)
        store.reopen(); assertEquals(original, day(winter)); assertEquals(originalWorkspace, store.state()); assertEquals(term, week(1).term)
    }
}
