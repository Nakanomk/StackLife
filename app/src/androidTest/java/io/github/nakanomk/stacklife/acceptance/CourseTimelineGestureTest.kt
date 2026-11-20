package io.github.nakanomk.stacklife.acceptance

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
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
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
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
            val topLeft = ui.runOnIdle { node.layoutInfo.coordinates.localToScreen(Offset.Zero) }
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
