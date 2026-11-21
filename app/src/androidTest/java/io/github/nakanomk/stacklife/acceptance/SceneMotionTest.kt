package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneMotionTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    @OptIn(ExperimentalTestApi::class)
    private fun show(vararg commands: PlannerCommand, reduced: Boolean = false, fontScale: Float? = null) {
        runBlocking {
            store.apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(
                Appearance(start = Destination.WORKSPACE, reduceMotion = reduced))), *commands)
        }
        ui.setContent {
            val model = viewModel { WorkspaceViewModel(store.api) }
            if (fontScale == null) StackLifeApp(model) else
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) { StackLifeApp(model) }
        }
        ui.waitUntil(12_000) { ui.onAllNodesWithTag("queue").fetchSemanticsNodes().size == 1 }
    }

    private fun bounds(tag: String) = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun x(node: SemanticsNode) = ui.runOnIdle {
        assertTrue("The displaced scene must still be placed", node.layoutInfo.isPlaced && node.layoutInfo.coordinates.isAttached)
        node.layoutInfo.coordinates.localToRoot(Offset.Zero).x
    }
    private fun click(node: SemanticsNodeInteraction) {
        val action = node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        ui.runOnUiThread { action() }
    }
    private fun withClockAndBack(body: (DirectNavigationEventInput) -> Unit) {
        val input = DirectNavigationEventInput()
        ui.runOnIdle { ui.activity.navigationEventDispatcher.addInput(input) }
        ui.mainClock.autoAdvance = false
        try { body(input) } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnUiThread { ui.activity.navigationEventDispatcher.removeInput(input) }
        }
    }
    private fun event(progress: Float) = NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = progress)
    private fun start(input: DirectNavigationEventInput) {
        ui.runOnUiThread { input.backStarted(event(0f)) }
        ui.mainClock.advanceTimeBy(64)
    }
    private fun seek(input: DirectNavigationEventInput, fraction: Float) {
        ui.runOnUiThread { input.backProgressed(event(fraction)) }
        ui.mainClock.advanceTimeBy(64)
    }
    private fun complete(input: DirectNavigationEventInput) {
        ui.runOnUiThread { input.backCompleted() }
        ui.mainClock.advanceTimeBy(600)
    }

    private fun uncut(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().let {
        Rect(it.positionInRoot, it.size.toSize())
    }
    private fun backHit() = uncut(ui.onNode(hasContentDescription("返回") and hasClickAction()))
    private fun clearOfBack(title: Rect, back: Rect, settledBack: Rect) {
        assertEquals("The detail toolbar must not descend with the contracting card", settledBack.top, back.top, 1f)
        assertTrue("The actual shared title must clear the Back target: $title / $back", !title.overlaps(back))
        assertTrue("Do not shrink Back to avoid the title", back.height >= 48f * ui.density.density - 1f)
    }

    private fun title(key: String, owner: String) =
        uncut(ui.onNodeWithTag("$owner-title/$key", useUnmergedTree = true))

    /** The opaque reading viewport moves between the card and the space below route chrome. */
    private fun checkCardTrip(key: String, input: DirectNavigationEventInput) {
        val row = bounds(key)
        val detail = "detail:$key"
        click(ui.onNodeWithTag(key))
        ui.mainClock.advanceTimeBy(128)
        val middle = bounds(detail)
        val pushingTitle = title(key, "detail")
        val pushingBack = backHit()
        ui.mainClock.advanceTimeBy(600)
        val page = bounds(detail)
        val back = backHit()
        assertTrue("The settled reading viewport starts below Back", page.top >= back.bottom - 1f)
        clearOfBack(pushingTitle, pushingBack, back)
        fun between(box: Rect) {
            assertTrue("Height must interpolate row → page: $row / $box / $page",
                box.height > row.height + 2f && box.height < page.height - 2f)
            assertTrue("Must originate at the tapped row, not the top of the scene: $box",
                box.top > page.top + 1f && box.top < row.top - 1f)
        }
        between(middle)

        start(input); seek(input, .35f)
        val nearDetail = bounds(detail)
        between(nearDetail)
        clearOfBack(title(key, "row"), backHit(), back)
        seek(input, .70f)
        val nearCard = bounds(detail)
        between(nearCard)
        clearOfBack(title(key, "row"), backHit(), back)
        assertTrue("More back progress must contract the same surface", nearCard.height < nearDetail.height - 2f)
        seek(input, .30f)
        assertTrue("Dragging the finger back must expand the surface again", bounds(detail).height > nearCard.height + 2f)
        clearOfBack(title(key, "row"), backHit(), back)
        ui.runOnUiThread { input.backCancelled() }
        ui.mainClock.advanceTimeBy(600)
        assertEquals("Cancel must keep the original detail fully open", page, bounds(detail))
        start(input); seek(input, .55f); complete(input)
        assertEquals("Return must restore this ID at its old scroll position", row, bounds(key))
        ui.onNodeWithTag(detail).assertDoesNotExist()
    }

    @Test fun firstTaskPushAndReversiblePredictivePopUseTheScrolledRow() {
        show(*(1..18).map { PlannerCommand.TaskAdd(TaskInput("步骤 $it")) }.toTypedArray())
        val queue = runBlocking { store.state().queue }
        ui.onNodeWithTag("queue").performScrollToIndex(7)
        withClockAndBack { checkCardTrip("task:${queue[7].id}", it) }
        assertEquals(queue, runBlocking { store.state().queue })
    }

    @Test fun heldAndReversedBackNeverMovesLargeTypeTitleAcrossBackButton() {
        show(PlannerCommand.TaskAdd(TaskInput("A - Outline")), fontScale = 2f)
        val key = "task:${runBlocking { store.state().queue.single().id }}"
        ui.onNodeWithTag(key).performClick()
        val resting = backHit()
        withClockAndBack { input ->
            start(input)
            for (progress in listOf(.30f, .45f, .55f, .68f, .42f)) {
                seek(input, progress)
                clearOfBack(title(key, "row"), backHit(), resting)
            }
            ui.runOnUiThread { input.backCancelled() }
            ui.mainClock.advanceTimeBy(600)
            clearOfBack(title(key, "detail"), backHit(), resting)
        }
    }

    @Test fun stackPagesAdjoinUnderFixedToolsAndFrameExpandsFromItsOwnRow() {
        show(PlannerCommand.FrameCreate("开会", listOf(TaskInput("待办"))))
        val id = runBlocking { store.state().frames.single().id }
        val queue = ui.onNodeWithTag("queue").fetchSemanticsNode()
        val origin = x(queue)
        val dock = bounds("navigation-capsule")
        val composer = bounds("assistant-input")
        withClockAndBack { input ->
            click(ui.onNode(hasText("栈") and hasClickAction()))
            ui.mainClock.advanceTimeBy(128)
            val outgoing = x(queue)
            val incoming = x(ui.onNodeWithTag("frames").fetchSemanticsNode())
            assertTrue(outgoing < origin - 1f && incoming > origin + 1f)
            assertEquals("Neither an empty page nor overlapping lists", queue.size.width.toFloat(), incoming - outgoing, 2f)
            assertEquals(dock, bounds("navigation-capsule")); assertEquals(composer, bounds("assistant-input"))
            ui.mainClock.advanceTimeBy(600)
            checkCardTrip("frame:$id", input)
        }
    }

    @Test fun unpairedEditorSlidesAndCancelledBackRetainsTheDraft() {
        show()
        val queue = ui.onNodeWithTag("queue").fetchSemanticsNode()
        val origin = x(queue)
        withClockAndBack { input ->
            click(ui.onNodeWithTag("add-task")); ui.mainClock.advanceTimeBy(112)
            val midEditor = x(ui.onNodeWithTag("title:0").fetchSemanticsNode())
            assertTrue("The covered scene must recede, not fade in place", x(queue) < origin - 1f)
            ui.mainClock.advanceTimeBy(600)
            val field = ui.onNodeWithTag("title:0")
            val end = x(field.fetchSemanticsNode())
            assertTrue("The editor must enter from the trailing edge", midEditor > end + 1f)
            ui.mainClock.autoAdvance = true
            field.performTextInput("未提交的草稿")
            ui.waitForIdle()
            ui.mainClock.autoAdvance = false
            start(input); seek(input, .35f)
            val partial = x(field.fetchSemanticsNode())
            assertTrue(partial > end + 1f)
            seek(input, .65f)
            assertTrue(x(field.fetchSemanticsNode()) > partial + 1f)
            ui.runOnUiThread { input.backCancelled() }; ui.mainClock.advanceTimeBy(600)
            assertEquals(end, x(field.fetchSemanticsNode()), 1f)
            field.assertTextContains("未提交的草稿")
            start(input); seek(input, .50f); complete(input)
            field.assertDoesNotExist()
        }
        assertTrue(runBlocking { store.state().queue.isEmpty() })
    }

    @Test fun rootTapMovesBothAdjacentPagesAndSwipingBackUpdatesSelection() {
        show()
        val queue = ui.onNodeWithTag("queue").fetchSemanticsNode()
        val origin = x(queue)
        val dock = bounds("navigation-capsule")
        val composer = bounds("assistant-input")
        withClockAndBack {
            click(ui.onNodeWithTag("nav:graphs")); ui.mainClock.advanceTimeBy(128)
            val outgoing = x(queue)
            val graph = ui.onNodeWithText("还没有流程图")
            val mid = x(graph.fetchSemanticsNode())
            assertTrue(outgoing < origin - 1f)
            ui.mainClock.advanceTimeBy(600)
            val end = x(graph.fetchSemanticsNode())
            assertTrue(mid > end + 1f)
            assertEquals(queue.size.width.toFloat(), (origin - outgoing) + (mid - end), 2f)
            assertEquals(dock, bounds("navigation-capsule")); assertEquals(composer, bounds("assistant-input"))
        }
        ui.onRoot().performTouchInput { swipeRight() }
        ui.onNodeWithTag("nav:workspace").assertIsSelected()
        ui.onNodeWithTag("queue").assertIsDisplayed()
    }

    @Test fun reducedMotionOpensTheEditorInPlaceOnItsFirstFrame() {
        show(reduced = true)
        withClockAndBack {
            click(ui.onNodeWithTag("add-task")); ui.mainClock.advanceTimeBy(48)
            val field = ui.onNodeWithTag("title:0")
            val first = x(field.fetchSemanticsNode())
            ui.mainClock.advanceTimeBy(400)
            assertEquals("No sliding while reduced motion is selected", first, x(field.fetchSemanticsNode()), 1f)
        }
    }

    @Test fun removedSourceNeverShrinksIntoItsReplacement() {
        show(PlannerCommand.TaskAdd(TaskInput("A")), PlannerCommand.TaskAdd(TaskInput("B")))
        val (a, b) = runBlocking { store.state().queue }
        ui.onNodeWithTag("task:${a.id}").performClick()
        val key = "detail:task:${a.id}"
        runBlocking { store.apply(PlannerCommand.TaskFinish(a.id)) }
        ui.waitUntil(12_000) { ui.onAllNodesWithText("这项任务已不在队列中。").fetchSemanticsNodes().size == 1 }
        val full = bounds(key)
        withClockAndBack { input ->
            start(input); seek(input, .50f)
            val outgoing = ui.onNodeWithTag(key).fetchSemanticsNode()
            assertEquals("No source: preserve the viewport height while sliding, never shrink toward B",
                full.height, outgoing.size.height.toFloat(), 1f)
            assertTrue("Missing source still needs a spatial pop", x(outgoing) > full.left + 2f)
            complete(input)
        }
        ui.onNodeWithTag("task:${b.id}").assertIsDisplayed()
        ui.onNodeWithTag("task:${a.id}").assertDoesNotExist()
    }
}
