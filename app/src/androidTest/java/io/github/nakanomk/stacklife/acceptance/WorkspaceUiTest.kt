package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
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
        await(hasText("待办队列"))
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

    @Test fun pushSelectBThenResumeAThroughTheWorkbench() {
        runBlocking { store.apply(PlannerCommand.TaskAdd(TaskInput("A")), PlannerCommand.TaskAdd(TaskInput("B"))) }
        show()
        ui.onNodeWithText("压栈").performClick()
        ui.onNodeWithTag("reason").performTextInput("去开会")
        ui.onNodeWithText("保存到栈").performClick()
        await(hasText("队列是空的"))
        ui.onNodeWithText("新增任务").performClick()
        ui.onNodeWithTag("title:0").performTextInput("X")
        val add = hasText("添加到队列") and hasClickAction()
        scrollTo(add)
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
        await(hasText("新增任务") and hasClickAction()).performClick()
        ui.onNodeWithTag("title:0").performTextInput("只创建一次")
        val add = hasText("添加到队列")
        scrollTo(add)

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
            scrollTo(add)
            ui.onNode(add).assertIsNotEnabled()
        } finally {
            release.complete(Unit)
            writer.join()
        }
        await(hasText("待办队列")).assertIsDisplayed()
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
