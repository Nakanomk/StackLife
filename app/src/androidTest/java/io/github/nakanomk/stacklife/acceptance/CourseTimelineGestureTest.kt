package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.CourseWeek
import io.github.nakanomk.stacklife.planner.PlannerCommand
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseTimelineGestureTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)

    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(10_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
        return ui.onNode(matcher)
    }

    @Test fun whiteCourseNameRoomAndConflictHaveLocalContrastOnABrightTile() {
        val term = TermSpec("Studio-7", LocalDate.of(2026, 11, 16), 1, BellSchedule.SUMMER)
        val course = ScheduledCourse(term.id, HUST_SOURCE, term.meeting(1, 1, "物理", "A", 1, 2), listOf("预约"))
        val week = CourseWeek(term, 1, listOf(course), mapOf("物理" to 0))
        ui.setContent { MaterialTheme { WeekGrid(term, week, 0.dp, 0.dp) {} } }
        for (label in listOf("物理", "@A", "冲突")) {
            val pixels = ui.onNodeWithText(label, useUnmergedTree = true).captureToImage().toPixelMap()
            var edges = 0
            for (y in 0 until pixels.height) for (x in 2 until pixels.width - 2) {
                val ink = pixels[x, y]
                if (minOf(ink.red, ink.green, ink.blue) > .98f &&
                    minOf(pixels[x - 2, y].luminance(), pixels[x + 2, y].luminance()) < .16f) edges++
            }
            assertTrue("White strokes in $label must border a contrasting edge", edges > 0)
        }
    }

    @Test fun swipeSelectsOnlyAnAdjacentWeekAndTheCourseOpensItsActualOccurrence() {
        // Future term starts on page 1 regardless of the current week or device zone.
        val monday = LocalDate.now(campusZone).plusYears(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val term = TermSpec("测试学期", monday, 2, BellSchedule.WINTER)
        val meetings = listOf(term.meeting(1, 1, "实验", "A", 1, 2), term.meeting(2, 7, "实验", "B", 1, 2))
        runBlocking {
            val id = store.api.stageCourseImport(term, meetings)
            try { store.apply(PlannerCommand.CourseImport(id)) } finally { store.api.discardCourseImport(id) }
        }
        val revision = runBlocking { store.state().revision }
        ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        await(hasTestTag("nav:courses")).performClick()
        val week = hasTestTag("course-week")
        val grid = hasTestTag("week-grid")
        await(week and hasText("第 1 周")).assertIsDisplayed()
        ui.onNodeWithTag("course-prev").assertIsNotEnabled()
        await(grid).performTouchInput { swipeLeft() }
        await(week and hasText("第 2 周")).assertIsDisplayed()
        ui.onNodeWithTag("course-next").assertIsNotEnabled()
        await(grid).performTouchInput { swipeLeft() }
        ui.onNode(week).assertTextContains("第 2 周")
        await(hasTestTag("grid-course:0") and hasContentDescription("周日", substring = true)).performClick()
        await(hasTestTag("course-detail")).assertIsDisplayed()
        ui.onNodeWithText("教室：B").assertIsDisplayed()
        ui.onNodeWithText("关闭").performClick()
        await(hasTestTag("nav:today")).performClick()
        await(hasTestTag("nav:courses")).performClick()
        await(week and hasText("第 2 周")).assertIsDisplayed()
        await(grid).performTouchInput { swipeRight() }
        await(week and hasText("第 1 周")).assertIsDisplayed()
        ui.onNodeWithContentDescription("课表选项").performClick()
        ui.onNodeWithTag("course-view-toggle").performClick()
        await(hasTestTag("week-course:0")).performClick()
        ui.onNode(hasText("教室：A") and hasAnyAncestor(hasTestTag("course-detail"))).assertIsDisplayed()
        assertEquals(revision, runBlocking { store.state().revision })
    }
}
