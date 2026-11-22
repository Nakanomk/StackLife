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
        try { ui.waitUntil(12_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().size == 1 } }
        catch (failure: ComposeTimeoutException) { throw AssertionError("$matcher\n${ui.onRoot().printToString()}", failure) }
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
        ui.onNodeWithText("保存外观").performClick(); visible(hasText("材质与动态") and hasClickAction())
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
        editor("色彩"); field("恢复默认设置").performClick(); ui.onNodeWithText("保存外观").performClick(); visible(hasText("色彩") and hasClickAction())
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
        val toggle = visible(hasTestTag("day-tray-toggle")).assert(hasStateDescription("已展开"))
        fun assertSafeTargets() {
            val header = visible(hasContentDescription("设置") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            val axis = visible(hasTestTag("day-drop-area")).fetchSemanticsNode().boundsInRoot
            for (tag in listOf("day-tray-toggle", "add-task")) {
                val button = visible(hasTestTag(tag)).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                val target = button.fetchSemanticsNode().boundsInRoot
                assertFalse("$tag overlaps Settings: $target, $header", target.overlaps(header))
                assertTrue("$tag escapes the touchable day viewport: $target, $axis",
                    target.top >= axis.top - 1f && target.bottom <= axis.bottom + 1f)
            }
        }
        assertSafeTargets()
        ui.assertFloatingAxisMeetsDock()
        toggle.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performTouchInput { click() }
        visible(hasTestTag("day-tray-toggle") and hasStateDescription("已收起")).assertIsDisplayed()
        assertSafeTargets()
        ui.assertFloatingAxisMeetsDock()
        ui.onNodeWithTag("day-tray-toggle").performTouchInput { click() }
        val tray = visible(hasTestTag("tray:$id"))
        val fab = ui.onNodeWithTag("add-task").fetchSemanticsNode().boundsInRoot
        assertFalse("新增任务遮住了待排任务的时间操作", fab.overlaps(tray.fetchSemanticsNode().boundsInRoot))
        val panel = ui.onNodeWithTag("day-tray-container").fetchSemanticsNode().boundsInRoot
        assertFalse(panel.overlaps(fab))
        assertFalse(panel.overlaps(ui.onNodeWithTag("assistant-composer").fetchSemanticsNode().boundsInRoot))
        assertFalse(panel.overlaps(ui.onNodeWithTag("navigation-capsule").fetchSemanticsNode().boundsInRoot))
        val exposed = tray.fetchSemanticsNode().boundsInRoot.intersect(panel)
        assertFalse("The queue toggle occludes its first task", exposed.overlaps(toggle.fetchSemanticsNode().boundsInRoot))
        val minimum = with(tray.fetchSemanticsNode().layoutInfo.density) { 48.dp.toPx() }
        assertTrue("Visible scheduling target: $exposed, tray $panel", exposed.width + 1 >= minimum && exposed.height + 1 >= minimum)
        ui.onRoot().performTouchInput { click(exposed.center) }
        visible(hasTestTag("slot:time")).assertExists()
    }

    @Test fun reorderAndHideKeepTheDestinationAndRootDraftAndHiddenFeatureReturnsToSettings() = runBlocking {
        val initial = Appearance(start = Destination.WORKSPACE)
        store.apply(PlannerCommand.TaskAdd(TaskInput("独立入口的原任务")), PlannerCommand.EditAppearance(AppearancePatch.Replace(initial)))
        show()
        visible(hasText("独立入口的原任务")).assertIsDisplayed()
        visible(hasTestTag("assistant-input")).performTextInput("未发送的跨页草稿")
        val reordered = initial.copy(order = listOf(Destination.GRAPHS,Destination.WORKSPACE,Destination.COUNTDOWN,Destination.TODAY,Destination.COURSES))
        store.apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(reordered)))
        visible(hasTestTag("nav:workspace") and isSelected()).assertIsDisplayed()
        val hidden = reordered.withVisible(setOf(Destination.COUNTDOWN))
        store.apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(hidden)))
        visible(hasTestTag("nav:countdown") and isSelected()).assertIsDisplayed(); visible(hasTestTag("countdown-add"))
        ui.onNodeWithTag("nav:workspace").assertDoesNotExist()
        visible(hasTestTag("assistant-input")).assertTextContains("未发送的跨页草稿")
        visible(hasContentDescription("设置") and hasClickAction()).performClick()
        val jump = hasTestTag("open-feature:workspace")
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(jump); visible(jump).performClick()
        visible(hasText("独立入口的原任务")).assertIsDisplayed(); ui.onNodeWithTag("navigation-capsule").assertDoesNotExist()
        visible(hasTestTag("assistant-input")).assertTextContains("未发送的跨页草稿")
        visible(hasContentDescription("返回") and hasClickAction()).performClick()
        visible(jump).assertIsDisplayed(); assertEquals(hidden, preferences().appearance)
    }

    @Test fun groupedResetKeepsOtherGroupsAndConcurrentEditCannotOverwriteThem() = runBlocking {
        val a = Appearance(glass = true, compact = true, palette = PaletteMode.SEED, seed = "#BB4411")
        store.apply(PlannerCommand.SetRestorePolicy(RestorePolicy.APPEND), PlannerCommand.EditAppearance(AppearancePatch.Replace(a)))
        val update = PlannerCommand.EditAppearance(AppearanceFields(AppearanceGroup.COLOR, null))
        val proposed = store.proposal(update)
        val change = proposed.changes.single()
        assertEquals(AppearanceSnapshot(a), change.before)
        assertEquals(AppearanceSnapshot(a.copy(palette = Appearance().palette, seed = Appearance().seed)), change.after)
        assertEquals(a, preferences().appearance)
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
