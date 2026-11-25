package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.i18n.AppLanguage
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.today.TodayRoute
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarArrowTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)

    private fun awaitOne(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(10_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
        return ui.onNode(matcher)
    }

    private fun slide(content: String, arrow: SemanticsMatcher, direction: Int, weekHeading: String? = null) {
        val outgoing = awaitOne(hasTestTag(content)).fetchSemanticsNode()
        val width = outgoing.size.width
        val dock = ui.onNodeWithTag("navigation-capsule").fetchSemanticsNode().boundsInRoot
        val click = awaitOne(arrow).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { click() }
            ui.mainClock.advanceTimeBy(144)
            if (weekHeading != null) ui.onNodeWithTag("course-week").assertTextContains(weekHeading)
            val displacement = ui.runOnIdle {
                val coordinates = outgoing.layoutInfo.coordinates
                assertTrue("Old $content must still be drawn mid-transition", coordinates.isAttached && outgoing.layoutInfo.isPlaced)
                -direction * coordinates.localToRoot(Offset.Zero).x
            }
            assertTrue("Arrow should move $content by a fraction of one page, got $displacement / $width", displacement in (width * .015f)..(width * .99f))
            val arriving = ui.onNodeWithTag(content).fetchSemanticsNode()
            val leading = ui.runOnIdle { arriving.layoutInfo.coordinates.localToRoot(Offset.Zero).x }
            assertEquals("The adjacent calendar must fill the revealed area rather than showing a loading gap",
                width - displacement, direction * leading, 2f)
            assertEquals(dock, ui.onNodeWithTag("navigation-capsule").fetchSemanticsNode().boundsInRoot)
            ui.mainClock.advanceTimeBy(500)
        } finally { ui.mainClock.autoAdvance = true }
    }

    @Test fun arrowsSlideTheCalendarUnderAStationaryDockInBothDirections() {
        val monday = LocalDate.now(campusZone).plusYears(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val term = TermSpec("分页测试", monday, 2, BellSchedule.WINTER)
        runBlocking {
            val id = store.api.stageCourseImport(term, listOf(term.meeting(1, 1, "甲", "101", 1, 2), term.meeting(2, 4, "乙", "202", 3, 4)))
            try { store.apply(PlannerCommand.CourseImport(id)) } finally { store.api.discardCourseImport(id) }
        }
        ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        slide("day-axis", hasContentDescription("后一天"), 1)
        slide("day-axis", hasContentDescription("前一天"), -1)
        ui.onNodeWithTag("nav:courses").performClick()
        val grid = awaitOne(hasTestTag("week-grid"))
        grid.performTouchInput {
            down(center); moveBy(Offset(-width * .16f, 0f), 90)
        }
        ui.onNodeWithTag("course-week").assertTextContains("第 2 周")
        grid.performTouchInput {
            moveBy(Offset(width * .16f, 0f), 300); advanceEventTime(200); up()
        }
        awaitOne(hasTestTag("course-week") and hasText("第 1 周"))
        slide("week-grid", hasTestTag("course-next"), 1, "第 2 周")
        awaitOne(hasTestTag("course-week") and hasText("第 2 周")).assertIsDisplayed()
        slide("week-grid", hasTestTag("course-prev"), -1, "第 1 周")
        awaitOne(hasTestTag("course-week") and hasText("第 1 周")).assertIsDisplayed()
    }

    @Test fun rapidRequestsAndListReturnStayOnTheSameTimetableWithoutWriting() {
        val monday = LocalDate.now(campusZone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
        val term = TermSpec("翻页验收", monday, 9, BellSchedule.SUMMER)
        val revision = runBlocking {
            val id = store.api.stageCourseImport(term, (1..9).map { term.meeting(it, 1, "课程-$it", "", 1, 2) })
            try { store.apply(PlannerCommand.CourseImport(id)) } finally { store.api.discardCourseImport(id) }
            store.state().revision
        }
        ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        awaitOne(hasTestTag("nav:courses")).performClick()
        val header = awaitOne(hasTestTag("course-week") and hasText("第 2 周"))
        ui.mainClock.autoAdvance = false
        try {
            var n = 2
            for (delta in listOf(1, 1, 1, 1, 1, -1, 1, -1)) {
                ui.onNodeWithTag(if (delta == 1) "course-next" else "course-prev").performClick()
                n += delta
                ui.mainClock.advanceTimeBy(80)
                ui.onNodeWithTag("course-week").assertTextContains("第 $n 周")
            }
            ui.mainClock.advanceTimeBy(1000)
        } finally { ui.mainClock.autoAdvance = true }
        awaitOne(hasTestTag("course-options")).performClick()
        awaitOne(hasTestTag("course-view-toggle")).performClick()
        awaitOne(hasTestTag("week-course:0") and hasText("课程-6")).assertIsDisplayed()
        ui.onNodeWithTag("course-options").performClick()
        awaitOne(hasTestTag("course-current-week")).performClick()
        header.assertTextContains(term.id)
        awaitOne(hasTestTag("week-course:0") and hasText("课程-2")).assertIsDisplayed()
        assertEquals(revision, runBlocking { store.state().revision })
    }

    private fun install(term: TermSpec, vararg meetings: CourseMeeting) = runBlocking {
        val draft = store.api.stageCourseImport(term, meetings.toList())
        try { store.apply(PlannerCommand.CourseImport(draft)) } finally { store.api.discardCourseImport(draft) }
    }

    @Test fun weekDatesArriveBeforeBlockedReadAndOpenTheRequestedOccurrence() {
        val monday = LocalDate.of(2028, 2, 28)
        val term = TermSpec("Calendar dates", monday, 2, BellSchedule.WINTER)
        install(term, term.meeting(1, 1, "Lab", "First room", 1, 2), term.meeting(2, 1, "Lab", "Second room", 1, 2))
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = object : PlannerApi by store.api {
            override fun observe(query: PlannerQuery) = store.api.observe(query).onStart {
                if (query == PlannerQuery.Week(term.id, 2)) { reading.complete(Unit); release.await() }
            }
        }
        val owner = mutableStateOf(CourseSelection(term.id, 1))
        val publications = mutableListOf<CourseSelection>()
        ui.setContent {
            StackLifeTheme {
                CoursesRoute(viewModel { WorkspaceViewModel(slow) }, owner.value,
                    { publications.add(it); owner.value = it }, {}, { _, _ -> }, {}, {})
            }
        }
        awaitOne(hasTestTag("grid-course:0"))
        ui.onNodeWithTag("course-next").performClick()
        ui.waitUntil(10_000) { reading.isCompleted }
        val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(AppLanguage.locale)
        val newMonday = hasContentDescription(monday.plusWeeks(1).format(format))
        try {
            awaitOne(newMonday).assertIsDisplayed().assertIsNotEnabled()
            ui.onNodeWithContentDescription(monday.format(format)).assertDoesNotExist()
            ui.onNodeWithTag("grid-course:0").assertDoesNotExist()
        } finally { release.complete(Unit) }
        awaitOne(newMonday and isEnabled()).performClick()
        awaitOne(hasText("Second room") and hasClickAction()).performClick()
        awaitOne(hasText("Second room") and hasAnyAncestor(hasTestTag("course-detail"))).performScrollTo().assertIsDisplayed()
        ui.runOnIdle { assertEquals(listOf(CourseSelection(term.id, 2)), publications) }
    }

    @Test fun heldWeekPreviewNeverChangesScopeAndExternalTermSurvivesReleaseAndRestore() {
        val first = TermSpec("Calendar A", LocalDate.of(2028, 2, 28), 5, BellSchedule.WINTER)
        val other = first.copy(id = "Calendar B", firstMonday = first.firstMonday.plusWeeks(6))
        install(first, first.meeting(2, 1, "A", "", 1, 2))
        install(other, other.meeting(4, 1, "B", "", 1, 2))
        val start = CourseSelection(first.id, 2)
        val end = CourseSelection(other.id, 4)
        val publications = mutableListOf<CourseSelection>()
        lateinit var external: (CourseSelection) -> Unit
        lateinit var scope: AssistantPage
        val restore = StateRestorationTester(ui)
        restore.setContent {
            val owner = rememberSaveable { mutableStateOf(start) }
            external = { owner.value = it }
            StackLifeTheme {
                CoursesRoute(viewModel { WorkspaceViewModel(store.api) }, owner.value,
                    { publications.add(it); owner.value = it }, {}, { _, _ -> }, {}, { scope = it })
            }
        }
        fun heading(n: Int) = hasTestTag("course-week") and hasText(ui.activity.getString(R.string.courses_week, n))
        val grid = awaitOne(hasTestTag("week-grid"))
        grid.performTouchInput { down(center); moveBy(Offset(-width * .20f, 0f), 90) }
        awaitOne(heading(3))
        grid.performTouchInput { moveBy(Offset(width * .42f, 0f), 180) }
        awaitOne(heading(1))
        ui.runOnIdle { assertEquals(AssistantPage.Week(first.id, 2), scope); assertTrue(publications.isEmpty()) }
        ui.onRoot().performTouchInput { cancel() }
        awaitOne(heading(2))
        grid.performTouchInput { swipeLeft() }
        ui.waitUntil(10_000) { scope == AssistantPage.Week(first.id, 3) }
        grid.performTouchInput { down(center); moveBy(Offset(-width * .20f, 0f), 90) }
        awaitOne(heading(4))
        ui.runOnIdle { external(end) }
        awaitOne(heading(4) and hasText(other.displayLabel()))
        ui.onRoot().performTouchInput { up() }
        restore.emulateSavedInstanceStateRestore()
        awaitOne(heading(4) and hasText(other.displayLabel())).assertIsDisplayed()
        ui.runOnIdle {
            assertEquals(AssistantPage.Week(other.id, 4), scope)
            assertEquals(listOf(CourseSelection(first.id, 3)), publications)
        }
    }

    @Test fun notificationForCommittedDayTakesOverHeldDayPreviewWithoutPublishing() {
        val day = LocalDate.of(2028, 3, 7)
        val query = PlannerQuery.Day(day, campusZone)
        val event = runBlocking {
            store.apply(PlannerCommand.TaskAdd(TaskInput("Reminder target", ref = "r")),
                once("@r", day.atTime(14, 30).atZone(campusZone).toOffsetDateTime(), 30))
            (store.api.observe(query).first() as DayPlan).occurrences.single { it.title == "Reminder target" }.key
        }
        val owner = mutableStateOf(day)
        val reveal = mutableStateOf<OccurrenceKey?>(null)
        var acknowledgements = 0
        val publications = mutableListOf<LocalDate>()
        ui.setContent {
            StackLifeTheme {
                TodayRoute(viewModel { WorkspaceViewModel(store.api) }, owner.value, campusZone,
                    { publications.add(it); owner.value = it }, {}, {}, reveal.value,
                    { acknowledgements++; reveal.value = null }, {})
            }
        }
        fun heading(date: LocalDate): SemanticsMatcher {
            val full = DateTimeFormatter.ofPattern(ui.activity.getString(R.string.today_full_date_pattern), AppLanguage.locale)
            return hasTestTag("day-date") and hasContentDescription(ui.activity.getString(R.string.today_choose_date, date.format(full)))
        }
        awaitOne(hasTestTag("day-axis")).performTouchInput { down(center); moveBy(Offset(-width * .20f, 0f), 80) }
        awaitOne(heading(day.plusDays(1)))
        ui.runOnIdle { assertTrue(publications.isEmpty()); reveal.value = event }
        awaitOne(heading(day))
        ui.onRoot().performTouchInput { up() }
        ui.waitUntil(10_000) { acknowledgements == 1 }
        ui.runOnIdle { assertTrue(publications.isEmpty()); assertEquals(day, owner.value) }
    }

    @Test fun fingerTakesOverFarArrowTargetAndOnlyItsActualLandingIsPublished() {
        val term = TermSpec("Far target", LocalDate.of(2028, 2, 28), 12, BellSchedule.WINTER)
        install(term, *(1..12).map { term.meeting(it, 1, "Class [$it]", "", 1, 2) }.toTypedArray())
        val owner = mutableStateOf(CourseSelection(term.id, 1))
        val publications = mutableListOf<CourseSelection>()
        ui.setContent { StackLifeTheme {
            CoursesRoute(viewModel { WorkspaceViewModel(store.api) }, owner.value,
                { publications.add(it); owner.value = it }, {}, { _, _ -> }, {}, {})
        } }
        val next = awaitOne(hasTestTag("course-next")).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val grid = awaitOne(hasTestTag("week-grid")).fetchSemanticsNode().boundsInRoot
        val start = Offset(grid.left + grid.width * .18f, grid.center.y)
        ui.mainClock.autoAdvance = false
        try {
            // Reuse the real arrow hit action before a recomposition; the pager pre-jumps
            // once, but all nine choices must be based on the latest requested week.
            ui.runOnUiThread { repeat(9) { next() } }
            ui.mainClock.advanceTimeBy(48)
            ui.onNodeWithTag("course-week").assertTextContains(ui.activity.getString(R.string.courses_week, 10))
            ui.onRoot().performTouchInput {
                down(start)
                moveTo(start + Offset(grid.width * .65f, 0f), 160)
            }
            ui.mainClock.advanceTimeByFrame()
            ui.runOnIdle { assertEquals((2..10).map { CourseSelection(term.id, it) }, publications) }
            ui.onRoot().performTouchInput { advanceEventTime(260); up() }
            ui.mainClock.advanceTimeBy(1000)
        } finally { ui.mainClock.autoAdvance = true }
        val actual = ui.runOnIdle {
            assertEquals(10, publications.size)
            checkNotNull(owner.value.week).also { assertTrue("Takeover must return from week 10, landed on $it", it in 2..9) }
        }
        awaitOne(hasTestTag("grid-course:0") and hasContentDescription("Class [$actual]", substring = true)).assertIsDisplayed()
        ui.onNodeWithTag("course-week").assertTextContains(ui.activity.getString(R.string.courses_week, actual))
    }

    @Test fun confirmedCourseStillSavesButLateReceiptCannotSelectTheOldTerm() {
        val first = TermSpec("Editor origin", LocalDate.of(2028, 2, 28), 5, BellSchedule.WINTER)
        val other = first.copy(id = "Current term", firstMonday = first.firstMonday.plusWeeks(6))
        install(first, first.meeting(1, 1, "Origin", "", 1, 2))
        install(other, other.meeting(3, 1, "Current", "", 1, 2))
        val written = CompletableDeferred<Unit>()
        val receipt = CompletableDeferred<Unit>()
        val delayed = object : PlannerApi by store.api {
            override suspend fun commit(proposalId: String): CommitResult {
                val result = store.api.commit(proposalId)
                written.complete(Unit)
                receipt.await()
                return result
            }
        }
        val owner = mutableStateOf(CourseSelection(first.id, 2))
        val publications = mutableListOf<CourseSelection>()
        ui.setContent { StackLifeTheme {
            CoursesRoute(viewModel { WorkspaceViewModel(delayed) }, owner.value,
                { publications.add(it); owner.value = it }, {}, { _, _ -> }, {}, {})
        } }
        fun action(tag: String) = awaitOne(hasTestTag(tag) and isEnabled())
        action("course-options").performClick()
        action("course-add").performClick()
        action("manual-next").performClick()
        action("manual-title").performScrollTo().performTextReplacement("Saved in original term")
        action("manual-next").performClick()
        action("manual-preview").performClick()
        action("manual-save").performClick()
        try {
            ui.waitUntil(10_000) { written.isCompleted }
            ui.runOnIdle { owner.value = CourseSelection(other.id, 3) }
            ui.waitForIdle()
        } finally { receipt.complete(Unit) }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("manual-course-sheet").fetchSemanticsNodes().isEmpty() }
        awaitOne(hasTestTag("course-week") and hasText(other.id)).assertTextContains(ui.activity.getString(R.string.courses_week, 3))
        ui.runOnIdle { assertTrue(publications.isEmpty()) }
        val saved = runBlocking { store.api.observe(PlannerQuery.Week(first.id, 2)).first() as CourseWeek }
        assertEquals("Saved in original term", saved.classes.single().meeting.title)
    }

    @Test fun editorFollowsItsSavedWeekWhenItsOwnWriteReplacesTheCatalogFallback() {
        val monday = LocalDate.now(campusZone).plusMonths(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val first = TermSpec("Earlier future term", monday, 3, BellSchedule.WINTER)
        install(first, first.meeting(1, 1, "First room", "", 1, 2))
        val release = CompletableDeferred<Unit>()
        val delayed = object : PlannerApi by store.api {
            override suspend fun commit(proposalId: String): CommitResult {
                val result = store.api.commit(proposalId)
                release.await()
                return result
            }
        }
        val owner = mutableStateOf(CourseSelection())
        val scopes = mutableListOf<AssistantPage>()
        ui.setContent { StackLifeTheme {
            CoursesRoute(viewModel { WorkspaceViewModel(delayed) }, owner.value, { owner.value = it },
                {}, { _, _ -> }, {}, { if (scopes.lastOrNull() != it) scopes.add(it) })
        } }
        fun action(tag: String) = awaitOne(hasTestTag(tag) and isEnabled())
        action("course-options").performClick()
        action("course-add").performClick()
        action("manual-term:new").performScrollTo().performClick()
        ui.chooseDate("termMonday", monday.plusWeeks(6))
        ui.chooseNumber("termWeeks", 3)
        action("manual-next").performClick()
        action("manual-title").performScrollTo().performTextReplacement("Week three only")
        action("manual-next").performClick()
        action("manual-week:3").performScrollTo().performClick()
        action("manual-week:1").performScrollTo().performClick()
        action("manual-preview").performClick()
        action("manual-save").performClick()
        val next = try {
            ui.waitUntil(10_000) { (scopes.lastOrNull() as? AssistantPage.Week)?.termId?.let { it != first.id } == true }
            ui.runOnIdle { (scopes.last() as AssistantPage.Week).also { assertEquals(1, it.week) }.termId }
        } finally { release.complete(Unit) }
        awaitOne(hasTestTag("grid-course:0") and hasContentDescription("Week three only", substring = true)).assertIsDisplayed()
        ui.runOnIdle { assertEquals(CourseSelection(next, 3), owner.value) }
    }

    @Test fun arrivingWeekDoesNotReplaceAReaderOpenedFromItsStillVisibleNeighbor() {
        val term = TermSpec("Neighbor", LocalDate.of(2028, 2, 28), 2, BellSchedule.WINTER)
        install(term, term.meeting(1, 7, "Lab", "Sunday A", 1, 3), term.meeting(2, 1, "Lab", "Monday B", 1, 3))
        val owner = mutableStateOf(CourseSelection(term.id, 1))
        ui.setContent { StackLifeTheme {
            CoursesRoute(viewModel { WorkspaceViewModel(store.api) }, owner.value, { owner.value = it }, {}, { _, _ -> }, {}, {})
        } }
        val source = awaitOne(hasTestTag("grid-course:0")).fetchSemanticsNode()
        val openSource = source.config[SemanticsActions.OnClick].action!!
        val next = ui.onNodeWithTag("course-next").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { next() }
            ui.mainClock.advanceTimeBy(64)
            ui.runOnUiThread {
                val xy = source.layoutInfo.coordinates.localToRoot(Offset.Zero)
                assertTrue("Sunday tile must actually be exposed", source.layoutInfo.isPlaced && xy.x + source.size.width > 0f)
                openSource()
            }
            ui.mainClock.advanceTimeBy(1000)
        } finally { ui.mainClock.autoAdvance = true }
        awaitOne(hasText("Sunday A") and hasAnyAncestor(hasTestTag("course-detail"))).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(ui.activity.getString(R.string.courses_close)).performClick()
        awaitOne(hasTestTag("grid-course:0") and hasContentDescription("Monday B", substring = true)).assertIsDisplayed()
        ui.runOnIdle { assertEquals(CourseSelection(term.id, 2), owner.value) }
    }
}
