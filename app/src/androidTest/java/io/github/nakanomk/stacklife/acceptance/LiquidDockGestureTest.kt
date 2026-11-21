package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
    @OptIn(ExperimentalTestApi::class)
    private fun launch(rtl: Boolean = false, fontScale: Float = 1f) {
        ui.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
                }
            }
        }
        ui.waitUntil(12_000) { ui.onAllNodesWithTag("nav:today").fetchSemanticsNodes().size == 1 }
    }

    @Test fun floatingDockRestoresCompactShellAndFullSizeTargets() {
        launch()
        // Padding rounds independently on hardware with fractional density.
        val shell = with(ui.density) { (48.dp.roundToPx() + 2 * 4.dp.roundToPx()).toDp() }
        ui.onNodeWithTag("navigation-capsule").assertHeightIsEqualTo(shell)
        for (destination in Destination.entries) {
            tab(destination.key).assertHeightIsEqualTo(48.dp).assertWidthIsAtLeast(48.dp)
        }
    }

    @Test fun largeFontGrowsFloatingDockWithoutDuplicatingLabelsOrLosingTargets() {
        launch(fontScale = 2f)
        ui.onNodeWithTag("navigation-capsule").assertHeightIsAtLeast(57.dp)
        for (destination in Destination.entries) {
            tab(destination.key).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            ui.onAllNodes(hasText(destination.label) and hasAnyAncestor(hasTestTag("nav:${destination.key}")),
                useUnmergedTree = true).assertCountEquals(1)
        }
        tab("courses").performClick().assertIsSelected()
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
        dock.performTouchInput { down(workspace); moveTo(workspace + Offset(0f, height * .34f), 140); up() }
        tab("today").assertIsSelected()
        dock.performTouchInput { down(today); moveTo(workspace, 180); up() }
        tab("workspace").assertIsSelected()
        ui.onNodeWithText("队列").assertIsDisplayed()
        val queue = ui.onNodeWithText("队列").fetchSemanticsNode().boundsInRoot.center
        val stack = ui.onNodeWithText("栈").fetchSemanticsNode().boundsInRoot.center
        ui.onNodeWithText("队列").performTouchInput { down(center); moveBy(stack - queue, 140); up() }
        ui.onNodeWithText("栈").assertIsSelected()
        dock.performTouchInput { down(workspace); moveTo(today, 180); up() }
        tab("today").assertIsSelected()
    }

    @Test fun solidRtlDragSelectsTheLogicalTabInReorderedDestinationList() {
        runBlocking { store.apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(Appearance(
            order = Destination.entries.reversed(), glass = false, reduceMotion = true, start = Destination.TODAY)))) }
        launch(rtl = true)
        val from = point("today"); val to = point("courses")
        ui.onNodeWithTag("navigation-capsule").performTouchInput { down(from); moveTo(to, 240); up() }
        tab("courses").assertIsSelected()
        ui.onNodeWithTag("course-import").assertIsDisplayed()
    }
}
