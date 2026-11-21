package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceUiTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)

    private fun show() {
        ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        await(hasTestTag("nav:workspace")).performClick()
        await(hasTestTag("queue"))
    }

    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(timeoutMillis = 10_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return ui.onNode(matcher)
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(matcher)
    }

    private fun awaitQueue(vararg titles: String) = runBlocking {
        withTimeout(8_000) {
            store.api.observe(PlannerQuery.Workspace).first { result ->
                (result as Workspace).queue.map { it.title } == titles.toList()
            } as Workspace
        }
    }

    @Test fun addTaskFloatsAboveCenteredNavigationOnTodayAndInTheQueue() {
        ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        fun checkPlacement() {
            val fab = await(hasTestTag("add-task")).fetchSemanticsNode().boundsInRoot
            val nav = ui.onNodeWithTag("navigation-capsule").fetchSemanticsNode().boundsInRoot
            val compose = ui.onNodeWithTag("assistant-input").fetchSemanticsNode().boundsInRoot
            assertTrue("Add and the assistant must have independent targets", fab.bottom < compose.top)
            assertTrue("Composer is above destination navigation", compose.bottom <= nav.top)
            val page = ui.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Add belongs above navigation, not alongside it", fab.bottom < nav.top)
            assertTrue("Add belongs on the right of the page", fab.left > page.center.x)
            assertEquals("A page action must not displace navigation", page.center.x, nav.center.x, 1f)
        }
        checkPlacement()
        await(hasTestTag("day-tray-toggle") and hasStateDescription("已展开")).performTouchInput { click() }
        val ball = await(hasTestTag("day-tray-toggle") and hasStateDescription("已收起")).fetchSemanticsNode().boundsInRoot
        val add = ui.onNodeWithTag("add-task").fetchSemanticsNode().boundsInRoot
        assertEquals(add.center.x, ball.center.x, 1f)
        assertTrue(ball.top > add.bottom && ball.bottom < ui.onNodeWithTag("assistant-input").fetchSemanticsNode().boundsInRoot.top)
        ui.onNodeWithTag("add-task").performTouchInput { click() }
        await(hasTestTag("title:0")).performTextInput("从时间轴创建")
        val save = hasText("添加到队列") and hasClickAction()
        ui.onNode(save).assertIsDisplayed()
        ui.onNode(save).performClick()
        val task = awaitQueue("从时间轴创建").queue.single()
        await(hasTestTag("day-tray-toggle") and hasStateDescription("已收起")).performClick()
        await(hasTestTag("tray:${task.id}")).assertIsDisplayed()
        ui.onNodeWithTag("nav:workspace").performClick()
        await(hasTestTag("task:${task.id}")).assertIsDisplayed()
        checkPlacement()
        ui.onNodeWithTag("nav:courses").performClick()
        await(hasTestTag("course-import")).assertIsDisplayed()
        ui.onNodeWithTag("add-task").assertDoesNotExist()
    }

    @Test fun keyboardTraversalVisitsTheVisibleWorkbenchAndSharedDockNotHiddenRoots() {
        show()
        val fab = ui.onNodeWithTag("add-task")
        for (backwards in listOf(false, true)) {
            fab.performSemanticsAction(SemanticsActions.RequestFocus)
            var foundDock = false
            for (i in 0..24) {
                ui.onRoot().performKeyInput {
                    if (backwards) withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } else pressKey(Key.Tab)
                }
                val focused = ui.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
                assertTrue("Keyboard focus left the visible semantics tree", focused.isNotEmpty())
                for (node in focused) {
                    val bounds = node.boundsInRoot
                    assertTrue("Off-screen keyboard target: ${node.id}", bounds.width > 0 && bounds.height > 0)
                    val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
                    if (tag.startsWith("nav:") || tag == "assistant-input") foundDock = true
                }
                ui.onNodeWithTag("queue").assertIsDisplayed()
                if (foundDock) break
            }
            assertTrue("Keyboard must reach the shared dock in either direction", foundDock)
        }
    }

    @Test fun pushSelectBThenResumeAThroughTheWorkbench() {
        runBlocking { store.apply(PlannerCommand.TaskAdd(TaskInput("A")), PlannerCommand.TaskAdd(TaskInput("B"))) }
        show()
        ui.onNodeWithText("压栈").performClick()
        ui.onNodeWithTag("reason").performTextInput("去开会")
        ui.onNodeWithText("保存到栈").performClick()
        await(hasText("队列是空的"))
        ui.onNodeWithContentDescription("新增任务").performClick()
        ui.onNodeWithTag("title:0").performTextInput("X")
        val add = hasText("添加到队列") and hasClickAction()
        ui.onNode(add).assertIsDisplayed()
        ui.onNode(add).performClick()
        val interrupted = awaitQueue("X")
        await(hasText("栈") and hasClickAction()).performClick()
        ui.onNodeWithTag("frame:${interrupted.frames.single().id}").performClick()
        ui.onNodeWithText("选择恢复").performClick()
        val selectB = hasText("B") and isToggleable()
        scrollTo(selectB)
        ui.onNode(selectB).performClick()
        ui.onNodeWithText("恢复所选").performClick()
        val partial = awaitQueue("B", "X")
        assertEquals(listOf(true, false), partial.frames.single().items.map { it.restorable })
        ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodesWithText("恢复所选").fetchSemanticsNodes().isEmpty() }
        val all = hasText("全部恢复") and hasClickAction()
        scrollTo(all)
        ui.onNode(all).performClick()
        val restored = awaitQueue("A", "B", "X")
        assertEquals(interrupted.frames.single().items.map { it.taskId } + interrupted.queue.single().id, restored.queue.map { it.id })
        assertEquals(0, restored.frames.single().remaining)
    }

    @Test fun recreatingDuringSaveConsumesTheOriginalReceipt() = runBlocking {
        // Same real Activity, ViewModel owner and saved-state call site before and after recreation.
        val scenario = ui.activityRule.scenario
        fun attach() = scenario.onActivity { activity ->
            activity.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        }
        attach()
        await(hasTestTag("nav:workspace")).performClick()
        await(hasContentDescription("新增任务") and hasClickAction()).performClick()
        ui.onNodeWithTag("title:0").performTextInput("只创建一次")
        val add = hasText("添加到队列")
        ui.onNode(add).assertIsDisplayed()

        // Hold the actual Room writer until the new Activity has restored the in-flight form.
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = launch(Dispatchers.IO) {
            store.db.withTransaction { locked.complete(Unit); release.await() }
        }
        try {
            withTimeout(5_000) { locked.await() }
            ui.onNode(add).performClick()
            ui.onNode(add).assertIsNotEnabled()
            scenario.recreate()
            attach()
            await(hasTestTag("title:0")).assertTextContains("只创建一次")
            ui.onNode(add).assertIsDisplayed().assertIsNotEnabled()
        } finally {
            release.complete(Unit)
            writer.join()
        }
        await(hasTestTag("queue")).assertIsDisplayed()
        assertEquals(listOf("只创建一次"), store.state().queue.map { it.title })
        ui.onNodeWithTag("title:0").assertDoesNotExist()
    }

    @Test fun draggingAndMenuMoveTheOriginalTasks() {
        runBlocking { store.apply(PlannerCommand.TaskAdd(TaskInput("A")), PlannerCommand.TaskAdd(TaskInput("B"))) }
        val original = runBlocking { store.state().queue }
        show()
        val handle = ui.onNodeWithContentDescription("移动A，长按拖动或点按选择位置")
        val start = handle.fetchSemanticsNode().boundsInRoot.center
        val target = ui.onNodeWithTag("task:${original.last().id}").fetchSemanticsNode().boundsInRoot.center
        val root = ui.onRoot()
        root.performTouchInput { down(start); advanceEventTime(700); moveTo(start + Offset(0f, 1f)) }
        root.performTouchInput {
            for (i in 1..12) moveTo(Offset(start.x, start.y + (target.y - start.y + 16f) * i / 12), delayMillis = 32)
        }
        assertEquals(original, runBlocking { store.state().queue })
        root.performTouchInput { up() }
        assertEquals(original.reversed(), awaitQueue("B", "A").queue)
        ui.onNodeWithContentDescription("移动A，长按拖动或点按选择位置").performClick()
        ui.onNodeWithText("移到队首").performClick()
        assertEquals(original, awaitQueue("A", "B").queue)
    }
}
