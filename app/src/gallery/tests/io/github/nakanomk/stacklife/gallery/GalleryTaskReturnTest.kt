package io.github.nakanomk.stacklife.gallery

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import io.github.nakanomk.stacklife.planner.CommitResult
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.PreviewResult
import io.github.nakanomk.stacklife.planner.TaskInput
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.planner.Workspace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GalleryTaskReturnTest {
    private val ui = createAndroidComposeRule<GalleryActivity>()
    private val environment = object : ExternalResource() {
        private val prefs get() = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("catalog", Context.MODE_PRIVATE)
        private var previous: String? = null
        override fun before() { previous = prefs.getString("preview", null); GalleryOptions().save(prefs) }
        override fun after() { prefs.edit().putString("preview", previous).commit() }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(environment).around(ui)

    @Test fun openingACourseShowsTheActualMeetingWithoutAModelConnection() {
        val app = ui.activity.application as GalleryApplication
        val first = runBlocking {
            app.ready.await()
            (app.planner.observe(PlannerQuery.Week("gallery-week", 1)).first() as io.github.nakanomk.stacklife.planner.CourseWeek).classes.first()
        }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("category:COMPONENT").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("category:COMPONENT").performClick()
        ui.onNodeWithTag("example:component-course-grid").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("grid-course:0").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("grid-course:0").performClick()
        ui.onNode(hasText(first.meeting.title) and hasAnyAncestor(hasTestTag("course-detail"))).assertIsDisplayed()
        ui.onNodeWithText("Asia/Shanghai").assertIsDisplayed()
    }

    // The new renderer clips in draw, not measure. Check the painted parent and moving
    // surface, not accessibility bounds that need not follow a GraphicsLayer clip.
    private fun changed(base: PixelMap, frame: PixelMap, area: Rect, except: Rect = Rect.Zero): Float {
        var total = 0
        var count = 0
        for (y in area.top.toInt().coerceAtLeast(0) until area.bottom.toInt().coerceAtMost(base.height) step 4)
            for (x in area.left.toInt().coerceAtLeast(0) until area.right.toInt().coerceAtMost(base.width) step 4) {
                if (except.contains(Offset(x.toFloat(), y.toFloat()))) continue
                val a = base[x, y]; val b = frame[x, y]
                if (maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue)) > .12f) count++
                total++
            }
        check(total > 100) { "The viewport contains too few comparison pixels: $area excluding $except" }
        return count.toFloat() / total
    }

    @Test fun deletingTheSourceExitsToTheEdgeAndNeverShrinksToANeighbor() {
        val app = ui.activity.application as GalleryApplication
        fun apply(command: PlannerCommand) = runBlocking {
            app.ready.await()
            val ready = app.planner.preview(listOf(command)) as PreviewResult.Ready
            assertTrue(app.planner.commit(ready.proposalId) is CommitResult.Applied)
            ready
        }
        val id = apply(PlannerCommand.TaskAdd(TaskInput("只用于落点检查", ref = "edge"))).refs.getValue("edge")
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("category:MOTION").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("category:MOTION").performClick()
        ui.onNodeWithTag("example:motion-task").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("queue").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("queue").performScrollToKey(id)
        ui.onNodeWithTag("task:$id").performClick()
        ui.onNodeWithTag("detail:task:$id").assertIsDisplayed()
        apply(PlannerCommand.TaskRemove(id))
        val missing = ui.activity.getString(io.github.nakanomk.stacklife.R.string.graph_source_removed)
        ui.waitUntil(10_000) { ui.onAllNodesWithText(missing).fetchSemanticsNodes().isNotEmpty() }
        val page = ui.onNodeWithTag("detail:task:$id").fetchSemanticsNode().boundsInRoot
        val front = ui.onRoot().captureToImage().toPixelMap()
        val input = DirectNavigationEventInput()
        ui.runOnIdle { ui.activity.navigationEventDispatcher.addInput(input) }
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnUiThread { input.backStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = 0f)) }
            ui.mainClock.advanceTimeBy(32)
            ui.runOnUiThread { input.backProgressed(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = .5f)) }
            ui.mainClock.advanceTimeBy(64)
            val middle = ui.onRoot().captureToImage().toPixelMap()
            // At the midpoint the former page's text and adjacent paper translate together, at full opacity.
            val dx = (page.width / 2).toInt()
            var edges = 0; var lostInk = 0
            for (y in page.top.toInt() + 4 until page.bottom.toInt() - 4 step 2)
                for (x in page.left.toInt() + 4 until page.center.x.toInt() - 4 step 2) {
                    val a = front[x, y]
                    if (abs(a.red - front[x + 2, y].red) < .28f) continue
                    edges++
                    if (abs(a.red - middle[x + dx, y].red) > .16f) lostInk++
                }
            assertTrue("Need legible foreground edges, got $edges", edges > 20)
            assertTrue("Edge exit lost or faded $lostInk of $edges glyph edges", lostInk < edges / 8)
            ui.runOnUiThread { input.backCompleted() }
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("task:$id").assertDoesNotExist()
            ui.onNodeWithTag("queue").assertIsDisplayed()
            val returned = ui.onRoot().captureToImage().toPixelMap()
            assertTrue("The exposed half must be the live queue", changed(returned, middle,
                Rect(page.left + 4, page.top + 4, page.center.x - 4, page.bottom - 4)) < .02f)
        } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnUiThread { ui.activity.navigationEventDispatcher.removeInput(input) }
        }
    }

    @Test fun backDuringEditorEntryReturnsToTheSameTaskWithoutSaving() {
        val app = ui.activity.application as GalleryApplication
        val before = runBlocking { app.ready.await(); app.planner.observe(PlannerQuery.Workspace).first() as Workspace }
        val tag = "task:${before.queue[1].id}"
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("category:MOTION").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("category:MOTION").performClick()
        ui.onNodeWithTag("example:motion-task").performClick()
        ui.onNodeWithTag(tag).performClick()
        val edit = ui.onNodeWithText(ui.activity.getString(io.github.nakanomk.stacklife.R.string.ui_edit_task))
        edit.assertIsDisplayed()
        val input = DirectNavigationEventInput()
        ui.runOnIdle { ui.activity.navigationEventDispatcher.addInput(input) }
        ui.mainClock.autoAdvance = false
        try {
            edit.performClick()
            ui.mainClock.advanceTimeBy(48)
            // This route already owns Back while its separate Room read can still be loading.
            val editorRoute = ui.onNodeWithTag("route:edit:${before.queue[1].id}")
            editorRoute.assertExists()
            ui.runOnUiThread { input.backCompleted() }
            ui.mainClock.advanceTimeBy(1000)
            editorRoute.assertDoesNotExist()
            ui.onNodeWithTag("title:0").assertDoesNotExist()
            ui.onNodeWithTag("detail:$tag").assertIsDisplayed()
            edit.assertIsDisplayed()
        } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnUiThread { ui.activity.navigationEventDispatcher.removeInput(input) }
        }
        edit.performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("title:0").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("title:0").assertTextContains(before.queue[1].title)
        ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithTag("detail:$tag").assertIsDisplayed()
        assertEquals(before, runBlocking { app.planner.observe(PlannerQuery.Workspace).first() })
    }

    @Test fun recreatingTheGalleryKeepsTheSameTaskUntilOneBackReturnsToTheQueue() {
        val app = ui.activity.application as GalleryApplication
        val before = runBlocking { app.ready.await(); app.planner.observe(PlannerQuery.Workspace).first() as Workspace }
        val task = before.queue[1]
        val row = "task:${task.id}"
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("category:MOTION").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("category:MOTION").performClick()
        ui.onNodeWithTag("example:motion-task").performClick()
        ui.onNodeWithTag("queue").performScrollToKey(task.id)
        ui.onNodeWithTag(row).performClick()
        ui.onNodeWithTag("detail-title/$row").assertTextContains(task.title)

        ui.activityRule.scenario.recreate()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("detail-title/$row").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("detail-title/$row").assertIsDisplayed().assertTextContains(task.title)
        ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithTag("detail:$row").assertDoesNotExist()
        ui.onNodeWithTag("queue").performScrollToKey(task.id)
        ui.onNodeWithTag(row).assertIsDisplayed()
        assertEquals(before, runBlocking { app.planner.observe(PlannerQuery.Workspace).first() })
    }

    @Test fun theStackExampleCanUndoRestoringItsLastItemsFromTheList() {
        val app = ui.activity.application as GalleryApplication
        fun workspace() = runBlocking { app.ready.await(); app.planner.observe(PlannerQuery.Workspace).first() as Workspace }
        val before = workspace()
        val frame = before.frames.first { it.remaining > 0 }
        val frameTag = "frame:${frame.id}"
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("category:MOTION").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("category:MOTION").performClick()
        ui.onNodeWithTag("example:motion-stack").performClick()
        ui.onNodeWithTag("frames").performScrollToKey(frame.id)
        ui.onNodeWithTag(frameTag).performClick()
        ui.onNodeWithText(ui.activity.getString(io.github.nakanomk.stacklife.R.string.ui_restore_all)).performScrollTo().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("queue").fetchSemanticsNodes().isNotEmpty() &&
            ui.onAllNodesWithTag("receipt-undo").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag(frameTag).assertDoesNotExist()
        val moved = frame.items.first { it.restorable }.taskId
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("task:$moved").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("task:$moved").assertIsDisplayed()
        assertTrue(workspace().frames.none { it.id == frame.id })
        ui.onNodeWithTag("receipt-keep").assertIsDisplayed()
        ui.onNodeWithTag("receipt-undo").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("receipt-undo").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithTag("queue").assertIsDisplayed()
        ui.onNode(hasText(ui.activity.getString(io.github.nakanomk.stacklife.R.string.ui_stack)) and isSelectable()).performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag(frameTag).fetchSemanticsNodes().isNotEmpty() }
        val restored = workspace()
        assertEquals(frame, restored.frames.single { it.id == frame.id })
        assertEquals(before.queue, restored.queue)
    }

    @Test fun cancelledBackExpandsTheSameTaskAndLeavesTheGalleryWorkspaceUntouched() {
        val app = ui.activity.application as GalleryApplication
        val before = runBlocking { app.ready.await(); app.planner.observe(PlannerQuery.Workspace).first() as Workspace }
        val tag = "task:${before.queue[1].id}"
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("category:MOTION").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("category:MOTION").performClick()
        ui.onNodeWithTag("example:motion-task").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        val row = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        fun paint() = ui.onRoot().captureToImage().toPixelMap()
        val parent = paint()
        ui.mainClock.autoAdvance = false
        ui.onNodeWithTag(tag).performClick()
        ui.mainClock.advanceTimeByFrame()
        val firstFrame = paint()
        ui.mainClock.autoAdvance = true
        val detail = ui.onNodeWithTag("detail:$tag")
        val open = detail.fetchSemanticsNode().boundsInRoot
        assertTrue("The entering page must start on its own row with the other rows still painted",
            changed(parent, firstFrame, open.deflate(4f), row.inflate(6f)) < .02f)
        val fullyOpen = paint()
        assertTrue("The detail must contain content different from its parent", changed(parent, fullyOpen, open.deflate(4f)) > .035f)
        val input = DirectNavigationEventInput()
        ui.runOnIdle { ui.activity.navigationEventDispatcher.addInput(input) }
        ui.mainClock.autoAdvance = false
        fun event(fraction: Float) = NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = fraction)
        try {
            ui.runOnUiThread { input.backStarted(event(0f)) }
            ui.mainClock.advanceTimeBy(64)
            ui.runOnUiThread { input.backProgressed(event(.55f)) }
            ui.mainClock.advanceTimeBy(64)
            val halfBox = lerp(row, open, .45f)
            val half = paint()
            assertTrue("At 55% Back the parent ink must remain outside the correct task's aperture",
                changed(parent, half, open.deflate(4f), halfBox.inflate(6f)) < .02f)
            assertTrue("The middle frame must still paint the moving container", changed(parent, half, halfBox.deflate(8f)) > .025f)
            ui.runOnUiThread { input.backProgressed(event(.2f)) }
            ui.mainClock.advanceTimeBy(64)
            val reversed = paint()
            assertTrue("Reversing to 20% must expand over pixels previously owned by the parent",
                changed(parent, reversed, lerp(row, open, .8f).deflate(8f), halfBox.inflate(6f)) > .025f)
            ui.runOnUiThread { input.backCancelled() }
            ui.mainClock.advanceTimeBy(800)
            assertEquals(open, detail.fetchSemanticsNode().boundsInRoot)
            assertTrue("Cancelling must restore the live detail ink", changed(fullyOpen, paint(), open.deflate(4f)) < .02f)
            ui.runOnUiThread { input.backStarted(event(0f)); input.backProgressed(event(.7f)) }
            ui.mainClock.advanceTimeBy(64)
            ui.runOnUiThread { input.backCompleted() }
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("detail:$tag").assertDoesNotExist()
            assertEquals(row, ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
        } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnUiThread { ui.activity.navigationEventDispatcher.removeInput(input) }
        }
        assertEquals(before, runBlocking { app.planner.observe(PlannerQuery.Workspace).first() })
    }
}
