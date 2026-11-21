package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import io.github.nakanomk.stacklife.today.TodayRoute
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixedCourseDragTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    private val date = LocalDate.of(2026, 11, 2)
    private val query = PlannerQuery.Day(date, campusZone)
    private fun at(h: Int, m: Int) = date.atTime(h, m).atZone(campusZone).toOffsetDateTime()
    private fun readDay(predicate: (DayPlan) -> Boolean = { true }) = runBlocking {
        withTimeout(8_000) { store.api.observe(query).first { predicate(it as DayPlan) } as DayPlan }
    }
    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(8_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return ui.onNode(matcher)
    }
    @Test fun collisionAtFifteenDoesNotMoveTaskButFifteenThirtyFiveDoes() {
        val term = TermSpec("秋季", date.minusWeeks(8), 18, BellSchedule.SUMMER, date)
        runBlocking {
            val id = store.api.stageCourseImport(term, listOf(term.meeting(9, 1, "操作系统", "B", 5, 6)))
            store.apply(CourseImport(id), TaskAdd(TaskInput("报告提纲", ref = "t")), SlotSet("@t", at(16, 0), 30))
            store.api.discardCourseImport(id)
        }
        val before = readDay(); val task = before.blocks.single()
        ui.setContent {
            val vm = viewModel { WorkspaceViewModel(store.api) }
            StackLifeTheme { TodayRoute(vm, date, campusZone, {}, {}, {}, null, {}, {}) }
        }
        val axis = await(hasTestTag("day-axis"))
        fun time(t: String) = ui.dayTick(t).fetchSemanticsNode().boundsInRoot.top
        ui.dayTick("15:30").performScrollTo()
        val area = ui.onNodeWithTag("day-drop-area").fetchSemanticsNode().boundsInRoot
        val tray = ui.onNodeWithTag("day-tray-container").fetchSemanticsNode().boundsInRoot
        val floor = if (area.center.x in tray.left..tray.right) minOf(area.bottom, tray.top) else area.bottom
        val shift = time("15:30") - (area.top + floor) / 2
        axis.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, shift) }; ui.waitForIdle()
        val root = ui.onRoot()
        fun drag(y: Float) {
            val box = ui.onNodeWithTag("block:${task.taskId}").fetchSemanticsNode().boundsInRoot
            val down = Offset(box.center.x, box.top + 5f); val to = Offset(box.center.x, y + 5f)
            root.performTouchInput { down(down); advanceEventTime(700); moveTo(down + Offset(0f, 1f)) }
            root.performTouchInput { for (i in 1..8) moveTo(down + (to - down) * (i / 8f), delayMillis = 16) }
        }
        assertEquals("查看固定课程", ui.onNodeWithTag("day-course").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        drag(time("15:00"))
        await(hasText("时段冲突：", substring = true) and hasText("操作系统", substring = true)).assertIsDisplayed()
        root.performTouchInput { up() }; await(hasText("知道了")).performClick(); assertEquals(before, readDay())
        drag(time("15:30") + (time("15:45") - time("15:30")) / 3f)
        await(hasText("15:35–16:05 · 松手安排")).assertIsDisplayed()
        root.performTouchInput { up() }
        val placed = readDay { it.blocks.single().startMillis == at(15, 35).toInstant().toEpochMilli() }
        assertEquals(before.courses, placed.courses); assertEquals(task.taskId, placed.blocks.single().taskId)
        assertTrue(placed.conflictingTaskIds.isEmpty())
    }
}
