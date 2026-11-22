package io.github.nakanomk.stacklife.acceptance

import com.materialkolor.ktx.toHct
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import java.time.*
import kotlin.math.abs
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
        return draft.resolve(mapOf(draft.issues.single().position to IssueDecision.Exclude)).meetings
    }
    private suspend fun proposal(spec: TermSpec, meetings: List<CourseMeeting>, vararg before: PlannerCommand): PreviewResult {
        val id = store.api.stageCourseImport(spec, meetings)
        return try { store.api.preview(before.toList() + CourseImport(id)) } finally { store.api.discardCourseImport(id) }
    }
    private suspend fun commit(result: PreviewResult) {
        assertTrue("Unexpected preview: $result", result is PreviewResult.Ready)
        assertTrue(store.api.commit((result as PreviewResult.Ready).proposalId) is CommitResult.Applied)
    }

    @Test fun courseHuesUseTermOrderNotTheCurrentWeekAndSurviveReopen() = runBlocking {
        val meetings = listOf(term.meeting(1, 1, "实验", "A", 1, 2), term.meeting(1, 2, "代数", "B", 3, 3),
            term.meeting(2, 3, "实验", "不同教室", 5, 6), term.meeting(2, 5, "物理", "C", 7, 8))
        commit(proposal(term, meetings))
        val first = week(1).colorIndices
        val second = week(2).colorIndices
        assertEquals(setOf("实验", "代数"), first.keys)
        assertEquals(setOf("实验", "物理"), second.keys)
        assertEquals(first.getValue("实验"), second.getValue("实验"))
        assertEquals(3, (first + second).values.map { courseColor(term.id, it) }.distinct().size)
        store.apply(EditAppearance(AppearancePatch.Replace(Appearance(courseTextOutline = true))))
        store.reopen()
        assertTrue((store.api.observe(PlannerQuery.Settings).first() as PolicyView).appearance.courseTextOutline)
        assertEquals(first, week(1).colorIndices)
        assertEquals(second, week(2).colorIndices)
    }

    @Test fun largeCourseCatalogStaysDistinctWithoutDarkOrFluorescentTiles() {
        val swatches = (0 until 32).map { courseColor(term.id, it).toHct() }
        for ((i, swatch) in swatches.withIndex()) {
            assertTrue("Dark or glaring tile $i: $swatch", swatch.tone in 69.0..85.0)
            assertTrue("Gray or fluorescent tile $i: $swatch", swatch.chroma in 24.0..48.0)
            for (j in 0 until i) {
                val other = swatches[j]
                val delta = abs(swatch.hue - other.hue)
                assertTrue("Courses $i and $j differ in neither hue nor lightness: $swatch, $other",
                    minOf(delta, 360.0 - delta) > 12.0 || abs(swatch.tone - other.tone) > 3.0)
            }
        }
        val hues = swatches.take(8).map { it.hue }.sorted()
        assertTrue("An ordinary term should span warm and cool families: $hues",
            (hues + (hues.first() + 360)).zipWithNext().all { (a, b) -> b - a < 75 })
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
        assertTrue(store.api.preview(listOf(TermRemove(term.id))) is PreviewResult.Rejected)
        val removal = store.proposal(TermRemove(term.id, HUST_SOURCE)); assertEquals(3, removal.changes.count { it.field.startsWith("移除课程") })
        assertEquals(nov, both.getValue(HUST_SOURCE).meeting)
        store.api.discard(removal.proposalId); assertEquals(term, week(1).term)
        store.apply(TermRemove(term.id, HUST_SOURCE)); assertEquals(term, week(1).term)
        assertEquals(setOf("another-source"), week(9).classes.map { it.source }.toSet())
        assertEquals(listOf(foreign.copy(conflicts = emptyList())), day(winter).courses)
        assertEquals("独立课", week(2, other.id).classes.single().meeting.title)
        db.query("SELECT COUNT(*) FROM course_terms WHERE source='another-source'").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        assertEquals(originalQueue, store.state().queue)
    }

    @Test fun alternateReviewedSourceReplacesOnlyItsOwnTermAndUsesItsOwnBellTimes() = runBlocking {
        val alternate = "test-campus"
        // A Sunday evening at this school falls on Monday in Beijing; it is still in week one.
        val calendar = object : CourseCalendar {
            override val zone = ZoneId.of("America/Vancouver")
            private val bells = (8..20).map { PeriodTime(LocalTime.of(it, 0), LocalTime.of(it, 45)) }
            override fun periods(term: TermSpec, date: LocalDate) = bells
            override fun scheduleDescription(term: TermSpec) = "Test schedule"
        }
        val api = RoomPlannerApi(store.db, SchoolImports.calendars + (alternate to calendar))
        suspend fun save(reviewed: ReviewedTimetable) {
            val id = api.stageCourseImport(reviewed.term, reviewed.meetings, reviewed.source)
            try {
                val preview = api.preview(listOf(CourseImport(id)))
                assertTrue(preview.toString(), preview is PreviewResult.Ready)
                assertTrue(api.commit((preview as PreviewResult.Ready).proposalId) is CommitResult.Applied)
            } finally { api.discardCourseImport(id) }
        }
        val manual = ManualSeries(CoursePattern("Manual", "M", 4, 1, 1), setOf(1))
        store.apply(ManualCourse(term.id, null, manual, term))
        val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("hust-week-acceptance.json").use(::readWeekFile)
        val hust = HustCourseImporter.parse(raw, term)
        save(hust.resolve(mapOf(hust.issues.single().position to IssueDecision.Exclude)))
        val sunday = term.meeting(1, 7, "Seminar", "E", 13, 13, calendar)
        val reviewed = ReviewedTimetable(alternate, term, listOf(sunday))
        save(reviewed)
        val moved = sunday.copy(room = "W")
        save(reviewed.copy(meetings = listOf(moved)))
        store.reopen()
        val rows = week(1).classes.associateBy { it.source }
        assertEquals(setOf(alternate, MANUAL_SOURCE, HUST_SOURCE), rows.keys)
        assertEquals(3, week(1).classes.size)
        assertEquals(moved, rows.getValue(alternate).meeting)
        assertEquals("A", rows.getValue(HUST_SOURCE).meeting.room)
        assertEquals("Manual", rows.getValue(MANUAL_SOURCE).meeting.title)
        val catalog = store.api.observe(PlannerQuery.Terms).first() as Terms
        assertEquals(setOf(alternate, HUST_SOURCE), catalog.importedSources.getValue(term.id).toSet())
        store.apply(TermRemove(term.id, alternate))
        assertEquals(setOf(HUST_SOURCE, MANUAL_SOURCE), week(1).classes.map { it.source }.toSet())
        assertTrue(store.api.preview(listOf(TermRemove(term.id, MANUAL_SOURCE))) is PreviewResult.Rejected)
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
