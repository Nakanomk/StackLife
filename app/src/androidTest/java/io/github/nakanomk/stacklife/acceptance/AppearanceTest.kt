package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    private suspend fun preferences() = store.api.observe(PlannerQuery.Settings).first() as PolicyView
    private fun show() = ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
    private fun visible(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(timeoutMillis = 12_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
        return ui.onNode(matcher)
    }
    private fun editor(label: String) { visible(hasContentDescription("设置") and hasClickAction()).performClick(); visible(hasText(label) and hasClickAction()).performClick() }
    private fun field(label: String): SemanticsNodeInteraction {
        val node = hasText(label) and hasClickAction()
        ui.onNodeWithTag("appearance-scroll").performScrollToNode(node)
        return ui.onNode(node).performScrollTo().assertIsDisplayed()
    }

    @Test fun previewCanBeCancelledAndReducedTransparencyAlwaysWins() {
        runBlocking { store.apply(PlannerCommand.SetRestorePolicy(RestorePolicy.SWAP)) }
        show(); editor("材质与动态")
        ui.onNodeWithText("设置分组").assertDoesNotExist()
        ui.onNodeWithText("色彩来源").assertDoesNotExist()
        field("启用液态玻璃").assertIsOn().performClick(); field("减少透明度").performClick()
        assertTrue(runBlocking { preferences().appearance.glass })
        ui.onNodeWithText("取消").performClick(); visible(hasText("材质与动态") and hasClickAction()).performClick()
        field("启用液态玻璃").assertIsOn(); field("减少透明度").assertIsOff().performClick()
        ui.onNodeWithText("保存外观").performClick(); visible(hasText("模型服务配置"))
        val saved = runBlocking { preferences() }
        assertTrue(saved.appearance.glass && saved.appearance.reduceTransparency); assertEquals(RestorePolicy.SWAP, saved.policy)
        ui.onNodeWithContentDescription("返回").performClick()
        visible(hasTestTag("navigation-capsule")).assert(hasStateDescription("实色胶囊"))
    }

    @Test fun startupOrderAndDockUsePersistedSettingsNotTheDefaultFirstFrame() {
        val layout = Appearance(navStyle = NavStyle.DOCKED, order = Destination.entries.reversed(), start = Destination.WORKSPACE,
            theme = ThemeMode.DARK, palette = PaletteMode.SEED, seed = "#CB3054", showDuration = false, showNotes = false)
        runBlocking { store.apply(PlannerCommand.TaskAdd(TaskInput("持久队列", "备注不应显示", 23)), PlannerCommand.EditAppearance(AppearancePatch.Replace(layout))) }
        store.reopen(); show()
        val task = visible(hasText("持久队列")); task.assertIsDisplayed(); ui.onNodeWithText("备注不应显示").assertDoesNotExist()
        ui.onNodeWithText("23 分钟").assertDoesNotExist()
        assertTrue(ui.onNodeWithTag("nav:courses").fetchSemanticsNode().boundsInRoot.left < ui.onNodeWithTag("nav:today").fetchSemanticsNode().boundsInRoot.left)
        task.performClick(); visible(hasText("任务详情")).assertIsDisplayed(); ui.onNodeWithContentDescription("返回").performClick(); visible(hasText("持久队列"))
        editor("色彩"); field("恢复默认设置").performClick(); ui.onNodeWithText("保存外观").performClick(); visible(hasText("模型服务配置"))
        assertEquals(layout.copy(theme = ThemeMode.SYSTEM, palette = PaletteMode.DYNAMIC, seed = Appearance().seed), runBlocking { preferences().appearance })
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun largeLandscapePlannerActionsNeverCoverOneAnother() {
        val id = runBlocking {
            store.apply(PlannerCommand.TaskAdd(TaskInput("待排任务")), PlannerCommand.EditAppearance(
                AppearancePatch.Replace(Appearance(navStyle = NavStyle.DOCKED, compact = true))))
            store.state().queue.single().id
        }
        ui.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(780.dp, 360.dp)) then DeviceConfigurationOverride.FontScale(2f)) {
                StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
            }
        }
        val toggle = visible(hasTestTag("day-tray-toggle")).assert(hasStateDescription("已收起"))
        toggle.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performTouchInput { click() }
        val tray = visible(hasTestTag("tray:$id"))
        val fab = ui.onNodeWithTag("add-task").fetchSemanticsNode().boundsInRoot
        assertFalse("新增任务遮住了待排任务的时间操作", fab.overlaps(tray.fetchSemanticsNode().boundsInRoot))
        val panel = ui.onNodeWithTag("day-tray-container").fetchSemanticsNode().boundsInRoot
        assertFalse(panel.overlaps(fab))
        assertFalse(panel.overlaps(ui.onNodeWithTag("assistant-input").fetchSemanticsNode().boundsInRoot))
        assertFalse(panel.overlaps(ui.onNodeWithTag("navigation-capsule").fetchSemanticsNode().boundsInRoot))
        tray.performTouchInput { click() }
        visible(hasTestTag("slot:hour")).assertExists()
    }

    @Test fun groupedResetKeepsOtherGroupsAndConcurrentEditCannotOverwriteThem() = runBlocking {
        val a = Appearance(glass = true, compact = true, palette = PaletteMode.SEED, seed = "#BB4411")
        store.apply(PlannerCommand.SetRestorePolicy(RestorePolicy.APPEND), PlannerCommand.EditAppearance(AppearancePatch.Replace(a)))
        val update = PlannerCommand.EditAppearance(AppearanceFields(AppearanceGroup.COLOR, null))
        val proposed = store.proposal(update)
        assertEquals(setOf("色彩来源", "种子色"), proposed.changes.map { it.field }.toSet()); assertEquals(a, preferences().appearance)
        assertTrue(store.api.commit(proposed.proposalId) is CommitResult.Applied)
        assertTrue(store.api.preview(listOf(PlannerCommand.EditAppearance(AppearancePatch.Replace(a.copy(haptics = false)), expected = a))) is PreviewResult.Rejected)
        store.reopen()
        assertEquals(PolicyView(RestorePolicy.APPEND, Appearance(compact = true, glass = true)), preferences())
        val snapshot = preferences().appearance
        store.apply(PlannerCommand.EditAppearance(AppearanceFields(AppearanceGroup.MATERIAL,
            kotlinx.serialization.json.JsonObject(mapOf("glass" to kotlinx.serialization.json.JsonPrimitive(false))))))
        store.apply(PlannerCommand.EditAppearance(AppearanceFields(AppearanceGroup.LAYOUT, null), expected = snapshot))
        assertFalse(preferences().appearance.compact)
        assertFalse(preferences().appearance.glass)
    }
}
