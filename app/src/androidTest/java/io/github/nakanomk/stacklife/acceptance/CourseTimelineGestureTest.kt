package io.github.nakanomk.stacklife.acceptance

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.LocalPlannerGlassAllowed
import io.github.nakanomk.stacklife.appearance.glassUnavailableReason
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import java.io.File
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseTimelineGestureTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)

    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(10_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
        return ui.onNode(matcher)
    }

    @Test fun whiteCourseNameRoomAndConflictHaveLocalContrastOnABrightTile() {
        val term = TermSpec("Studio-7", LocalDate.of(2026, 11, 16), 1, BellSchedule.SUMMER)
        val course = ScheduledCourse(term.id, HUST_SOURCE, term.meeting(1, 1, "物理", "A", 1, 2), listOf("预约"))
        val week = CourseWeek(term, 1, listOf(course), mapOf("物理" to 0))
        val settings = mutableStateOf(Appearance())
        ui.setContent { MaterialTheme { CompositionLocalProvider(LocalAppearance provides settings.value) {
            WeekGrid(term, week, 0.dp, 0.dp) {}
        } } }
        for (edged in listOf(true, false)) {
            ui.runOnIdle { settings.value = settings.value.copy(courseTextOutline = edged) }
            for (label in listOf("物理", "@A", "冲突")) assertCourseInk(label, edged)
        }
    }

    @Test fun glassTransmitsTheActualThirdPeriodRuleThroughACourseButSolidDoesNot() {
        val term = TermSpec("Studio-7", LocalDate.of(2026, 11, 16), 1, BellSchedule.SUMMER)
        val meeting = term.meeting(1, 1, "物理", "A", 1, 4)
        val week = CourseWeek(term, 1, listOf(ScheduledCourse(term.id, HUST_SOURCE, meeting)), mapOf(meeting.title to 0))
        val appearance = mutableStateOf(Appearance(theme = ThemeMode.LIGHT, palette = PaletteMode.SEED))
        var available = false
        ui.setContent {
            StackLifeTheme(appearance.value) {
                available = glassUnavailableReason(appearance.value) == null
                CompositionLocalProvider(LocalPlannerGlassAllowed provides true) { WeekGrid(term, week, 0.dp, 0.dp) {} }
            }
        }
        ui.runOnIdle { assumeTrue("Needs a hardware-accelerated optical surface", available) }
        fun ruleContrast(solid: Boolean): Float {
            val node = ui.onNodeWithTag("grid-course:0").fetchSemanticsNode()
            val topLeft = ui.runOnIdle { node.positionOnScreen }
            val width = node.size.width
            val height = node.size.height
            val xs = (topLeft.x.toInt() + width / 3) until (topLeft.x.toInt() + width * 2 / 3)
            val y = topLeft.y.toInt() + height * 3 / 4
            val bodyY = y + height / 16
            val swatch = courseColor(term.id, 0).toArgb()
            var result = 0f
            ui.waitUntil(timeoutMillis = 10_000) {
                val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val pixels = if (capture.config == Bitmap.Config.HARDWARE) {
                    capture.copy(Bitmap.Config.ARGB_8888, false).also { capture.recycle() }
                } else capture
                try {
                    val pixel = pixels.getPixel(topLeft.x.toInt() + width / 2, bodyY)
                    val body = Color(pixel)
                    if (if (solid) pixel != swatch else maxOf(body.red, body.green, body.blue) - minOf(body.red, body.green, body.blue) < .14f) return@waitUntil false
                    fun line(row: Int): Float {
                        var sum = 0f
                        for (x in xs) sum += Color(pixels.getPixel(x, row)).luminance()
                        return sum / xs.count()
                    }
                    val interior = line(bodyY)
                    result = (y - height / 40..y + height / 40).maxOf { kotlin.math.abs(line(it) - interior) }
                    true
                } finally { pixels.recycle() }
            }
            return result
        }
        val glass = ruleContrast(solid = false)
        assertTrue("The transmitted period rule must remain visible inside the course (ΔL=$glass)", glass > .025f)
        ui.runOnIdle { appearance.value = appearance.value.copy(glass = false) }
        val solid = ruleContrast(solid = true)
        assertTrue("The solid fallback must completely cover the period rule (ΔL=$solid)", solid < .008f)
    }

    @Test fun longCourseCapsAndRulesStayRegisteredAtRestPressedAndScrolled() {
        val term = TermSpec("Studio-7", LocalDate.of(2026, 9, 7), 1, BellSchedule.SUMMER)
        fun tile(day: Int, name: String, first: Int, last: Int) =
            ScheduledCourse(term.id, HUST_SOURCE, term.meeting(1, day, name, "", first, last))
        // Short, left-aligned names leave a text-free vertical strip even in the half-width lanes.
        val classes = listOf(tile(1, "L", 1, 12), tile(2, "I", 2, 7), tile(2, "J", 2, 7),
            tile(3, "T", 2, 5), tile(3, "U", 1, 1), tile(3, "V", 6, 6))
        val week = CourseWeek(term, 1, classes, classes.mapIndexed { i, c -> c.meeting.title to i }.toMap())
        val appearance = Appearance(theme = ThemeMode.LIGHT, palette = PaletteMode.SEED)
        var available = false
        var opened: ScheduledCourse? = null
        ui.setContent {
            StackLifeTheme(appearance) {
                available = glassUnavailableReason(appearance) == null
                CompositionLocalProvider(LocalPlannerGlassAllowed provides true) {
                    Box(Modifier.wrapContentHeight(Alignment.Top).height(420.dp)) {
                        WeekGrid(term, week, 0.dp, 24.dp) { opened = it }
                    }
                }
            }
        }
        ui.runOnIdle { assumeTrue("Needs an accelerated API 33+ optical surface", available) }
        val dp = ui.density.density
        val gap = dp.roundToInt()
        fun course(i: Int) = ui.onNodeWithTag("grid-course:$i")
        fun bounds(i: Int): Rect {
            val node = course(i).fetchSemanticsNode()
            val origin = ui.runOnIdle { node.positionOnScreen }
            val x = origin.x.roundToInt(); val y = origin.y.roundToInt()
            return Rect(x, y, x + node.size.width, y + node.size.height)
        }
        val long = bounds(0); val lane = bounds(1); val four = bounds(3)
        val row = (long.height() + 2 * gap) / 12f
        val gridX = four.centerX() + four.width() + 2 * gap // Empty Thursday, same grid layer.
        val touchX = four.centerX()
        val touchY = four.top + four.height() * 5 / 8
        val rest = opticalFrame("rest") { frame ->
            val ink = Color(frame.getPixel(touchX, touchY))
            maxOf(ink.red, ink.green, ink.blue) - minOf(ink.red, ink.green, ink.blue) > .12f
        }
        try {
            course(3).performTouchInput { down(percentOffset(.5f, .625f)); advanceEventTime(350) }
            ui.mainClock.advanceTimeBy(400)
            val pressed = try {
                opticalFrame("pressed") { it.getPixel(touchX, touchY) != rest.getPixel(touchX, touchY) }
            } finally { course(3).performTouchInput { up() } }
            try {
                ui.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("week-grid")))
                    .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 12 * row) }
                ui.waitForIdle()
                val lower = bounds(0); val lowerLane = bounds(1)
                val scrolled = opticalFrame("scrolled") { frame ->
                    // The area below the registered closing cap is now the trailing spacer.
                    val inside = Color(frame.getPixel(lower.centerX(), lower.bottom - (8 * dp).roundToInt()))
                    val outside = Color(frame.getPixel(lower.centerX(), lower.bottom + (8 * dp).roundToInt()))
                    abs(inside.luminance() - outside.luminance()) > .05f
                }
                try {
                    // Collect all three captures before comparing so the identical test APK can
                    // also preserve the failing 0.9.0 rendering, not just the first failure.
                    assertEquals(classes[3], opened)
                    assertTrue(lower.top < long.top - row)
                    fun lum(image: Bitmap, x: Int, y: Int) = Color(image.getPixel(x, y)).luminance()
                    fun cap(image: Bitmap, tile: Rect, top: Boolean) {
                        val edge = if (top) tile.top else tile.bottom - 1
                        val sign = if (top) 1 else -1
                        val end = (row * .55f).roundToInt()
                        // Beyond the corner and 2dp optical rim, no source rule exists in
                        // this half-period. Check individual pixels, not an average hiding an arc.
                        val margin = (3 * dp).roundToInt()
                        for (x in tile.left + margin until tile.right - margin) {
                            val body = lum(image, x, edge + sign * end)
                            for (d in (4 * dp).roundToInt() until end) {
                                // Only the real label occupies the upper left of the first row.
                                if (top && d < 24 * dp && x < tile.left + 13 * dp) continue
                                val l = lum(image, x, edge + sign * d)
                                if (body - l >= .023f) throw AssertionError("Extra stroke at cap ($x,${edge + sign * d}), ΔL=${body - l}")
                            }
                        }
                    }
                    fun rule(image: Bitmap, tile: Rect, y: Int) {
                        val radius = (3 * dp).roundToInt()
                        fun darkest(x: Int) = (y - radius..y + radius).minBy { lum(image, x, it) }
                        val source = darkest(gridX)
                        for (x in listOf(tile.left + radius, tile.centerX(), tile.right - radius - 1)) {
                            val line = darkest(x)
                            assertTrue("Lost period rule at ($x,$y)", lum(image, x, y + 2 * radius) - lum(image, x, line) > .02f)
                            assertTrue("Bent period rule at $x: $line vs source $source", abs(line - source) <= gap + 1)
                        }
                    }
                    fun boundary(image: Bitmap, tile: Rect, top: Boolean) {
                        val dir = if (top) 1 else -1
                        val y = if (top) tile.top - gap else tile.bottom + gap
                        val gapRows = if (top) y until tile.top else tile.bottom until y
                        // The first and closing strokes belong to the grid, not the tile rim.
                        // Across the unpainted gap their pixels must match the empty day.
                        val sourceY = gapRows.minBy { lum(image, gridX, it) }
                        val source = lum(image, gridX, sourceY)
                        assertTrue("Missing source boundary at ($gridX,$y)", lum(image, gridX, y + dir * 5 * gap) - source > .025f)
                        assertTrue("Course covers source boundary at ($gridX,$sourceY)", abs(lum(image, tile.centerX(), sourceY) - source) < .025f)
                    }
                    cap(rest, long, true); cap(rest, lane, true); cap(rest, four, false)
                    cap(pressed, four, true); cap(pressed, four, false)
                    cap(scrolled, lowerLane, false); cap(scrolled, lower, false)
                    boundary(rest, long, true); boundary(scrolled, lower, false)
                    rule(rest, long, (long.top - gap + 3 * row).roundToInt())
                    rule(rest, lane, (lane.top - gap + 2 * row).roundToInt())
                    rule(pressed, four, (four.top - gap + row).roundToInt())
                    rule(scrolled, lower, (lower.bottom + gap - row).roundToInt())
                    // Actual classes immediately above and below the pressed tile: neither
                    // their pigment nor the separating rule may change under a held pointer.
                    for (edge in listOf(four.top - 1, four.bottom)) {
                        val dir = if (edge < four.top) -1 else 1
                        for (d in 0..(7 * dp).roundToInt()) for (x in four.centerX() until four.right - 4 * gap) {
                            val y = edge + dir * d
                            if (abs(lum(rest, x, y) - lum(pressed, x, y)) >= .012f) throw AssertionError("Press escaped its cell at ($x,$y)")
                        }
                    }
                } finally { scrolled.recycle() }
            } finally { pressed.recycle() }
        } finally { rest.recycle() }
    }

    private fun opticalFrame(name: String, ready: (Bitmap) -> Boolean): Bitmap {
        val inst = InstrumentationRegistry.getInstrumentation()
        var result: Bitmap? = null
        ui.waitUntil(10_000) {
            val raw = inst.uiAutomation.takeScreenshot() ?: return@waitUntil false
            val frame = if (raw.config == Bitmap.Config.HARDWARE) raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() } else raw
            if (ready(frame)) { result = frame; true } else { frame.recycle(); false }
        }
        val frame = requireNotNull(result)
        val label = InstrumentationRegistry.getArguments().getString("courseOpticsCapture")
        if (label == "before" || label == "after") {
            File(inst.targetContext.getExternalFilesDir(null), "course-$label-$name.png").outputStream().use {
                check(frame.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        return frame
    }

    private fun assertCourseInk(label: String, edged: Boolean) {
        val pixels = ui.onNodeWithText(label, useUnmergedTree = true).captureToImage().toPixelMap()
        var white = 0
        var edges = 0
        for (y in 0 until pixels.height) for (x in 2 until pixels.width - 2) {
            val ink = pixels[x, y]
            if (minOf(ink.red, ink.green, ink.blue) > .98f) {
                white++
                if (minOf(pixels[x - 2, y].luminance(), pixels[x + 2, y].luminance()) < .16f) edges++
            }
        }
        assertTrue("$label must have white glyphs", white > 0)
        if (edged) assertTrue("White strokes in $label must border a contrasting edge", edges > 0)
        else assertEquals("$label must have plain white strokes", 0, edges)
    }

    @Test fun materialEditorPreviewsCourseInkWithoutChangingTheTimetableAndCanCancelSaveOrReset() {
        // Solid mode isolates the ink change from GPU availability and the system palette.
        val saved = Appearance(theme = ThemeMode.LIGHT, glass = false)
        runBlocking { store.apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(saved))) }
        val revision = runBlocking { store.state().revision }
        fun appearance() = runBlocking { (store.api.observe(PlannerQuery.Settings).first() as PolicyView).appearance }
        val outline = hasText("课程文字描边") and hasClickAction()
        fun field(matcher: SemanticsMatcher): SemanticsNodeInteraction {
            ui.onNodeWithTag("appearance-scroll").performScrollToNode(matcher)
            return ui.onNode(matcher).performScrollTo()
        }
        fun material() = await(hasText("材质与动态") and hasClickAction()).performClick()
        ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        await(hasContentDescription("设置") and hasClickAction()).performClick()
        material()
        field(outline).assertIsOn()
        await(hasTestTag("course-timetable-preview")).assertIsDisplayed()
        ui.onNodeWithTag("grid-course:0").assertContentDescriptionContains("第 1–2 节", substring = true).assertHasNoClickAction()
        ui.onNodeWithTag("grid-course:1").assertContentDescriptionContains("第 2–3 节", substring = true).assertHasNoClickAction()
        assertCourseInk("大学物理", edged = true)
        ui.onNode(outline).performClick()
        assertCourseInk("大学物理", edged = false)
        assertEquals(revision, runBlocking { store.state().revision })
        ui.onNodeWithText("取消").performClick()
        material()
        field(outline).assertIsOn().performClick()
        ui.onNodeWithText("保存外观").performClick()
        material()
        field(outline).assertIsOff()
        assertEquals(saved.copy(courseTextOutline = false), appearance())
        field(hasText("恢复默认设置") and hasClickAction()).performClick()
        field(outline).assertIsOn()
        assertEquals(saved.copy(courseTextOutline = false), appearance())
        ui.onNodeWithText("保存外观").performClick()
        await(hasText("模型服务配置") and hasClickAction())
        assertEquals(Appearance(theme = ThemeMode.LIGHT), appearance())
    }

    @Test fun swipeSelectsOnlyAnAdjacentWeekAndTheCourseOpensItsActualOccurrence() {
        // Future term starts on page 1 regardless of the current week or device zone.
        val monday = LocalDate.now(campusZone).plusYears(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val term = TermSpec("测试学期", monday, 2, BellSchedule.WINTER)
        val meetings = listOf(term.meeting(1, 1, "实验", "A", 1, 2), term.meeting(2, 7, "实验", "B", 1, 2))
        runBlocking {
            val id = store.api.stageCourseImport(term, meetings)
            try { store.apply(PlannerCommand.CourseImport(id)) } finally { store.api.discardCourseImport(id) }
        }
        val revision = runBlocking { store.state().revision }
        ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        await(hasTestTag("nav:courses")).performClick()
        val week = hasTestTag("course-week")
        val grid = hasTestTag("week-grid")
        await(week and hasText("第 1 周")).assertIsDisplayed()
        ui.onNodeWithTag("course-prev").assertIsNotEnabled()
        await(grid).performTouchInput { swipeLeft() }
        await(week and hasText("第 2 周")).assertIsDisplayed()
        ui.onNodeWithTag("course-next").assertIsNotEnabled()
        await(grid).performTouchInput { swipeLeft() }
        ui.onNode(week).assertTextContains("第 2 周")
        await(hasTestTag("grid-course:0") and hasContentDescription("周日", substring = true)).performClick()
        await(hasTestTag("course-detail")).assertIsDisplayed()
        ui.onNodeWithText("教室：B").assertIsDisplayed()
        ui.onNodeWithText("关闭").performClick()
        await(hasTestTag("nav:today")).performClick()
        await(hasTestTag("nav:courses")).performClick()
        await(week and hasText("第 2 周")).assertIsDisplayed()
        await(grid).performTouchInput { swipeRight() }
        await(week and hasText("第 1 周")).assertIsDisplayed()
        ui.onNodeWithContentDescription("课表选项").performClick()
        ui.onNodeWithTag("course-view-toggle").performClick()
        await(hasTestTag("week-course:0")).performClick()
        ui.onNode(hasText("教室：A") and hasAnyAncestor(hasTestTag("course-detail"))).assertIsDisplayed()
        assertEquals(revision, runBlocking { store.state().revision })
    }
}
