package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import kotlin.math.abs
import kotlin.math.roundToInt

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

    private fun pixels() = ui.onRoot().captureToImage().toPixelMap()
    private fun delta(a: Color, b: Color) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    /** Outside the reveal, even the *ink* of the original toolbar, rows and dock must stay
     * put. A stationary semantics tree behind a blank or translucent page is not enough. */
    private fun assertUncoveredPixels(before: PixelMap, during: PixelMap, clip: Rect) {
        val trim = (28 * ui.density.density).roundToInt() // exclude system clock / navigation bar
        val reveal = clip.inflate(2f)
        var edges = 0
        var changed = 0
        for (y in trim until before.height - trim step 3) for (x in 3 until before.width - 3 step 3) {
            if (reveal.contains(Offset(x.toFloat(), y.toFloat()))) continue
            if (delta(before[x, y], before[x + 2, y]) > .15f) edges++
            if (delta(before[x, y], during[x, y]) > .12f) changed++
        }
        assertTrue("Need real uncovered text or controls in this keyframe", edges > 15)
        assertTrue("Detail/chrome leaked or erased the original scene outside $clip: $changed changed / $edges edges",
            changed <= maxOf(8, edges / 12))
    }

    private fun title(key: String) =
        ui.onNodeWithTag("detail-title/$key", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun backHit() = ui.onNode(hasContentDescription("返回") and hasClickAction()).fetchSemanticsNode().boundsInRoot
    private data class DetailFrame(val box: Rect, val title: Rect, val pixels: PixelMap, val headerEnd: Float, val dockStart: Float)
    private fun frame(key: String): DetailFrame {
        val inside = hasAnyAncestor(hasTestTag("detail:$key"))
        val back = ui.onNode(inside and hasContentDescription("返回") and hasClickAction())
        val firstAction = if (key.startsWith("task:")) hasText("完成任务") and hasClickAction() else hasTestTag("assistant-input")
        return DetailFrame(bounds("detail:$key"), title(key), pixels(),
            windowRect(back).bottom, windowRect(ui.onNode(inside and firstAction)).top)
    }

    private fun windowRect(node: SemanticsNodeInteraction): Rect {
        val c = node.fetchSemanticsNode().layoutInfo.coordinates
        return ui.runOnIdle { Rect(c.localToRoot(Offset.Zero), c.localToRoot(Offset(c.size.width.toFloat(), c.size.height.toFloat()))) }
    }

    /** Compare actual title ink at *both ends*, and actual blank paper around it, against
     * the settled page at the measured title scale. Catches truncated glyphs and second
     * copies of row text/drag handles shining through the moving surface. */
    private fun assertDetailInk(open: DetailFrame, moving: DetailFrame) {
        assertTrue("Title ${moving.title} must be visible inside the moving window ${moving.box}", moving.title.width > 0 && moving.title.height > 0)
        val paper = open.pixels[open.box.right.toInt() - 3, open.box.center.y.toInt()]
        fun titleInk(f: DetailFrame, half: Int): Float {
            var dark = 0
            var total = 0
            val x0 = (f.title.left + half * f.title.width / 2).roundToInt()
            val x1 = (f.title.left + (half + 1) * f.title.width / 2).roundToInt()
            for (y in f.title.top.roundToInt() until f.title.bottom.roundToInt())
                for (x in x0 until x1) {
                    total++
                    if (delta(f.pixels[x, y], paper) > .35f) dark++
                }
            return dark.toFloat() / total.coerceAtLeast(1)
        }
        for (half in 0..1) {
            val ink = titleInk(open, half)
            assertTrue("Reference title has no ink in half $half", ink > .02f)
            assertTrue("Missing or ghosted title ink in half $half at ${moving.box}",
                titleInk(moving, half) in ink * .60f..ink * 1.55f)
        }

        val scale = moving.title.width / open.title.width
        var tested = 0
        var foreignInk = 0
        var firstInkY = Int.MAX_VALUE
        var lastInkY = -1
        val inset = 5
        for (y in moving.box.top.toInt() + inset until moving.box.bottom.toInt() - inset step 3)
            for (x in moving.box.left.toInt() + inset until moving.box.right.toInt() - inset step 3) {
                val ox = ((x - moving.title.left) / scale + open.title.left).roundToInt()
                val oy = ((y - moving.title.top) / scale + open.title.top).roundToInt()
                // Only sample definite background, not glyph edges at fractional scale.
                if (oy < open.title.top || oy > open.box.center.y || ox !in inset until open.pixels.width - inset) continue
                if (delta(open.pixels[ox, oy], paper) > .05f) continue
                tested++
                val pixel = moving.pixels[x, y]
                if (delta(pixel, paper) <= .22f) continue
                // Fixed controls reveal their exact settled pixels instead of following the body.
                if ((y <= open.headerEnd || y >= open.dockStart) && delta(pixel, open.pixels[x, y]) < .12f) continue
                if ((-inset..inset step inset).all { dy -> (-inset..inset step inset).all { dx ->
                        delta(open.pixels[ox + dx, oy + dy], paper) <= .05f
                    } }) {
                    foreignInk++
                    firstInkY = minOf(firstInkY, y)
                    lastInkY = maxOf(lastInkY, y)
                }
            }
        assertTrue("Need blank paper within the animated detail", tested > 30)
        assertTrue("Extra ink on detail: $foreignInk/$tested at y=$firstInkY..$lastInkY, title=${moving.title}, window=${moving.box}",
            foreignInk <= maxOf(6, tested / 350))
    }

    private fun between(origin: Rect, box: Rect, page: Rect) {
        assertTrue("Width must interpolate: $origin / $box / $page",
            box.width > origin.width + 1f && box.width < page.width - 1f)
        assertTrue("Height must interpolate: $origin / $box / $page",
            box.height > origin.height + 2f && box.height < page.height - 2f)
        // All four edges use the same bounds transform, from this particular origin.
        val t = (box.height - origin.height) / (page.height - origin.height)
        assertEquals(origin.top + (page.top - origin.top) * t, box.top, 3f)
        assertEquals(origin.left + (page.left - origin.left) * t, box.left, 3f)
    }

    private fun checkCardTrip(key: String, input: DirectNavigationEventInput) {
        val row = bounds(key)
        val before = pixels()
        val detail = "detail:$key"
        click(ui.onNodeWithTag(key))
        ui.mainClock.advanceTimeBy(128)
        val middle = frame(key)
        ui.mainClock.advanceTimeBy(600)
        val open = frame(key)
        between(row, middle.box, open.box)
        assertUncoveredPixels(before, middle.pixels, middle.box)
        assertDetailInk(open, middle)

        start(input); seek(input, .35f)
        val nearDetail = bounds(detail)
        between(row, nearDetail, open.box)
        seek(input, .70f)
        val nearCard = frame(key)
        between(row, nearCard.box, open.box)
        assertUncoveredPixels(before, nearCard.pixels, nearCard.box)
        assertDetailInk(open, nearCard)
        assertTrue("More back progress must contract the same surface", nearCard.box.height < nearDetail.height - 2f)
        seek(input, .30f)
        assertTrue("Dragging the finger back must expand the surface again", bounds(detail).height > nearCard.box.height + 2f)
        ui.runOnUiThread { input.backCancelled() }
        ui.mainClock.advanceTimeBy(600)
        assertEquals("Cancel must keep the original detail fully open", open.box, bounds(detail))
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

    @Test fun chromeIsRevealedInItsOwnSlotNeverCarriedUnderTheRootToolbar() {
        show(PlannerCommand.TaskAdd(TaskInput("报告提纲")), PlannerCommand.TaskAdd(TaskInput("取资料")))
        val key = "task:${runBlocking { store.state().queue.first().id }}"
        val rootHeader = bounds("root-toolbar")
        val rootPixels = pixels()
        val inside = hasAnyAncestor(hasTestTag("detail:$key"))
        val heading = ui.onNode(hasText("任务详情") and inside, useUnmergedTree = true)
        val assistant = ui.onNode(hasTestTag("assistant-input") and inside, useUnmergedTree = true)
        withClockAndBack { input ->
            click(ui.onNodeWithTag(key))
            ui.mainClock.advanceTimeBy(144)
            val push = frame(key)
            val pushHeading = windowRect(heading)
            val pushDock = windowRect(assistant)
            ui.mainClock.advanceTimeBy(600)
            val open = frame(key)
            val finalHeading = windowRect(heading)
            val finalDock = windowRect(assistant)
            val paper = open.pixels[open.box.right.toInt() - 3, open.box.center.y.toInt()]
            // PageTitle centers its text in a weighted row. Use the glyphs, not that
            // row's layout width; the left side legitimately contains moving task text.
            var glyphLeft = finalHeading.right.toInt()
            var glyphRight = finalHeading.left.toInt()
            for (y in finalHeading.top.toInt() until finalHeading.bottom.toInt())
                for (x in finalHeading.left.toInt() until finalHeading.right.toInt())
                    if (delta(open.pixels[x, y], paper) > .40f) {
                        glyphLeft = minOf(glyphLeft, x)
                        glyphRight = maxOf(glyphRight, x)
                    }
            assertTrue("Need actual settled heading glyphs", glyphRight - glyphLeft > 30)
            val headerBand = Rect(minOf(rootHeader.left, finalHeading.left), minOf(rootHeader.top, finalHeading.top),
                maxOf(rootHeader.right, finalHeading.right), maxOf(rootHeader.bottom, finalHeading.bottom))
            var rootGlyphs = 0
            var detailGlyphs = 0
            var checkedBlank = 0
            fun check(f: DetailFrame, header: Rect, dock: Rect) {
                fun assertFixed(label: String, fixed: Rect, now: Rect) {
                    assertEquals(label, fixed.left, now.left, 1f); assertEquals(label, fixed.top, now.top, 1f)
                    assertEquals(label, fixed.right, now.right, 1f); assertEquals(label, fixed.bottom, now.bottom, 1f)
                }
                assertFixed("Detail header must not travel below the root header", finalHeading, header)
                assertFixed("Detail composer must replace the dock in place", finalDock, dock)
                val visible = f.box.deflate(3f)
                val excluded = f.box.inflate(3f)
                var expected = 0
                var wrong = 0
                for (y in headerBand.top.toInt() until headerBand.bottom.toInt() step 2)
                    for (x in headerBand.left.toInt() until headerBand.right.toInt() step 2) {
                        val p = Offset(x.toFloat(), y.toFloat())
                        val ink = when {
                            visible.contains(p) && finalHeading.contains(p) && delta(open.pixels[x, y], paper) > .40f ->
                                open.pixels[x, y].also { detailGlyphs++ }
                            !excluded.contains(p) && rootHeader.contains(p) && delta(rootPixels[x, y], paper) > .40f ->
                                rootPixels[x, y].also { rootGlyphs++ }
                            else -> continue
                        }
                        expected++
                        // The glass compositor can resample an edge within one physical pixel.
                        if ((-1..1).all { dy -> (-1..1).all { dx -> delta(f.pixels[x + dx, y + dy], ink) > .15f } }) wrong++
                    }
                assertTrue("Wrong toolbar visible in ${f.box}: $wrong/$expected glyph samples",
                    wrong <= maxOf(2, expected / 15))
                // The short fixture texts are left aligned. The center below the fixed
                // heading is bare paper, even when the real root toolbar is still above it.
                val y0 = maxOf(visible.top, finalHeading.bottom + 4f).toInt()
                val y1 = minOf(visible.bottom, open.box.center.y).toInt()
                val x0 = maxOf(visible.left.toInt(), glyphLeft)
                val x1 = minOf(visible.right.toInt(), glyphRight + 1)
                var probes = 0
                var ghosts = 0
                for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) {
                    if (delta(open.pixels[x, y], paper) > .05f) continue
                    probes++
                    if (delta(f.pixels[x, y], paper) > .28f) ghosts++
                }
                checkedBlank += probes
                assertTrue("Displaced heading ink under the fixed toolbar: $ghosts/$probes", ghosts <= maxOf(2, probes / 700))
            }
            check(push, pushHeading, pushDock)
            start(input)
            for (progress in listOf(.12f, .30f, .45f, .56f, .68f)) {
                seek(input, progress)
                check(frame(key), windowRect(heading), windowRect(assistant))
            }
            assertTrue("Need glyphs from both visible toolbars and blank paper between them: $rootGlyphs/$detailGlyphs/$checkedBlank",
                rootGlyphs > 40 && detailGlyphs > 40 && checkedBlank > 200)
            complete(input)
        }
    }

    @Test fun backUsesTheNewLocationOfTheSameTaskAfterReorder() {
        show(*(1..3).map { PlannerCommand.TaskAdd(TaskInput("步骤 $it")) }.toTypedArray())
        val (a, b, c) = runBlocking { store.state().queue }
        val key = "task:${b.id}"
        val old = bounds(key)
        ui.onNodeWithTag(key).performClick()
        runBlocking { store.apply(PlannerCommand.QueueOrder(listOf(c.id, a.id, b.id))) }
        ui.waitUntil(12_000) { ui.onAllNodesWithText("队列第 3 项").fetchSemanticsNodes().size == 1 }
        val page = bounds("detail:$key")
        withClockAndBack { input ->
            start(input); seek(input, .65f)
            val middle = bounds("detail:$key")
            complete(input)
            val reordered = bounds(key)
            assertTrue("The source really moved", reordered.top > old.top + 2f)
            between(reordered, middle, page)
        }
    }

    @Test fun partiallyCoveredRowIsNeverUsedAsAFullCardOrigin() {
        show(*(1..18).map { PlannerCommand.TaskAdd(TaskInput("步骤 $it")) }.toTypedArray())
        val key = "task:${runBlocking { store.state().queue[5].id }}"
        val list = ui.onNodeWithTag("queue")
        list.performScrollToIndex(6) // list header is item zero
        val aligned = bounds(key)
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, aligned.height / 2) }
        assertTrue("The row must now straddle the top clearance", bounds(key).top < aligned.top - 2f)
        withClockAndBack { input ->
            click(ui.onNodeWithTag(key)); ui.mainClock.advanceTimeBy(128)
            val first = bounds("detail:$key")
            ui.mainClock.advanceTimeBy(600)
            assertEquals("Do not expand from the hidden part of a row", bounds("detail:$key"), first)
            start(input); seek(input, .55f)
            assertEquals("The still-obscured source must not attract Back", first, bounds("detail:$key"))
            complete(input)
        }
    }

    @Test fun heldAndReversedBackNeverMovesLargeTypeTitleAcrossBackButton() {
        show(PlannerCommand.TaskAdd(TaskInput("A - Outline")), fontScale = 2f)
        val key = "task:${runBlocking { store.state().queue.single().id }}"
        ui.onNodeWithTag(key).performClick()
        val open = frame(key)
        withClockAndBack { input ->
            start(input)
            for (progress in listOf(.30f, .45f, .55f, .68f, .42f)) {
                seek(input, progress)
                val moving = frame(key)
                assertTrue("Large title must stay within the moving surface", moving.box.inflate(1f).contains(moving.title.center))
                assertFalse("The clipped Back control must never cover the title", moving.title.overlaps(backHit()))
                assertDetailInk(open, moving)
            }
            ui.runOnUiThread { input.backCancelled() }
            ui.mainClock.advanceTimeBy(600)
            assertEquals(open.title, title(key))
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

    @Test fun fabExpandsIntoEditorAndCancelledBackRetainsTheDraft() {
        show()
        val queue = ui.onNodeWithTag("queue").fetchSemanticsNode()
        val originX = x(queue)
        val fab = bounds("add-task")
        val route = "route:add-task"
        val before = pixels()
        withClockAndBack { input ->
            click(ui.onNodeWithTag("add-task")); ui.mainClock.advanceTimeBy(112)
            val middle = bounds(route)
            assertEquals("The queue behind the editor must stay put", originX, x(queue), 1f)
            assertUncoveredPixels(before, pixels(), middle)
            ui.mainClock.advanceTimeBy(600)
            val field = ui.onNodeWithTag("title:0")
            val page = bounds(route)
            between(fab, middle, page)
            ui.mainClock.autoAdvance = true
            field.performTextInput("未提交的草稿")
            ui.waitForIdle()
            ui.mainClock.autoAdvance = false
            start(input); seek(input, .35f)
            val partial = bounds(route)
            between(fab, partial, page)
            seek(input, .65f)
            val smaller = bounds(route)
            between(fab, smaller, page)
            assertTrue(smaller.width < partial.width && smaller.height < partial.height)
            ui.runOnUiThread { input.backCancelled() }; ui.mainClock.advanceTimeBy(600)
            assertEquals(page, bounds(route))
            field.assertTextContains("未提交的草稿")
            start(input); seek(input, .50f); complete(input)
            field.assertDoesNotExist()
        }
        assertTrue(runBlocking { store.state().queue.isEmpty() })
    }

    @Test fun aSettingsRowExpandsWithoutRetractingSettingsIntoItsOwnGear() {
        show()
        ui.onNode(hasContentDescription("设置") and hasClickAction()).performClick()
        val row = ui.onNode(hasText("布局") and hasClickAction())
        val origin = row.fetchSemanticsNode().boundsInRoot
        val parent = bounds("route:settings")
        val before = pixels()
        val tag = "route:appearance:layout"
        withClockAndBack { input ->
            click(row); ui.mainClock.advanceTimeBy(112)
            between(origin, bounds(tag), parent)
            assertUncoveredPixels(before, pixels(), bounds(tag))
            ui.mainClock.advanceTimeBy(500)
            start(input); seek(input, .58f)
            between(origin, bounds(tag), parent)
            assertUncoveredPixels(before, pixels(), bounds(tag))
            complete(input)
            assertEquals(parent, bounds("route:settings"))
            assertEquals(origin, row.fetchSemanticsNode().boundsInRoot)
        }
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
            assertEquals("No source: do not contract toward its replacement", full, outgoing.boundsInRoot)
            complete(input)
        }
        ui.onNodeWithTag("task:${b.id}").assertIsDisplayed()
        ui.onNodeWithTag("task:${a.id}").assertDoesNotExist()
    }
}
