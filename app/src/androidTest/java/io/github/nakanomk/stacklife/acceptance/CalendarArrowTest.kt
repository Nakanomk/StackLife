package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.PlannerCommand
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
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
}
