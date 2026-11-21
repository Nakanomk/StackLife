package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class LiquidDockGestureTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)

    private fun tab(name: String) = ui.onNodeWithTag("nav:$name")
    private fun point(name: String): Offset = tab(name).fetchSemanticsNode().boundsInRoot.center -
        ui.onNodeWithTag("navigation-capsule").fetchSemanticsNode().boundsInRoot.topLeft
    private fun launch(rtl: Boolean = false) {
        ui.setContent { CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
        } }
        ui.waitUntil(12_000) { ui.onAllNodesWithTag("nav:today").fetchSemanticsNodes().size == 1 }
    }

    @Test fun scrubChangesDestinationOnReleaseOnlyAndCancelledPullKeepsSelection() {
        launch()
        val dock = ui.onNodeWithTag("navigation-capsule")
        val today = point("today"); val workspace = point("workspace"); val graphs = point("graphs")
        dock.performTouchInput { down(today); moveTo(graphs, 160) }
        tab("today").assertIsSelected()
        dock.performTouchInput { cancel() }
        tab("today").assertIsSelected()
        dock.performTouchInput { down(today); moveTo(today + Offset(0f, -height.toFloat()), 140); up() }
        tab("today").assertIsSelected()
        dock.performTouchInput { down(today); moveTo(workspace, 180); up() }
        tab("workspace").assertIsSelected()
        ui.onNodeWithText("队列").assertIsDisplayed()
        val queue = ui.onNodeWithText("队列").fetchSemanticsNode().boundsInRoot.center
        val stack = ui.onNodeWithText("栈").fetchSemanticsNode().boundsInRoot.center
        ui.onNodeWithText("队列").performTouchInput { down(center); moveBy(stack - queue, 140); up() }
        ui.onNodeWithText("栈").assertIsSelected()
    }

    @Test fun solidRtlDragSelectsTheLogicalTabInReorderedDestinationList() {
        runBlocking { store.apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(Appearance(
            order = Destination.entries.reversed(), glass = false, start = Destination.TODAY)))) }
        launch(rtl = true)
        val from = point("today"); val to = point("courses")
        ui.onNodeWithTag("navigation-capsule").performTouchInput { down(from); moveTo(to, 240); up() }
        tab("courses").assertIsSelected()
        ui.onNodeWithTag("course-import").assertIsDisplayed()
    }
}
