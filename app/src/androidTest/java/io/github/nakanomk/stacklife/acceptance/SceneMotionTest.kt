package io.github.nakanomk.stacklife.acceptance

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.BackgroundStore
import io.github.nakanomk.stacklife.appearance.LocalBackgroundStore
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import io.github.nakanomk.stacklife.about.HelpAboutScreen
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class SceneMotionTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    @OptIn(ExperimentalTestApi::class)
    private fun show(vararg commands: PlannerCommand, reduced: Boolean = false, fontScale: Float? = null, glass: Boolean = true) {
        runBlocking {
            store.apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(
                Appearance(start = Destination.WORKSPACE, reduceMotion = reduced, glass = glass))), *commands)
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

    private fun keepFrame(name: String, pixels: PixelMap) {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("captureMotion") != "yes") return
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = android.graphics.Bitmap.createBitmap(pixels.buffer, pixels.bufferOffset, pixels.stride,
            pixels.width, pixels.height, android.graphics.Bitmap.Config.ARGB_8888)
        try { java.io.File(context.getExternalFilesDir(null), "repair-$name.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }

    /** Compare the row's ink *and* the paper between glyphs. A solid landing tile has
     * the right bounds and background color but erases these edges until disposal. */
    private fun assertLivePatch(before: PixelMap, now: PixelMap, region: Rect) {
        val r = region.deflate(3f)
        var edges = 0
        var different = 0
        for (y in r.top.toInt() until r.bottom.toInt() step 2) for (x in r.left.toInt() until r.right.toInt() step 2) {
            if (delta(before[x, y], before[x + 2, y]) > .15f) edges++
            if (delta(before[x, y], now[x, y]) > .16f) different++
        }
        assertTrue("Expected visible text/controls in $region", edges > 15)
        assertTrue("Landing obscured or displaced the source at $region: $different changed / $edges edges",
            different <= maxOf(8, edges / 10))
    }

    private data class EdgeShot(val frame: Rect, val pixels: PixelMap)

    private fun edgeShot(tag: String, full: Rect, progress: Float): EdgeShot {
        val actual = windowRect(ui.onNodeWithTag(tag, useUnmergedTree = true))
        val dx = full.width * progress
        assertEquals("Back must follow the end edge, not a replacement", full.left + dx, actual.left, 2f)
        assertEquals("Do not shrink the clipped-away part", full.right + dx, actual.right, 2f)
        assertEquals(full.top, actual.top, 1f)
        assertEquals(full.bottom, actual.bottom, 1f)
        return EdgeShot(actual, pixels())
    }

    /** Check ink that differs from the *actual* newly uncovered parent. Comparing only
     * backgrounds (or testing bounds alone) would also pass a two-page crossfade. */
    private fun assertOpaqueEdge(full: Rect, front: PixelMap, parent: PixelMap, shot: EdgeShot) {
        assertUncoveredPixels(parent, shot.pixels, shot.frame)
        val dx = (shot.frame.left - full.left).roundToInt()
        val trim = (32 * ui.density.density).roundToInt()
        var distinct = 0
        var unchanged = 0
        for (y in maxOf(trim, full.top.toInt() + 2) until minOf(front.height - trim, full.bottom.toInt() - 2) step 2) {
            for (x in full.left.toInt() + 2 until minOf(full.right.toInt(), front.width - dx) - 3 step 2) {
                val a = front[x, y]
                val b = front[x + 2, y]
                if (delta(a, b) < .30f || delta(a, parent[x + dx, y]) < .30f) continue
                distinct++
                if (delta(a, shot.pixels[x + dx, y]) < .09f && delta(b, shot.pixels[x + dx + 2, y]) < .09f) unchanged++
            }
        }
        assertTrue("Moving front-page ink must remain opaque ($unchanged/$distinct)", distinct > 30 && unchanged >= distinct * .85f)
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
        assertEquals("Top must interpolate from the actual launcher: $origin / $box / $page", origin.top + (page.top - origin.top) * t, box.top, 3f)
        assertEquals(origin.left + (page.left - origin.left) * t, box.left, 3f)
    }

    private fun checkCardTrip(key: String, input: DirectNavigationEventInput) {
        val row = bounds(key)
        val startTitle = windowRect(ui.onNodeWithTag("row-title/$key", useUnmergedTree = true)).topLeft
        fun placedTitle() = windowRect(ui.onNodeWithTag("detail-title/$key", useUnmergedTree = true)).topLeft
        val before = pixels()
        val detail = "detail:$key"
        fun measuredPage() = ui.onNodeWithTag(detail).fetchSemanticsNode().size
        click(ui.onNodeWithTag(key))
        ui.mainClock.advanceTimeBy(128)
        val middle = frame(key)
        val pushTitle = placedTitle()
        val pushSize = measuredPage()
        ui.mainClock.advanceTimeBy(600)
        val open = frame(key)
        val restingTitle = placedTitle()
        val restingSize = measuredPage()
        fun checkTitlePath(box: Rect, placed: Offset) {
            val fraction = (box.height - row.height) / (open.box.height - row.height)
            val expected = androidx.compose.ui.geometry.lerp(startTitle, restingTitle, fraction)
            assertEquals("The title and aperture must share one x path", expected.x, placed.x, 3f)
            assertEquals("The title and aperture must share one y path", expected.y, placed.y, 3f)
        }
        assertEquals("Opening changes the painted aperture, not the measured page", restingSize, pushSize)
        checkTitlePath(middle.box, pushTitle)
        ui.onNodeWithTag(key).assertDoesNotExist() // The covered row is not an accessible action.
        between(row, middle.box, open.box)
        assertUncoveredPixels(before, middle.pixels, middle.box)
        assertDetailInk(open, middle)

        start(input); seek(input, .35f)
        val nearDetail = bounds(detail)
        between(row, nearDetail, open.box)
        assertEquals("Seeking must retain the resting page constraints", restingSize, measuredPage())
        checkTitlePath(nearDetail, placedTitle())
        assertEquals("Holding Back at 35% must track the finger, not the automatic playback curve",
            open.box.height + (row.height - open.box.height) * .35f, nearDetail.height, 3f)
        seek(input, .70f)
        val nearCard = frame(key)
        between(row, nearCard.box, open.box)
        assertUncoveredPixels(before, nearCard.pixels, nearCard.box)
        // At 70% Back the destination ink has handed off. The row's paper, title
        // and controls must already be intact, not a second moving detail title.
        assertLivePatch(before, nearCard.pixels, row)
        assertTrue("More back progress must contract the same surface", nearCard.box.height < nearDetail.height - 2f)
        seek(input, .30f)
        checkTitlePath(bounds(detail), placedTitle())
        assertTrue("Dragging the finger back must expand the surface again", bounds(detail).height > nearCard.box.height + 2f)
        assertEquals("Reversing Back must seek the same geometry immediately",
            open.box.height + (row.height - open.box.height) * .30f, bounds(detail).height, 3f)
        ui.runOnUiThread { input.backCancelled() }
        ui.mainClock.advanceTimeBy(600)
        assertEquals("Cancel must keep the original detail fully open", open.box, bounds(detail))
        assertUncoveredPixels(open.pixels, pixels(), Rect.Zero)
        start(input); seek(input, .55f); complete(input)
        assertEquals("Return must restore this ID at its old scroll position", row, bounds(key))
        ui.onNodeWithTag(detail).assertDoesNotExist()
    }

    @Test fun firstTaskPushAndReversiblePredictivePopUseTheScrolledRow() {
        show(*(1..18).map { PlannerCommand.TaskAdd(TaskInput("步骤 $it")) }.toTypedArray())
        val queue = runBlocking { store.state().queue }
        ui.onNodeWithTag("queue").performScrollToIndex(7)
        val originalList = ui.onNodeWithTag("queue").fetchSemanticsNode().id
        withClockAndBack { checkCardTrip("task:${queue[7].id}", it) }
        assertEquals("Back must return the very same live list, not rebuild it at the saved index",
            originalList, ui.onNodeWithTag("queue").fetchSemanticsNode().id)
        assertEquals(queue, runBlocking { store.state().queue })
    }

    @Test fun toolbarBackKeepsRealTaskAndNeighborsPlacedAndPaintedThroughDisposal() {
        show(*(1..3).map { PlannerCommand.TaskAdd(TaskInput("交接步骤 $it", minutes = 21 + it)) }.toTypedArray())
        val keys = runBlocking { store.state().queue.map { "task:${it.id}" } }
        val original = keys.map(::bounds)
        val before = pixels()
        val key = keys[1]
        val tag = "detail:$key"
        ui.onNodeWithTag(key).performClick()
        val full = bounds(tag)
        withClockAndBack {
            click(ui.onNode(hasContentDescription("返回") and hasClickAction()))
            ui.mainClock.advanceTimeBy(192)
            val lastFrames = ArrayDeque<Pair<Rect, PixelMap>>(2)
            var disposedFrames = 0
            repeat(18) {
                ui.mainClock.advanceTimeByFrame()
                val moving = ui.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                val shot = pixels()
                for (i in keys.indices) assertEquals("The list must reserve the real row, not the moving detail", original[i], bounds(keys[i]))
                if (moving == null || moving == full) {
                    // A released shared node can report full layout bounds for one frame
                    // before disposal. It must not actually paint any of that rectangle.
                    assertUncoveredPixels(before, shot, Rect.Zero)
                    for (row in original) assertLivePatch(before, shot, row)
                    if (moving == null) disposedFrames++
                } else {
                    assertUncoveredPixels(before, shot, moving)
                    if (lastFrames.size == 2) lastFrames.removeFirst()
                    lastFrames.addLast(moving to shot)
                }
            }
            assertEquals("Need the last two frames before the detail is removed", 2, lastFrames.size)
            keepFrame("entity-source", before)
            for ((i, sample) in lastFrames.withIndex()) keepFrame("entity-landing-$i", sample.second)
            for ((window, shot) in lastFrames) {
                assertLivePatch(before, shot, original[1])
                for (i in listOf(0, 2)) if (!window.overlaps(original[i])) assertLivePatch(before, shot, original[i])
            }
            assertTrue("Need frames on the other side of detail disposal", disposedFrames >= 2)
        }
    }

    @Test fun chromeIsRevealedInItsOwnSlotNeverCarriedUnderTheRootToolbar() {
        show(PlannerCommand.TaskAdd(TaskInput("报告提纲")), PlannerCommand.TaskAdd(TaskInput("取资料")))
        val key = "task:${runBlocking { store.state().queue.first().id }}"
        val source = bounds(key)
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
            fun check(f: DetailFrame, header: Rect, dock: Rect, detailAlpha: Float) {
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
                                (if (source.contains(p)) open.pixels[x, y].copy(alpha = detailAlpha).compositeOver(rootPixels[x, y])
                                    else open.pixels[x, y]).also { detailGlyphs++ }
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
                // Below the fixed heading the page is opaque except for its source
                // slot: the return is allowed to reveal exactly that row's real ink.
                val y0 = maxOf(visible.top, finalHeading.bottom + 4f).toInt()
                val y1 = minOf(visible.bottom, open.box.center.y).toInt()
                val x0 = maxOf(visible.left.toInt(), glyphLeft)
                val x1 = minOf(visible.right.toInt(), glyphRight + 1)
                var probes = 0
                var ghosts = 0
                for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) {
                    if (delta(open.pixels[x, y], paper) > .05f) continue
                    probes++
                    val expectedPaper = if (source.contains(Offset(x.toFloat(), y.toFloat())))
                        paper.copy(alpha = detailAlpha).compositeOver(rootPixels[x, y]) else paper
                    if (delta(f.pixels[x, y], expectedPaper) > .28f) ghosts++
                }
                checkedBlank += probes
                assertTrue("Displaced heading ink under the fixed toolbar: $ghosts/$probes", ghosts <= maxOf(2, probes / 700))
            }
            val openAtPush = (push.box.height - source.height) / (open.box.height - source.height)
            check(push, pushHeading, pushDock, ((openAtPush - .2f) / .2f).coerceIn(0f, 1f))
            start(input)
            for (progress in listOf(.12f, .30f, .45f, .56f, .68f)) {
                seek(input, progress)
                check(frame(key), windowRect(heading), windowRect(assistant), ((1f - progress - .6f) / .2f).coerceIn(0f, 1f))
            }
            assertTrue("Need glyphs from both visible toolbars and blank paper between them: $rootGlyphs/$detailGlyphs/$checkedBlank",
                rootGlyphs > 40 && detailGlyphs > 40 && checkedBlank > 200)
            complete(input)
        }
    }

    @Test fun backUsesTheNewLocationOfTheSameTaskAfterReorder() = checkReorderedLanding(glass = true)
    @Test fun solidCardReturnsToTheMovedTask() = checkReorderedLanding(glass = false)

    private fun checkReorderedLanding(glass: Boolean) {
        show(*(1..3).map { PlannerCommand.TaskAdd(TaskInput("步骤 $it")) }.toTypedArray(), glass = glass)
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
            val first = windowRect(ui.onNodeWithTag("detail:$key"))
            ui.mainClock.advanceTimeBy(600)
            val full = windowRect(ui.onNodeWithTag("detail:$key"))
            assertEquals("A half-obscured row must not supply vertical geometry", full.top, first.top, 1f)
            assertEquals(full.bottom, first.bottom, 1f)
            assertEquals("Do not expand from the hidden part of a row", full.width, first.width, 1f)
            val front = pixels()
            start(input); seek(input, .55f)
            val half = edgeShot("detail:$key", full, .55f)
            seek(input, .25f)
            val reversed = edgeShot("detail:$key", full, .25f)
            complete(input)
            val parent = pixels()
            assertOpaqueEdge(full, front, parent, half)
            assertOpaqueEdge(full, front, parent, reversed)
        }
    }

    @Test fun heldAndReversedBackNeverMovesLargeTypeTitleAcrossBackButton() {
        show(PlannerCommand.TaskAdd(TaskInput("A - Outline")), fontScale = 2f)
        val key = "task:${runBlocking { store.state().queue.single().id }}"
        val source = bounds(key)
        val before = pixels()
        ui.onNodeWithTag(key).performClick()
        val open = frame(key)
        withClockAndBack { input ->
            start(input)
            for (progress in listOf(.12f, .20f, .30f, .50f, .80f, .98f, .50f, .12f)) {
                seek(input, progress)
                val moving = frame(key)
                val paintedTitle = if (progress < .4f) moving.title else ui.onNodeWithTag(
                    "row-title/$key", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("The visible title must stay inside the moving surface at $progress",
                    moving.box.inflate(1f).contains(paintedTitle.center))
                assertFalse("The clipped Back control must never cover the painted title", paintedTitle.overlaps(backHit()))
                keepFrame("large-back-${(progress * 100).roundToInt()}", moving.pixels)
                assertUncoveredPixels(before, moving.pixels, moving.box)
                if (progress <= .12f) assertDetailInk(open, moving)
                if (progress >= .50f) assertLivePatch(before, moving.pixels, source)
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

    private fun tracePlacement(value: () -> Float?): List<Pair<Long, Float>> = buildList {
        repeat(28) {
            ui.mainClock.advanceTimeByFrame()
            value()?.let { add(ui.mainClock.currentTime to it) }
        }
    }

    private fun checkTweenTravel(samples: List<Pair<Long, Float>>, start: Float, end: Float, remaining: Float = 1f) {
        val native = tween<Float>()
        fun nativeTime(distance: Float) = (0..native.durationMillis).first {
            native.easing.transform(it.toFloat() / native.durationMillis) >= distance
        }
        val travel = samples.map { (time, position) -> time to (position - start) / (end - start) }
        // Compare elapsed time between actual placements, not time since click:
        // the source and destination have to be placed before they can be paired.
        fun crossed(distance: Float): Double = travel.zipWithNext().firstNotNullOfOrNull { (a, b) ->
            if (a.second > 0f && a.second <= distance && b.second >= distance && b.second < 1f && b.second > a.second)
                a.first + (b.first - a.first) * ((distance - a.second) / (b.second - a.second)).toDouble()
            else null
        } ?: error("No moving frame crossing $distance in $travel")
        val startTime = crossed(.30f)
        for (distance in listOf(.65f, .95f)) {
            assertEquals("Measured container travel from 30% to ${distance * 100}%: $travel",
                (nativeTime(distance) - nativeTime(.30f)) * remaining.toDouble(), crossed(distance) - startTime, 24.0)
        }
    }

    @Test fun helpPaneOpensAndRecoversFromBackAlongTheSamePlacedPath() {
        ui.setContent {
            StackLifeTheme(Appearance()) { HelpAboutScreen(back = { error("Unexpected exit") }, onGuide = { error("Unexpected guide") }) }
        }
        val launcher = ui.onNodeWithTag("about-help").performScrollTo()
        val location = launcher.fetchSemanticsNode().boundsInRoot
        val sourcePixels = pixels()
        val tag = "pane:HELP"
        fun window() = ui.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        withClockAndBack { input ->
            click(launcher)
            // The guide is a LazyColumn item under Scaffold insets, not the
            // container. Compare the actual clipped surface with its real row.
            val opening = tracePlacement { window()?.height }
            val full = checkNotNull(window())
            val helpPixels = pixels()
            checkTweenTravel(opening, location.height, full.height)
            fun checkSeek(progress: Float): Rect {
                val landing = ui.onNodeWithTag("about-help", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertEquals("The same row is available throughout the gesture", location, landing)
                val box = checkNotNull(window())
                fun edge(parent: Float, source: Float, actual: Float) = assertEquals(
                    "Pane at $progress: $full → $landing, actual $box", parent + (source - parent) * progress, actual, 3f)
                edge(full.top, landing.top, box.top); edge(full.bottom, landing.bottom, box.bottom)
                edge(full.left, landing.left, box.left); edge(full.right, landing.right, box.right)
                return box
            }
            start(input); checkSeek(0f)
            seek(input, .75f)
            val held = checkSeek(.75f)
            val backPixels = pixels()
            keepFrame("pane-help-source", sourcePixels)
            keepFrame("pane-help-open", helpPixels)
            keepFrame("pane-help-back-75", backPixels)
            assertUncoveredPixels(sourcePixels, backPixels, held)
            assertLivePatch(sourcePixels, backPixels, location)
            seek(input, .25f); checkSeek(.25f)
            seek(input, .75f); assertEquals(held, checkSeek(.75f))
            ui.runOnUiThread { input.backCancelled() }
            checkTweenTravel(tracePlacement { window()?.height }, held.height, full.height, remaining = .75f)
            assertEquals(full, window())
            assertUncoveredPixels(helpPixels, pixels(), Rect.Zero)
            start(input); seek(input, .5f); complete(input)
            assertEquals(location, launcher.fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun launcherOpenAndToolbarBackFollowTheDefaultComposeTween() {
        show()
        val fab = ui.onNodeWithTag("add-task")
        val compact = fab.fetchSemanticsNode().boundsInRoot.height
        val route = "route:add-task"
        fun record(button: SemanticsNodeInteraction): List<Pair<Long, Float>> {
            click(button)
            return tracePlacement { ui.onAllNodesWithTag(route).fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.height }
        }
        withClockAndBack {
            val opening = record(fab)
            val full = bounds(route).height
            checkTweenTravel(opening, compact, full)
            val back = ui.onNode(hasAnyAncestor(hasTestTag(route)) and hasContentDescription("返回") and hasClickAction())
            checkTweenTravel(record(back), full, compact)
            ui.onNodeWithTag(route).assertDoesNotExist()
            assertEquals(compact, bounds("add-task").height, 0f)
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
            assertEquals("The underlying FAB must not ride the editor's IME", fab, bounds("add-task"))
            between(fab, partial, page)
            seek(input, .65f)
            val smaller = bounds(route)
            assertEquals("More Back progress must keep the same stationary launcher", fab, bounds("add-task"))
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

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun darkWallpaperAndGlassKeepBothPagesPaintedThroughPushBackAndCancel() = wallpaperTrip(glass = true)

    @Test fun darkWallpaperAndSolidControlsKeepBothPagesPaintedThroughPushBackAndCancel() = wallpaperTrip(glass = false)

    private fun wallpaperTrip(glass: Boolean) {
        val context = ui.activity.applicationContext
        val folder = Files.createTempDirectory(context.cacheDir.toPath(), "scene-photo-").toFile()
        val id = UUID.randomUUID().toString()
        val photos = BackgroundStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }) { setOf(id) }
        val api = RoomPlannerApi(store.db, imageExists = photos::exists)
        try {
            runBlocking { photos.recover() }
            val photo = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888)
            try {
                photo.eraseColor(android.graphics.Color.rgb(128, 12, 18))
                Canvas(photo).drawRect(32f, 0f, 64f, 128f, Paint().apply { color = android.graphics.Color.rgb(12, 18, 144) })
                File(File(folder, "background-images"), id).outputStream().use { check(photo.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { photo.recycle() }
            val appearance = Appearance(start = Destination.WORKSPACE, theme = ThemeMode.DARK, glass = glass,
                pageBackgrounds = mapOf(Destination.WORKSPACE to WallpaperSpec(id, tint = 0f)))
            runBlocking {
                val change = api.preview(listOf(PlannerCommand.EditAppearance(AppearancePatch.Replace(appearance)),
                    PlannerCommand.TaskAdd(TaskInput("Pixel witness / 交接")))) as PreviewResult.Ready
                assertTrue(api.commit(change.proposalId) is CommitResult.Applied)
            }
            ui.setContent { CompositionLocalProvider(LocalBackgroundStore provides photos) {
                StackLifeApp(viewModel { WorkspaceViewModel(api) })
            } }
            val route = "route:settings"
            val gear = ui.onNode(hasContentDescription(context.getString(R.string.ui_settings)) and hasClickAction())
            val taskId = runBlocking { store.state().queue.single().id }
            val queueTitle = ui.onNodeWithTag("row-title/task:$taskId", useUnmergedTree = true)
            ui.waitUntil(12_000) { ui.onAllNodesWithTag("queue").fetchSemanticsNodes().isNotEmpty() }
            val material = context.getString(if (glass) R.string.ui_glass_capsule else R.string.ui_solid_capsule)
            ui.onAllNodes(hasStateDescription(material)).onFirst().assertExists()
            fun photoVisible(map: PixelMap): Boolean {
                val left = map[map.width / 4, map.height * 3 / 5]
                val right = map[map.width * 3 / 4, map.height * 3 / 5]
                return left.red - left.blue > .18f && right.blue - right.red > .18f
            }
            ui.waitUntil(12_000) { photoVisible(pixels()) }
            val parent = pixels()
            val row = windowRect(queueTitle)
            val dock = bounds("navigation-capsule")
            val launcher = windowRect(gear)
            gear.performClick()
            ui.waitForIdle()
            val settled = pixels()
            val page = bounds(route)
            val settingsLabel = ui.onNode(hasAnyAncestor(hasTestTag(route)) and hasText(context.getString(R.string.ui_settings)),
                useUnmergedTree = true)
            val label = windowRect(settingsLabel)
            val wake = windowRect(ui.onNodeWithTag("routine-wake"))
            assertFalse("Settings must not inherit the workspace-only photo", photoVisible(settled))
            val capture = "dark-wallpaper-${if (glass) "glass" else "solid"}"
            keepFrame("$capture-parent", parent)
            keepFrame("$capture-settings", settled)
            ui.onNode(hasAnyAncestor(hasTestTag(route)) and hasContentDescription(context.getString(R.string.ui_back)) and hasClickAction()).performClick()
            ui.waitForIdle()

            // Match pairs of real glyph/control edge pixels, including the neighbour's contrast.
            // A black frame, a plain theme fill, and the wallpaper alone all score zero.
            fun matches(reference: PixelMap, patch: Rect, shot: PixelMap, offset: Offset): Int {
                var matched = 0
                val dx = offset.x.roundToInt()
                val dy = offset.y.roundToInt()
                for (y in patch.top.toInt().coerceAtLeast(0) until patch.bottom.toInt().coerceAtMost(reference.height) step 2)
                    for (x in patch.left.toInt().coerceAtLeast(0) until patch.right.toInt().coerceAtMost(reference.width - 2) step 2) {
                        val xx = x + dx; val yy = y + dy
                        if (xx !in 0 until shot.width - 2 || yy !in 0 until shot.height) continue
                        val a = reference[x, y]; val b = reference[x + 2, y]
                        if (delta(a, b) > .3f && delta(a, shot[xx, yy]) < .16f && delta(b, shot[xx + 2, yy]) < .16f) matched++
                    }
                return matched
            }
            withClockAndBack { input ->
                fun checkFrame(stage: String) {
                    val box = ui.onAllNodesWithTag(route).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                    val shot = pixels()
                    if (stage == "push 6" || stage == "back 0.5" || stage == "back 0.98" || stage == "cancel 4")
                        keepFrame("$capture-${stage.replace(' ', '-')}", shot)
                    val rootInk = matches(parent, row, shot, Offset.Zero) + matches(parent, dock, shot, Offset.Zero)
                    val pageInk = if (box == null) 0 else matches(settled, label, shot, box.topLeft - page.topLeft)
                    assertTrue("$stage: missing actual queue, dock and settings ink ($rootInk/$pageInk), box=$box",
                        rootInk + pageInk >= 12)
                    if (box != null && box.width < page.width - 2f && box.height > page.height * .88f) {
                        assertTrue("$stage: missing settings label inside the nearly open reveal ($pageInk)", pageInk >= 12)
                    }
                    if (stage == "back 0.5") {
                        val reveal = checkNotNull(box).deflate(4f)
                        val offset = box.topLeft - page.topLeft
                        val visibleWake = wake.intersect(reveal.translate(-offset))
                        assertTrue("Need real settings row pixels inside the reveal and outside the launcher",
                            !visibleWake.isEmpty && !visibleWake.translate(offset).overlaps(launcher))
                        val evidence = matches(settled, visibleWake, settled, Offset.Zero)
                        val visible = matches(settled, visibleWake, shot, offset)
                        assertTrue("50% Back: missing settings row ink in the covered area ($visible/$evidence)",
                            evidence >= 12 && visible >= evidence * .8f)
                    }
                    if (box != null && !box.inflate(3f).overlaps(row)) assertLivePatch(parent, shot, row)
                    // Compare the photographic material separately, where neither destination nor
                    // controls can cover it. A still-drawn toolbar must not excuse a lost wallpaper.
                    for (x in listOf(shot.width / 4, shot.width * 3 / 4)) {
                        val y = shot.height * 3 / 5
                        if (box?.inflate(3f)?.contains(Offset(x.toFloat(), y.toFloat())) != true)
                            assertTrue("$stage: missing uncovered workspace wallpaper at ($x,$y)", delta(parent[x, y], shot[x, y]) < .05f)
                    }
                }
                click(gear)
                repeat(22) { i -> ui.mainClock.advanceTimeByFrame(); checkFrame("push $i") }
                ui.mainClock.advanceTimeBy(600)
                assertLivePatch(settled, pixels(), label)
                start(input)
                // Reverse before cancellation as well. At a held fraction the real row must
                // still have its settled width and position relative to the top of the window.
                for (p in listOf(.02f, .20f, .50f, .80f, .20f, .80f, .98f)) {
                    seek(input, p)
                    val frame = bounds(route)
                    val expected = androidx.compose.ui.geometry.lerp(page, launcher, p)
                    assertEquals(expected.left, frame.left, 2f); assertEquals(expected.top, frame.top, 2f)
                    assertEquals(expected.right, frame.right, 2f); assertEquals(expected.bottom, frame.bottom, 2f)
                    val expectedWake = wake.translate(frame.topLeft - page.topLeft)
                    val actualWake = windowRect(ui.onNodeWithTag("routine-wake"))
                    assertEquals(expectedWake.left, actualWake.left, 1f); assertEquals(expectedWake.right, actualWake.right, 1f)
                    assertEquals(expectedWake.top, actualWake.top, 1f); assertEquals(expectedWake.bottom, actualWake.bottom, 1f)
                    checkFrame("back $p")
                }
                ui.runOnUiThread { input.backCancelled() }
                repeat(22) { i -> ui.mainClock.advanceTimeByFrame(); checkFrame("cancel $i") }
                ui.mainClock.advanceTimeBy(600)
                assertLivePatch(settled, pixels(), label)
                start(input); seek(input, .98f)
                assertLivePatch(parent, pixels(), row)
                if (glass) {
                    // A retained hardware layer must still repaint real mutations in the
                    // exposed page even while the gesture and the destination are stationary.
                    val renamed = "WWWWWWWW"
                    runBlocking { store.apply(PlannerCommand.TaskEdit(taskId, title = renamed)) }
                    ui.waitUntil(5_000) {
                        ui.mainClock.advanceTimeByFrame()
                        ui.onAllNodesWithText(renamed).fetchSemanticsNodes().isNotEmpty()
                    }
                    val live = pixels()
                    val patch = windowRect(queueTitle)
                    var changed = 0
                    for (y in patch.top.toInt() until patch.bottom.toInt() step 2)
                        for (x in patch.left.toInt() until patch.right.toInt() step 2)
                            if (delta(parent[x, y], live[x, y]) > .25f) changed++
                    assertTrue("Held Back still shows the old task title", changed > 12)
                    complete(input)
                    assertLivePatch(pixels(), live, patch)
                } else {
                    complete(input)
                    assertLivePatch(parent, pixels(), row)
                }
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun settingsReturnsToTheWindowEdgeWhenAnotherTabReplacesItsGear() {
        show(glass = false)
        val tag = "route:settings"
        val settings = ui.activity.getString(R.string.ui_settings)
        val emptyGraph = ui.activity.getString(R.string.ui_no_graphs)
        ui.onNode(hasContentDescription(settings) and hasClickAction()).performClick()
        val page = windowRect(ui.onNodeWithTag(tag))
        val before = pixels()
        runBlocking { store.apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(
            Appearance(start = Destination.GRAPHS, visible = setOf(Destination.GRAPHS), glass = false)))) }
        withClockAndBack { input ->
            start(input)
            ui.waitUntil(12_000) {
                ui.mainClock.advanceTimeByFrame()
                ui.onAllNodesWithText(emptyGraph).fetchSemanticsNodes().isNotEmpty()
            }
            seek(input, .5f)
            val midway = edgeShot(tag, page, .5f)
            seek(input, .25f)
            val reversed = edgeShot(tag, page, .25f)
            complete(input)
            val after = pixels()
            assertOpaqueEdge(page, before, after, midway)
            assertOpaqueEdge(page, before, after, reversed)
        }
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

    @Test fun nestedPredictiveLandingRestoresLauncherInkBeforeCommitAndCanReverse() {
        show()
        ui.onNode(hasContentDescription("设置") and hasClickAction()).performClick()
        val inSettings = hasAnyAncestor(hasTestTag("route:settings"))
        val row = ui.onNode(inSettings and hasText("布局") and hasClickAction())
        val adjacent = ui.onNode(inSettings and hasText("色彩") and hasClickAction())
        val origin = row.fetchSemanticsNode().boundsInRoot
        val neighbor = adjacent.fetchSemanticsNode().boundsInRoot
        val parent = bounds("route:settings")
        val before = pixels()
        val tag = "route:appearance:layout"
        row.performClick()
        withClockAndBack { input ->
            start(input)
            for (fraction in listOf(.94f, .98f, .995f)) {
                seek(input, fraction)
                ui.onNodeWithTag(tag).assertExists()
                assertEquals(parent, bounds("route:settings"))
                assertEquals(origin, row.fetchSemanticsNode().boundsInRoot)
                val frame = pixels()
                keepFrame("settings-source", before)
                keepFrame("settings-return-${(fraction * 1000).roundToInt()}", frame)
                assertLivePatch(before, frame, origin)
                val reveal = bounds(tag)
                assertUncoveredPixels(before, frame, reveal)
                assertLivePatch(before, frame, neighbor.copy(bottom = minOf(neighbor.bottom, reveal.top - 2f)))
            }
            seek(input, .45f)
            between(origin, bounds(tag), parent)
            ui.runOnUiThread { input.backCancelled() }
            ui.mainClock.advanceTimeBy(600)
            assertEquals(parent, bounds(tag))
            start(input); seek(input, .995f)
            ui.runOnUiThread { input.backCompleted() }
            repeat(4) {
                ui.mainClock.advanceTimeByFrame()
                assertLivePatch(before, pixels(), origin)
            }
            ui.mainClock.advanceTimeBy(600)
            ui.onNodeWithTag(tag).assertDoesNotExist()
            assertEquals(parent, bounds("route:settings"))
            assertLivePatch(before, pixels(), origin)
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
        // The parent will use B in the same list slot while the detail is still open.
        runBlocking { store.apply(PlannerCommand.TaskFinish(a.id)) }
        ui.waitUntil(12_000) { ui.onAllNodes(hasText(ui.activity.getString(io.github.nakanomk.stacklife.R.string.today_done)) and hasAnyAncestor(hasTestTag(key))).fetchSemanticsNodes().size == 1 }
        val full = windowRect(ui.onNodeWithTag(key))
        val before = pixels()
        withClockAndBack { input ->
            start(input); seek(input, .50f)
            val midway = edgeShot(key, full, .50f)
            seek(input, .25f)
            val reversed = edgeShot(key, full, .25f)
            complete(input)
            val after = pixels()
            assertOpaqueEdge(full, before, after, midway)
            assertOpaqueEdge(full, before, after, reversed)
        }
        ui.onNodeWithTag("task:${b.id}").assertIsDisplayed()
        ui.onNodeWithTag("task:${a.id}").assertDoesNotExist()
    }

    @Test fun restoredHelpPaneReturnsOpaquelyWithoutBorrowingTheNewLauncher() {
        val restore = StateRestorationTester(ui)
        restore.setContent {
            StackLifeTheme(Appearance(glass = false)) {
                HelpAboutScreen(back = { error("Unexpected exit") }, onGuide = { error("Unexpected guide") })
            }
        }
        ui.onNodeWithTag("about-help").performScrollTo().performClick()
        restore.emulateSavedInstanceStateRestore() // Restores the pane, not a no-longer-placed origin.
        val tag = "pane:HELP"
        val full = windowRect(ui.onNodeWithTag(tag, useUnmergedTree = true))
        val front = pixels()
        withClockAndBack { input ->
            start(input); seek(input, .5f)
            val midway = edgeShot(tag, full, .5f)
            seek(input, .25f)
            val reversed = edgeShot(tag, full, .25f)
            complete(input)
            val parent = pixels()
            assertOpaqueEdge(full, front, parent, midway)
            assertOpaqueEdge(full, front, parent, reversed)
        }
        ui.onNodeWithTag("about-help").assertIsDisplayed()
    }

    @Test fun losingTheHeldCardCarriesItsCurrentWindowAndCanRetraceIt() {
        show(PlannerCommand.TaskAdd(TaskInput("移动中的任务")), PlannerCommand.TaskAdd(TaskInput("保留的任务")))
        val (a, b) = runBlocking { store.state().queue }
        val source = "task:${a.id}"
        val key = "detail:$source"
        val row = bounds(source)
        ui.onNodeWithTag(source).performClick()
        val full = bounds(key)
        fun checkRect(label: String, expected: Rect) {
            // layoutInfo.coordinates is inside the inverse content transform; its
            // full-size design plane isn't the window the user can see.
            val actual = bounds(key)
            val visible = expected.intersect(full)
            assertEquals(label, visible.left, actual.left, 2f); assertEquals(label, visible.right, actual.right, 2f)
            assertEquals(label, visible.top, actual.top, 2f); assertEquals(label, visible.bottom, actual.bottom, 2f)
        }
        withClockAndBack { input ->
            start(input); seek(input, .4f)
            val held = bounds(key)
            between(row, held, full)
            runBlocking { store.apply(PlannerCommand.TaskFinish(a.id)) }
            ui.waitUntil(12_000) {
                ui.mainClock.advanceTimeByFrame()
                ui.onAllNodes(hasText(ui.activity.getString(R.string.today_done)) and hasAnyAncestor(hasTestTag(key)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            checkRect("Losing the original cannot change the held frame", held)
            // The queue independently animates its surviving row into the vacant
            // slot. Hold the navigation seek until that real parent is stationary.
            ui.mainClock.advanceTimeBy(1200)
            checkRect("The held gesture cannot drift with the queue's row motion", held)
            seek(input, .7f)
            val carried = held.translate((full.right - held.left) / 2f, 0f)
            checkRect("Carry the painted window, not its old landing rectangle", carried)
            val forward = pixels()
            seek(input, .4f)
            checkRect("Reverse to the exact point of loss", held)
            seek(input, .2f)
            checkRect("Reverse past the point of loss toward the real detail", androidx.compose.ui.geometry.lerp(held, full, .5f))
            complete(input)
            val parent = pixels()
            keepFrame("row-loss-parent", parent); keepFrame("row-loss-carried", forward)
            assertUncoveredPixels(parent, forward, carried)
        }
        ui.onNodeWithTag("task:${b.id}").assertIsDisplayed()
        ui.onNodeWithTag("task:${a.id}").assertDoesNotExist()
    }
}
