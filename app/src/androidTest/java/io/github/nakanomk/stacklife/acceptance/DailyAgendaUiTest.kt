package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DailyAgendaUiTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    private val query = PlannerQuery.Day(LocalDate.now(), ZoneId.systemDefault())
    private fun show() { ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) } }
    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(8_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return ui.onNode(matcher)
    }
    private fun day() = runBlocking { store.api.observe(query).first() as DayPlan }
    @Test fun checkboxDoesNotLaunchEditorAndRoutineChangesAreVisibleAcrossDates() {
        val start = query.date.atTime(10,0).atZone(query.zone).toOffsetDateTime()
        runBlocking { store.apply(TaskAdd(TaskInput("校对", ref = "t")), once("@t", start, 30)) }
        show()
        val checkbox = await(hasContentDescription("将校对标记为已完成")).performScrollTo()
        val viewport = ui.onNodeWithTag("day-drop-area").fetchSemanticsNode().boundsInRoot
        val tray = ui.onNodeWithTag("day-tray-container").fetchSemanticsNode().boundsInRoot
        val shift = checkbox.fetchSemanticsNode().boundsInRoot.center.y - (viewport.top + minOf(tray.top, viewport.bottom)) / 2f
        ui.onNodeWithTag("day-axis").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, shift) }
        checkbox.assertIsOff().performTouchInput { click() }
        await(hasContentDescription("将校对标记为未完成")).assertIsOn(); assertEquals(Owner.DONE, day().occurrences.single().owner)
        ui.onNodeWithTag("day-view").performClick(); ui.onNodeWithTag("day-view-events").performClick()
        await(hasContentDescription("将校对标记为未完成")).performScrollTo().performClick()
        await(hasContentDescription("将校对标记为已完成")).assertIsOff(); assertEquals(Owner.QUEUE, day().occurrences.single().owner)
        ui.onNode(hasContentDescription("设置") and hasClickAction()).performClick()
        fun setRole(tag: String, hour: Int, minute: Int) {
            await(hasTestTag(tag)).performScrollTo().performClick()
            await(hasTestTag("slot:time")); ui.chooseClock("slot:time", hour, minute)
            ui.onNodeWithTag("slot:length").assertTextContains("单点事件")
            await(hasText("保存时段") and hasClickAction() and isEnabled()).performScrollTo().performClick()
            await(hasTestTag(tag))
        }
        setRole("routine-wake", 6, 25); setRole("routine-sleep", 23, 0)
        val back = ui.activity.onBackPressedDispatcher
        ui.runOnIdle { back.onBackPressed() }
        val wake = hasContentDescription(ui.activity.getString(io.github.nakanomk.stacklife.R.string.slot_complete_this, "起床"))
        await(wake).performScrollTo().performClick(); await(hasContentDescription("将起床标记为未完成")).assertIsOn()
        val routine = day().occurrences.single { it.role == RoutineKind.WAKE }
        assertTrue(routine.completed); assertEquals(query.date.atTime(6,25).atZone(query.zone).toInstant().toEpochMilli(), routine.startMillis)
        ui.onNodeWithContentDescription("后一天").performClick(); await(wake).assertIsOff()
        ui.onNode(hasContentDescription("设置") and hasClickAction()).performClick(); await(hasTestTag("routine-wake")).performClick()
        await(hasText("停用整个系列") and hasClickAction()).performScrollTo().performClick()
        await(hasTestTag("routine-wake")); ui.runOnIdle { back.onBackPressed() }
        ui.waitUntil(8_000) { day().occurrences.mapNotNull { it.role } == listOf(RoutineKind.SLEEP) }
    }
    @Test fun deletingFromDetailsThenClearingHistoryLeavesTasksAndLiveFrameUntouched() {
        runBlocking {
            store.apply(KeepRestoredFrames(true))
            store.apply(FrameCreate("已恢复甲", listOf(TaskInput("写提纲")), "first"), FrameRestore("@first", RestoreMode.ALL),
                FrameCreate("已恢复乙", listOf(TaskInput("校对")), "second"), FrameRestore("@second", RestoreMode.ALL),
                FrameCreate("保留", listOf(TaskInput("新任务"))))
        }
        show(); await(hasText("工作台") and hasClickAction()).performClick(); ui.onNodeWithText("栈").performClick()
        val known = runBlocking { store.state() }; val first = known.frames.single { it.reason == "已恢复甲" }
        await(hasTestTag("frame:${first.id}")).performScrollTo().performClick(); await(hasTestTag("delete-frame-history")).performClick()
        ui.onNodeWithText("取消").performClick(); assertEquals(known, runBlocking { store.state() })
        ui.onNodeWithTag("delete-frame-history").performClick(); ui.onNodeWithTag("confirm-frame-deletion").performClick()
        await(hasTestTag("clear-frame-history")).performScrollTo().performClick(); ui.onNodeWithTag("confirm-frame-deletion").performClick()
        ui.waitUntil(8_000) { runBlocking { store.state().frames.size } == 1 }
        val actual = runBlocking { store.state() }; assertEquals(known.frames.single { it.reason == "保留" }, actual.frames.single())
        assertEquals(known.queue, actual.queue)
    }
}
