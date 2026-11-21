package io.github.nakanomk.stacklife.acceptance

import android.graphics.Bitmap
import android.graphics.Point
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
import io.github.nakanomk.stacklife.appearance.LocalPlannerGlassAllowed
import io.github.nakanomk.stacklife.appearance.PlannerPopupHost
import io.github.nakanomk.stacklife.appearance.glassUnavailableReason
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.assistant.Disclosure
import io.github.nakanomk.stacklife.assistant.PlannerTools
import io.github.nakanomk.stacklife.assistant.ToolPlan
import java.io.File
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualCourseEntryTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    private fun await(tag: String): SemanticsNodeInteraction {
        val matcher = hasTestTag(tag) and isEnabled()
        try {
            ui.waitUntil(timeoutMillis = 10_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: ComposeTimeoutException) {
            val roots = ui.onAllNodes(isRoot(), useUnmergedTree = true)
            val tree = roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() }
            throw AssertionError("未找到可用的 $tag：\n$tree", failure)
        }
        return ui.onNode(matcher)
    }
    private fun awaitClosed() = ui.waitUntil(timeoutMillis = 10_000) {
        ui.onAllNodesWithTag("manual-course-sheet").fetchSemanticsNodes().isEmpty()
    }
    private fun field(tag: String, value: String) {
        await(tag).performScrollTo().assertIsDisplayed().performTextReplacement(value)
        await(tag).assertTextContains(value)
    }
    private fun period(tag: String, n: Int) {
        await(tag).performScrollTo().performClick()
        await("$tag:$n").performScrollTo().performClick()
    }
    private suspend fun week(n: Int) = store.api.observe(PlannerQuery.Week("手动学期", n)).first() as CourseWeek

    private fun point(node: SemanticsNodeInteraction, x: Float, y: Float): Point {
        val target = node.assertIsDisplayed().fetchSemanticsNode()
        return ui.runOnIdle {
            val origin = target.positionOnScreen
            Point((origin.x + target.size.width * x).roundToInt(), (origin.y + target.size.height * y).roundToInt())
        }
    }

    // Sample the composed display, including separate popup windows, rather than the sampled layer itself.
    private fun colorsAt(points: List<Point>, name: String? = null): List<Int> {
        val inst = InstrumentationRegistry.getInstrumentation()
        val shot = checkNotNull(inst.uiAutomation.takeScreenshot())
        val bitmap = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false).also { shot.recycle() } else shot
        try {
            val label = InstrumentationRegistry.getArguments().getString("courseChromeCapture")
            if (name != null && label in listOf("before", "after")) {
                File(inst.targetContext.getExternalFilesDir(null), "course-chrome-$label-$name.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            return points.map { bitmap.getPixel(it.x, it.y) }
        } finally { bitmap.recycle() }
    }

    @Test fun movingCourseReachesPinnedFrostedWeekday() = pinnedWeekday(live = true)
    @Test fun reducedTransparencyKeepsDateSolidAndTappableWhileTheCourseScrolls() = pinnedWeekday(live = false)

    private fun pinnedWeekday(live: Boolean) {
        val term = TermSpec("Studio-7", LocalDate.of(2026, 9, 7), 1, BellSchedule.SUMMER)
        val m = term.meeting(1, 1, "测试课", "", 1, 12)
        val week = CourseWeek(term, 1, listOf(ScheduledCourse(term.id, MANUAL_SOURCE, m)), mapOf(m.title to 0))
        val appearance = Appearance(theme = ThemeMode.LIGHT, glass = true, reduceTransparency = !live)
        var available = false
        ui.setContent { StackLifeTheme(appearance) {
            available = glassUnavailableReason(appearance) == null
            CompositionLocalProvider(LocalPlannerGlassAllowed provides true) {
                Box(Modifier.wrapContentHeight(Alignment.Top).height(360.dp)) { WeekGrid(term, week, 60.dp, 0.dp) {} }
            }
        } }
        if (live) ui.runOnIdle { assumeTrue("Requires accelerated backdrop rendering", available) }
        val date = ui.onNode(hasText("9/7") and hasClickAction())
        val adjacentDate = ui.onNode(hasText("9/8") and hasClickAction())
        val spokenDate = date.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()
        // Empty Tuesday: probe just outside Monday’s tile and well inside the unoccupied column.
        // A transparent scrim can transmit the course, but cannot spread its pigment into Tuesday.
        val dateRow = listOf(point(date, .48f, .10f), point(adjacentDate, .07f, .10f), point(adjacentDate, .58f, .10f))
        val header = ui.onNodeWithTag("week-dates").fetchSemanticsNode()
        val (top, bottom) = ui.runOnIdle { header.positionOnScreen.y to header.positionOnScreen.y + header.size.height }
        val dp = ui.density.density
        val ruleX = point(adjacentDate, 1f, .5f).x
        val edge = bottom.roundToInt()
        val ruleRows = listOf(edge - (12 * dp).roundToInt(), edge, edge + 1, edge + (8 * dp).roundToInt())
            .flatMap { y -> (-3..3).map { dx -> Point(ruleX + dx, y) } }
        val points = dateRow + dateRow.map { Point(it.x, (top - 8 * dp).roundToInt()) }
        val initial = colorsAt(points, "dates-$live-rest")
        val grid = ui.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("week-grid")))
        val tile = ui.onNodeWithTag("grid-course:0")
        fun tileTop(): Float = tile.fetchSemanticsNode().let { ui.runOnIdle { it.positionOnScreen.y } }
        val before = tileTop()
        grid.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 130f * dp) }
        ui.waitForIdle()
        assertTrue("Course must actually travel behind the date", before - tileTop() > 80f * dp)
        assertEquals(points.first(), point(date, .48f, .10f))
        if (live) ui.waitUntil(10_000) {
            val color = Color(colorsAt(points).first())
            val old = Color(initial.first())
            maxOf(abs(color.red - old.red), abs(color.green - old.green), abs(color.blue - old.blue)) > .025f
        }
        val after = colorsAt(points + ruleRows, "dates-$live-scrolled")
        if (live) {
            fun edgeSpread(rgb: List<Int>): Float {
                val (course, near, empty) = rgb.map { Color(it) }
                val r = course.red - empty.red; val g = course.green - empty.green; val b = course.blue - empty.blue
                return (r * (near.red - empty.red) + g * (near.green - empty.green) + b * (near.blue - empty.blue)) /
                    (r * r + g * g + b * b).coerceAtLeast(.0001f)
            }
            val (dateSpread, toolbarSpread) = after.take(points.size).chunked(3).map(::edgeSpread)
            assertTrue("No sharp hole between toolbar and dates: $toolbarSpread, $dateSpread",
                dateSpread > .06f && toolbarSpread > .06f)
            val (inside, firstGridPixel, nextGridPixel, sharp) = after.drop(points.size).chunked(7)
            assertTrue("Reference must cross a real timetable divider", sharp.distinct().size > 1)
            assertNotEquals("Divider must still be softened in the fade", sharp, inside)
            assertEquals("The grid must be sharp at the date bar's lower edge", sharp, firstGridPixel)
            assertEquals("No blur may extend below the date bar", sharp, nextGridPixel)
        } else assertEquals("Solid toolbar and weekday leaked a moving course", initial, after.take(points.size))
        date.performTouchInput { click() }
        ui.onNode(hasText(spokenDate) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onNode(hasText("测试课") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onNodeWithText("关闭").performClick()
    }

    @Test fun editorWeeksAndReviewTransmitChangesFromTheActualWindow() = sheetMaterial(live = true)
    @Test fun highContrastEditorWeeksAndReviewStayOpaqueAndUsable() = sheetMaterial(live = false)

    private fun sheetMaterial(live: Boolean) {
        val host = mutableStateOf(Color.Red)
        val appearance = Appearance(theme = ThemeMode.LIGHT, glass = true, highContrast = !live)
        var available = false
        ui.setContent { StackLifeTheme(appearance) {
            available = glassUnavailableReason(appearance) == null
            CompositionLocalProvider(LocalPlannerGlassAllowed provides true) {
                PlannerPopupHost {
                    Spacer(Modifier.fillMaxSize().background(host.value).testTag("chrome-source"))
                    val form = remember { ManualCourseForm(null, 1).apply {
                        newTerm = TermForm(id = "手动学期", monday = LocalDate.of(2026, 9, 7), weeks = 4)
                        pattern = pattern.copy(title = "测试课")
                    } }
                    ManualCourseSheet(viewModel { WorkspaceViewModel(store.api) }, form, emptyList(), {}, { _, _ -> })
                }
            }
        } }
        if (live) ui.runOnIdle { assumeTrue("Requires accelerated backdrop rendering", available) }
        val toolbar = await("manual-toolbar")
        val toolbarTop = point(toolbar, .25f, 0f)
        val hostTop = point(ui.onNodeWithTag("chrome-source"), .25f, 0f)
        val handleHeight = with(ui.density) { 48.dp.roundToPx() }
        val panel = Point(toolbarTop.x, toolbarTop.y - handleHeight / 2) // Bare shell beside the centered drag handle.
        val exposedHost = Point(hostTop.x, (hostTop.y + toolbarTop.y - handleHeight) / 2)
        fun checkPlane(stage: String, field: Point) {
            val points = listOf(exposedHost, panel, point(toolbar, .26f, .25f), field)
            val names = listOf("exposed-host", "sheet-shell", "toolbar", "content")
            fun describe(values: List<Int>) = points.indices.joinToString { i ->
                "${names[i]}(${points[i].x},${points[i].y})=#${Integer.toHexString(values[i])}"
            }
            fun blueMinusRed(pixel: Int) = ((pixel and 255) - ((pixel ushr 16) and 255))
            fun sample(phase: String, before: List<Int>?, ready: (List<Int>) -> Boolean): List<Int> {
                if (live) try {
                    ui.waitUntil(10_000) { ready(colorsAt(points)) }
                } catch (e: ComposeTimeoutException) {
                    val actual = colorsAt(points, "$stage-$live-$phase-timeout")
                    throw AssertionError("No live transmission at $stage/$phase. Before: ${before?.let(::describe)}; actual: ${describe(actual)}", e)
                }
                return colorsAt(points, "$stage-$live-$phase")
            }
            ui.runOnIdle { host.value = Color.Red }; ui.waitForIdle()
            val red = sample("red", null) { values -> (2..3).all { blueMinusRed(values[it]) < -5 } }
            ui.runOnIdle { host.value = Color.Blue }; ui.waitForIdle()
            val blue = sample("blue", red) { values -> (2..3).all { blueMinusRed(values[it]) - blueMinusRed(red[it]) > 10 } }
            if (!live) assertEquals("Opaque controls changed at $stage: before ${describe(red)}, after ${describe(blue)}",
                red.drop(1), blue.drop(1))
        }
        checkPlane("editor", point(await("termId"), .85f, .50f))
        await("manual-weeks").performScrollTo().performClick()
        val first = await("manual-week:1")
        checkPlane("weeks", point(first, .74f, .53f))
        first.assertIsSelected().performClick()
        ui.onNodeWithTag("manual-weeks-done").assertIsNotEnabled()
        await("manual-week:2").assertIsNotSelected().performClick()
        await("manual-weeks-done").performClick()
        await("manual-preview").performClick()
        await("manual-review").assertIsDisplayed()
        val change = ui.onAllNodesWithTag("manual-change")[0]
        checkPlane("review", point(change, .97f, .5f))
        assertNull(runBlocking { week(1).term })
        await("manual-cancel").performClick()
        await("manual-weeks").assertTextContains("第 2 周")
    }

    @Test fun authorizedAssistantSeriesAppearsOnTodaysAxisAfterLocalConfirmationAndReopen(): Unit = runBlocking {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // Campus period five, on whichever Shanghai date maps to this device's current civil day.
        val campusDate = (-1L..1L).map(today::plusDays).first {
            it.atTime(14, 30).atZone(campusZone).withZoneSameInstant(zone).toLocalDate() == today
        }
        val monday = campusDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val args = Json.parseToJsonElement("""{"commands":[{"kind":"manual_course_add","termId":"测试学期","title":"现场课程","room":"独立地点","day":${campusDate.dayOfWeek.value},"weeks":[1],"firstPeriod":5,"lastPeriod":6,"newTerm":{"firstMonday":"$monday","totalWeeks":2,"initial":"SUMMER"}}]}""").jsonObject
        val plan = PlannerTools(store.api, AssistantPage.Terms, Disclosure(courseDetails = true)).proposal(args) as ToolPlan.Commands
        val ready = store.api.preview(plan.commands) as PreviewResult.Ready
        val day = PlannerQuery.Day(today, zone)
        assertTrue((store.api.observe(day).first() as DayPlan).courses.isEmpty())
        assertTrue(store.api.commit(ready.proposalId) is CommitResult.Applied)
        store.reopen()
        val privateRead = PlannerTools(store.api, AssistantPage.Day(today, zone), Disclosure())
            .query(Json.parseToJsonElement("""{"kind":"current"}""").jsonObject).toString()
        assertTrue("busy" in privateRead); assertFalse("现场课程" in privateRead || "独立地点" in privateRead)
        ui.activityRule.scenario.onActivity { activity -> activity.setContent {
            StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
        } }
        val course = await("day-course").performScrollTo()
        // Center the actual touch above the floating queue, not just inside the scroller.
        val viewport = await("day-drop-area").fetchSemanticsNode().boundsInRoot
        val tray = await("day-tray-container").fetchSemanticsNode().boundsInRoot
        val center = course.fetchSemanticsNode().boundsInRoot.center
        val floor = if (center.x in tray.left..tray.right) minOf(tray.top, viewport.bottom) else viewport.bottom
        val shift = center.y - (viewport.top + floor) / 2
        await("day-axis").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, shift) }
        ui.waitForIdle()
        assertTrue("Course tap must land in the uncovered axis", viewport.contains(course.fetchSemanticsNode().boundsInRoot.center))
        course.performTouchInput { click() }
        await("course-detail").assertIsDisplayed()
        ui.onNode(hasText("现场课程") and hasAnyAncestor(hasTestTag("course-detail"))).assertIsDisplayed()
    }

    @Test fun emptyTimetableToRealSeriesThenMenuCancelEditAndRemove() = runBlocking {
        ui.activityRule.scenario.onActivity { activity -> activity.setContent {
            StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
        } }
        await("nav:courses").performClick()
        await("course-add").assertIsDisplayed().performClick()
        field("termId", "手动学期"); ui.chooseDate("termMonday", LocalDate.of(2026, 9, 7)); ui.chooseNumber("termWeeks", 3)
        ui.onNodeWithText("冬令").performScrollTo().performClick().assertIsSelected()
        field("manual-title", "线性代数"); field("manual-room", "东101")
        await("manual-weeks").performScrollTo().assertIsDisplayed().performClick()
        await("manual-week:3").performScrollTo().assertIsDisplayed().performClick()
        await("manual-weeks-done").assertIsDisplayed().performClick()
        await("manual-weeks").assertTextContains("第 1、3 周")
        await("manual-day").performScrollTo().performClick()
        await("manual-day:1").assertIsSelected()
        await("manual-day:2").assertIsNotSelected().performClick()
        period("manual-first", 5); period("manual-last", 6)
        await("manual-preview").performClick(); await("manual-review").assertExists()
        assertNull(week(1).term)
        await("manual-save").performClick(); awaitClosed(); await("grid-course:0").performClick()
        await("course-detail").assertExists()
        ui.onNode(hasText("线性代数") and hasAnyAncestor(hasTestTag("course-detail"))).assertExists()
        val firstWeek = week(1)
        val terms = store.api.observe(PlannerQuery.Terms).first() as Terms
        assertEquals("Saved under a different term: ${terms.terms}", listOf("手动学期"), terms.terms.map { it.id })
        assertNotNull("Saved term missing from the week query", firstWeek.term)
        assertEquals("Wrong occurrences after confirming weeks 1 and 3: ${firstWeek.classes}; week 3: ${week(3).classes}",
            1, firstWeek.classes.size)
        val class1 = firstWeek.classes.single()
        assertEquals(MANUAL_SOURCE, class1.source); assertEquals(2, class1.meeting.day)
        assertEquals(5, class1.meeting.firstPeriod); assertEquals(6, class1.meeting.lastPeriod)
        assertEquals("东101", class1.meeting.room); assertTrue(week(2).classes.isEmpty()); assertEquals("线性代数", week(3).classes.single().meeting.title)
        val day = store.api.observe(PlannerQuery.Day(LocalDate.parse("2026-09-08"), campusZone)).first() as DayPlan
        assertEquals(class1, day.courses.single())
        ui.onNodeWithText("关闭").performClick(); await("course-options").performClick(); await("course-add").performClick()
        field("manual-title", "不保存"); await("manual-cancel").performClick(); awaitClosed()
        await("course-options").performClick(); await("course-add").performClick(); field("manual-title", "本周新课")
        await("manual-preview").performClick(); await("manual-save").performClick(); awaitClosed()
        assertEquals(listOf("本周新课", "线性代数"), week(1).classes.map { it.meeting.title })
        await("grid-course:1").performClick(); await("manual-edit").performClick(); field("manual-title", "数值分析")
        await("manual-preview").performClick(); await("manual-save").performClick()
        awaitClosed()
        assertEquals("数值分析", week(3).classes.single().meeting.title)
        await("grid-course:1").performClick(); await("manual-edit").performClick()
        await("manual-remove").performScrollTo().assertIsDisplayed().performClick()
        await("manual-save").performClick(); awaitClosed()
        assertEquals("本周新课", week(1).classes.single().meeting.title)
        assertTrue(week(3).classes.isEmpty())
    }
}
