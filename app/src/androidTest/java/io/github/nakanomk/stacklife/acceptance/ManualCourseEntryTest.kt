package io.github.nakanomk.stacklife.acceptance

import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Paint
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.LocalPlannerGlassAllowed
import io.github.nakanomk.stacklife.appearance.PlannerPopupHost
import io.github.nakanomk.stacklife.appearance.glassUnavailableReason
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.appearance.BackgroundStore
import io.github.nakanomk.stacklife.appearance.LocalBackgroundStore
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
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
    private suspend fun terms() = (store.api.observe(PlannerQuery.Terms).first() as Terms).terms
    private suspend fun week(n: Int) = store.api.observe(PlannerQuery.Week(terms().single().id, n)).first() as CourseWeek
    private fun next() { await("manual-next").performClick() }

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
        inst.uiAutomation.waitForIdle(200, 5_000)
        val shot = checkNotNull(inst.uiAutomation.takeScreenshot())
        val bitmap = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false).also { shot.recycle() } else shot
        try {
            val args = InstrumentationRegistry.getArguments()
            val label = args.getString("courseChromeCapture")
            val file = when {
                name == null -> null
                name.startsWith("u8-") -> name.takeIf { args.getString("captureU8") == "yes" }
                label in listOf("before", "after") -> "course-chrome-$label-$name"
                else -> null
            }
            if (file != null) {
                File(checkNotNull(inst.targetContext.getExternalFilesDir(null)), "$file.png").outputStream().use {
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
        val appearance = Appearance(theme = ThemeMode.LIGHT, glass = true, showCourseGrid = true, reduceTransparency = !live)
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

    @Test fun gridSwitchAddsBothRulesToPreviewAndRealWeekWithoutMovingTheCourse() {
        val term = TermSpec("秋季实验", LocalDate.of(2026, 9, 7), 1, BellSchedule.SUMMER)
        val optics = term.meeting(1, 1, "光学实验", "A101", 1, 3)
        runBlocking {
            val draft = store.api.stageCourseImport(term, listOf(optics,
                term.meeting(1, 4, "设计", "B202", 2, 3), term.meeting(1, 5, "研讨", "C301", 3, 4)))
            try { store.apply(PlannerCommand.CourseImport(draft), PlannerCommand.EditAppearance(
                AppearancePatch.Replace(Appearance(start = Destination.COURSES, theme = ThemeMode.LIGHT, palette = PaletteMode.SEED,
                    background = WallpaperSpec())))) }
            finally { store.api.discardCourseImport(draft) }
        }
        val applying = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val api = object : PlannerApi by store.api {
            override suspend fun preview(commands: List<PlannerCommand>): PreviewResult {
                if (commands.singleOrNull() is PlannerCommand.EditAppearance && applying.complete(Unit)) release.await()
                return store.api.preview(commands)
            }
        }
        ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(api) }) }
        val dp = ui.density.density
        fun delta(a: Int, b: Int) = maxOf(abs((a ushr 16 and 255) - (b ushr 16 and 255)),
            abs((a ushr 8 and 255) - (b ushr 8 and 255)), abs((a and 255) - (b and 255)))
        fun awaitPaintedWeek() {
            // The semantics tree can lay out the timetable while the display still shows
            // the startup spinner or the outgoing settings page. Synchronize on rail ink,
            // independent of the grid lines and the course pigment under test.
            val firstPeriod = ui.onNode(hasText("1") and hasAnyAncestor(hasTestTag("week-grid")), useUnmergedTree = true)
            val start = point(firstPeriod, 0f, 0f)
            val end = point(firstPeriod, 1f, 1f)
            val glyph = buildList {
                for (y in start.y until end.y step 2) for (x in start.x until end.x step 2) add(Point(x, y))
            }
            ui.waitUntil(7_000) { colorsAt(glyph).any { android.graphics.Color.red(it) < 100 } }
        }
        fun probe(preview: Boolean, lines: Boolean): Int {
            if (!preview) awaitPaintedWeek()
            val scope = hasTestTag(if (preview) "course-timetable-preview" else "week-grid")
            val tile = ui.onNode(hasTestTag("grid-course:0") and hasAnyAncestor(scope)).assertIsDisplayed()
            val origin = point(tile, 0f, 0f)
            val size = tile.fetchSemanticsNode().size
            val column = size.width + 2 * dp
            val period = (size.height + 2 * dp) / (if (preview) 2 else 3)
            val x = origin.x - dp
            val y = origin.y - dp
            // Cross Tue/Wed halfway through the first period and periods 1/2 in the free Wed column.
            val vertical = Point((x + 2 * column).roundToInt(), (y + .5f * period).roundToInt())
            val horizontal = Point((x + 2.5f * column).roundToInt(), (y + period).roundToInt())
            val span = (3 * dp).roundToInt().coerceAtLeast(3)
            val points = (-span..span).map { Point(vertical.x + it, vertical.y) } +
                (-span..span).map { Point(horizontal.x, horizontal.y + it) } +
                listOf(Point(vertical.x + (column / 3).roundToInt(), vertical.y),
                    Point(horizontal.x, horizontal.y + (period / 3).roundToInt()), point(tile, .65f, .83f))
            val surface = if (preview) "grid-preview" else "week-grid"
            val pixels = colorsAt(points, "u8-$surface-${if (lines) "on" else "off"}")
            val width = 2 * span + 1
            for (i in 0..1) {
                val strength = (0 until width).maxOf { delta(pixels[i * width + it], pixels[2 * width + i]) }
                assertTrue("$surface: ${if (i == 0) "vertical" else "horizontal"} rule ΔRGB=$strength, lines=$lines",
                    if (lines) strength > 12 else strength < 5)
            }
            return pixels.last()
        }
        val course = await("grid-course:0").assertTextContains(optics.title)
        fun box() = course.fetchSemanticsNode().boundsInRoot
        val original = box()
        val dates = await("week-dates").fetchSemanticsNode().boundsInRoot
        val hit = original.center
        fun openAtSamePosition() {
            ui.onRoot().performTouchInput { click(hit) }
            await("course-detail")
            ui.onNode(hasText(optics.title) and hasAnyAncestor(hasTestTag("course-detail"))).assertIsDisplayed()
            ui.onNodeWithText(ui.activity.getString(R.string.courses_close)).performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithTag("course-detail").fetchSemanticsNodes().isEmpty() }
        }
        val off = probe(preview = false, lines = false)
        openAtSamePosition()
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNode(hasText(ui.activity.getString(R.string.ui_material_motion)) and hasClickAction()).performClick()
        val list = await("appearance-scroll")
        val toggle = hasText(ui.activity.getString(R.string.appearance_grid_lines)) and hasClickAction()
        val preview = hasTestTag("course-timetable-preview")
        fun revealPreview() {
            list.performScrollToNode(preview); ui.onNode(preview).performScrollTo()
            // A bring-into-view can reach y=0 of the scrolling surface, behind the overlay.
            // Sample both real rules below the pinned controls, on the displayed window.
            val bottom = ui.onNodeWithTag("appearance-header").fetchSemanticsNode().boundsInRoot.bottom
            val y = ui.onNode(preview).fetchSemanticsNode().positionInRoot.y
            if (y < bottom) list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, y - bottom - 1f) }
            assertTrue(ui.onNode(preview).fetchSemanticsNode().positionInRoot.y >= bottom - 1f)
        }
        revealPreview()
        probe(preview = true, lines = false)
        try {
            list.performScrollToNode(toggle)
            ui.onNode(toggle).performScrollTo().assertIsOff().performTouchInput { click() }
            ui.onNode(toggle).assertIsOn()
            ui.waitUntil(10_000) { applying.isCompleted }
            revealPreview()
            probe(preview = true, lines = true)
        } finally { release.complete(Unit) }
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.ui_back)).performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("appearance-scroll").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithContentDescription("返回").performClick()
        await("week-grid")
        val on = probe(preview = false, lines = true)
        val rgb = listOf(off ushr 16 and 255, off ushr 8 and 255, off and 255)
        assertTrue("Course lost its pigment with grid disabled: #${off.toUInt().toString(16)}",
            rgb.max() - rgb.min() > 24 && delta(on, off) < 16)
        assertEquals(original, box()); assertEquals(dates, await("week-dates").fetchSemanticsNode().boundsInRoot)
        openAtSamePosition()
        assertEquals(optics, runBlocking { week(1).classes.first().meeting })
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun courseGlassSamplesThePageWallpaperEvenWhenGridRulesAreHidden() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "week-wallpaper-").toFile()
        val id = java.util.UUID.randomUUID().toString()
        val photos = BackgroundStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = dir }) { setOf(id) }
        val api = RoomPlannerApi(store.db, imageExists = photos::exists)
        val term = TermSpec("光学", LocalDate.of(2026, 9, 7), 2, BellSchedule.SUMMER)
        fun crop(x: Float) = runBlocking {
            val a = Appearance(theme = ThemeMode.LIGHT, start = Destination.COURSES, showCourseGrid = false,
                pageBackgrounds = mapOf(Destination.COURSES to WallpaperSpec(id, offsetX = x, tint = 0f)))
            val preview = api.preview(listOf(PlannerCommand.EditAppearance(AppearancePatch.Replace(a)))) as PreviewResult.Ready
            assertTrue(api.commit(preview.proposalId) is CommitResult.Applied)
        }
        try {
            runBlocking {
                photos.recover()
                val draft = store.api.stageCourseImport(term, listOf(term.meeting(1, 1, "光学实验", "", 1, 3)))
                try { store.apply(PlannerCommand.CourseImport(draft)) } finally { store.api.discardCourseImport(draft) }
            }
            val bitmap = Bitmap.createBitmap(2048, 320, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                val paint = Paint()
                for (y in 0 until bitmap.height step 2) {
                    val level = if (y % 4 == 0) 255 else 140
                    paint.color = android.graphics.Color.rgb(level, 0, 0)
                    canvas.drawRect(0f, y.toFloat(), 1024f, y + 2f, paint)
                    paint.color = android.graphics.Color.rgb(0, 0, level)
                    canvas.drawRect(1024f, y.toFloat(), 2048f, y + 2f, paint)
                }
                File(File(dir, "background-images"), id).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            crop(-1f)
            ui.setContent { CompositionLocalProvider(LocalBackgroundStore provides photos) {
                StackLifeApp(viewModel { WorkspaceViewModel(api) })
            } }
            await("course-week").performClick()
            await("course-week-choice:1").performClick()
            val tile = await("grid-course:0")
            assumeTrue("Requires the real optical renderer", ui.onAllNodes(hasStateDescription("折射胶囊")).fetchSemanticsNodes().isNotEmpty())
            val inside = point(tile, .55f, .8f)
            val empty = Point(inside.x + tile.fetchSemanticsNode().size.width, inside.y)
            val points = listOf(inside, empty)
            fun bias(c: Int) = android.graphics.Color.red(c) - android.graphics.Color.blue(c)
            fun sample(sign: Int, label: String): Int {
                ui.waitUntil(10_000) { sign * bias(colorsAt(points).last()) > 60 }
                return colorsAt(points, "u8-wallpaper-course-$label").first()
            }
            val red = sample(1, "red")
            crop(1f)
            val blue = sample(-1, "blue")
            assertTrue("An opaque tile would not transmit the wallpaper change: #${red.toUInt().toString(16)} → #${blue.toUInt().toString(16)}",
                bias(red) - bias(blue) > 32)
            // This dropdown is a separate window opened from inside a glass capsule.
            // The opaque Material fallback (or a toolbar-sized donor) cannot pass this.
            await("course-week").performClick()
            await("course-week-choice:1").assertIsSelected()
            val next = await("course-week-choice:2")
            val menuPoint = listOf(point(next, .88f, .5f))
            ui.waitUntil(10_000) { bias(colorsAt(menuPoint).single()) < -24 }
            val from = point(next, .88f, .17f); val to = point(next, .88f, .83f)
            val scan = (from.y..to.y).map { Point(from.x, it) }
            fun relativeTexture(pixels: List<Int>): Double {
                val channel = pixels.map { -bias(it) }
                return (channel.max() - channel.min()) / channel.average().coerceAtLeast(1.0)
            }
            val frosted = relativeTexture(colorsAt(scan))
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("course-week-choice:2").fetchSemanticsNodes().isEmpty() }
            val bare = relativeTexture(colorsAt(scan))
            assertTrue("No sharp wallpaper reference under the window: $bare", bare > .16)
            assertTrue("A tint attenuates signal and texture equally; expected actual blur: $frosted vs $bare", frosted < .65 * bare)
            await("course-week").performClick(); await("course-week-choice:2")
            crop(-1f)
            ui.waitUntil(10_000) { bias(colorsAt(menuPoint).single()) > 24 }
            colorsAt(menuPoint, "u8-week-menu-red")
            next.performClick()
            await("course-week").assertTextContains(ui.activity.getString(R.string.courses_week, 2))
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("course-week-choice:2").fetchSemanticsNodes().isEmpty() }
            await("course-week").performClick(); await("course-week-choice:2").assertIsSelected()
            await("course-week-choice:1").performClick()
            await("grid-course:0")
            tile.performClick(); await("course-detail")
            ui.onNode(hasText("光学实验") and hasAnyAncestor(hasTestTag("course-detail"))).assertIsDisplayed()
        } finally { dir.deleteRecursively() }
    }

    @Test fun editorWeeksAndReviewTransmitChangesFromTheActualWindow() = sheetMaterial(live = true)
    @Test fun highContrastEditorWeeksAndReviewStayOpaqueAndUsable() = sheetMaterial(live = false)

    private fun sheetMaterial(live: Boolean) {
        val host = mutableStateOf(Color.Red)
        val appearance = Appearance(theme = ThemeMode.LIGHT, glass = true, highContrast = !live)
        var available = false
        val editor = ManualEditorModel(SavedStateHandle(), ui.activity).apply {
            open(null, 1)
            checkNotNull(form).update { it.copy(newTerm = it.newTerm.copy(monday = LocalDate.of(2026, 9, 7), weeks = 4),
                pattern = it.pattern.copy(title = "测试课")) }
        }
        ui.setContent { StackLifeTheme(appearance) {
            available = glassUnavailableReason(appearance) == null
            CompositionLocalProvider(LocalPlannerGlassAllowed provides true) {
                PlannerPopupHost {
                    Spacer(Modifier.fillMaxSize().background(host.value).testTag("chrome-source"))
                    editor.form?.let { ManualCourseSheet(viewModel { WorkspaceViewModel(store.api) }, it, emptyList(), editor::close, { _, _ -> editor.close() }) }
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
        checkPlane("editor", point(await("termMonday"), .84f, .20f))
        next(); await("manual-title").assertTextContains("测试课"); next()
        val first = await("manual-week:1")
        checkPlane("weeks", point(first, .74f, .53f))
        first.assertIsSelected().performClick()
        ui.onNodeWithTag("manual-preview").assertIsNotEnabled()
        await("manual-week:2").assertIsNotSelected().performClick()
        await("manual-preview").performClick()
        await("manual-review").assertIsDisplayed()
        val change = await("manual-change")
        checkPlane("review", point(change, .97f, .10f))
        assertTrue(runBlocking { terms().isEmpty() })
        await("manual-back").performClick()
        await("manual-week:2").assertIsSelected()
        await("manual-cancel").performScrollTo().performClick()
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
        assertTrue((store.api.observe(day).first() as DayPlan).occurrences.mapNotNull { it.course }.isEmpty())
        assertTrue(store.api.commit(ready.proposalId) is CommitResult.Applied)
        store.reopen()
        val privateRead = PlannerTools(store.api, AssistantPage.Day(today, zone), Disclosure())
            .query(Json.parseToJsonElement("""{"kind":"current"}""").jsonObject).toString()
        assertTrue("busy" in privateRead); assertFalse("现场课程" in privateRead || "独立地点" in privateRead)
        ui.activityRule.scenario.onActivity { activity -> activity.setContent {
            StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
        } }
        val course = await("day-course").performScrollTo()
        val toggle = await("day-tray-toggle")
        if (toggle.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == ui.activity.getString(io.github.nakanomk.stacklife.R.string.today_expanded)) toggle.performClick()
        val activeCard = course.fetchSemanticsNode().id
        val label = ui.onNode(hasText("现场课程") and hasAnyAncestor(SemanticsMatcher("visible course card") { it.id == activeCard }), useUnmergedTree = true).performScrollTo()
        val bounds = label.fetchSemanticsNode().boundsInRoot
        val viewport = await("day-drop-area").fetchSemanticsNode().boundsInRoot
        val hit = bounds.intersect(viewport).center
        assertTrue("Visible course title must receive a real touch", bounds.contains(hit) && viewport.contains(hit))
        label.performTouchInput { click(hit - bounds.topLeft) }
        await("course-detail").assertIsDisplayed()
        ui.onNode(hasText("现场课程") and hasAnyAncestor(hasTestTag("course-detail"))).assertIsDisplayed()
    }

    @Test fun restoredPrivateDraftKeepsScrollAndNeverReplaysAConfirmedWrite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ui.activityRule.scenario
        var handle = SavedStateHandle()
        val editor = mutableStateOf(ManualEditorModel(handle, context).apply { open(null, 1) })
        val committed = java.util.concurrent.atomic.AtomicInteger()
        val api = object : PlannerApi by store.api {
            override suspend fun commit(proposalId: String): CommitResult {
                committed.incrementAndGet()
                return store.api.commit(proposalId)
            }
        }
        val receipts = java.util.concurrent.atomic.AtomicInteger()
        ui.setContent { StackLifeTheme {
            val current = editor.value
            current.form?.let { form -> key(form) {
                ManualCourseSheet(viewModel { WorkspaceViewModel(api) }, form, emptyList(), current::close,
                    // Leave the file at the exact boundary after Room applied the command but before close/delete.
                    { _, _ -> receipts.incrementAndGet(); Unit })
            } }
        } }
        fun restoreFromIdentity() {
            scenario.moveToState(Lifecycle.State.CREATED)
            val keys = handle.keys()
            assertEquals(1, keys.size)
            val id = checkNotNull(handle.get<String>(keys.single()))
            assertEquals(id, java.util.UUID.fromString(id).toString())
            handle = SavedStateHandle(mapOf(keys.single() to id))
            scenario.onActivity { editor.value = ManualEditorModel(handle, context) }
            scenario.moveToState(Lifecycle.State.RESUMED)
        }
        try {
            await("termWeeks").assertTextContains("20").performScrollTo().performClick()
            ui.onNodeWithTag("wheel-number").assert(hasStateDescription("20"))
            ui.onNodeWithTag("wheel-cancel").performClick()
            ui.chooseDate("termMonday", LocalDate.of(2026, 9, 7))
            ui.chooseNumber("termWeeks", 53); next()
            field("manual-title", "实际草稿"); field("manual-room", "B")
            next()
            ui.onNodeWithText(ui.activity.getString(io.github.nakanomk.stacklife.R.string.courses_select_all)).performScrollTo().performClick()
            await("manual-week:52").performScrollTo().performClick().assertIsNotSelected()
            await("manual-back").performClick()
            await("manual-title").assertTextContains("实际草稿")
            await("manual-room").assertTextContains("B")
            next(); await("manual-week:53").assertIsSelected()
            await("manual-preview").performClick(); await("manual-change")
            val proposal = ui.runOnIdle { checkNotNull(editor.value.form?.ready).proposalId }
            fun offset() = await("manual-review").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            await("manual-review").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 550f) }
            val scrolled = offset()
            assertTrue("Review of 52 real meetings must scroll", scrolled > 0f)
            restoreFromIdentity()
            await("manual-change"); assertEquals(scrolled, offset(), 1f)
            await("manual-back").performClick(); await("manual-week:52").assertIsNotSelected()
            await("manual-preview").performClick(); await("manual-change")
            assertEquals(scrolled, offset(), 1f)
            restoreFromIdentity()
            await("manual-change")
            assertNotEquals(proposal, ui.runOnIdle { checkNotNull(editor.value.form?.ready).proposalId })
            assertEquals(0, committed.get())
            val top = await("manual-save").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            val bottom = await("manual-save-bottom").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            // Both real click handlers run in the same frame, before Compose can disable either button.
            ui.onNodeWithTag("manual-save").assertIsDisplayed()
            ui.runOnIdle { top(); bottom() }
            ui.waitUntil(10_000) { receipts.get() == 1 }
            assertEquals(1, committed.get())
            restoreFromIdentity()
            await("manual-error").assertTextContains(ui.activity.getString(io.github.nakanomk.stacklife.R.string.manual_interrupted))
            ui.onNodeWithTag("manual-save").assertIsNotEnabled()
            ui.onNodeWithTag("manual-save-bottom").assertDoesNotExist()
            runBlocking {
                assertEquals("实际草稿", week(1).classes.single().meeting.title)
                assertEquals("B", week(53).classes.single().meeting.room)
                assertTrue(week(52).classes.isEmpty())
            }
            assertEquals(1, committed.get())
            val file = File(context.noBackupFilesDir, "manual-course-drafts/${handle.get<String>(handle.keys().single())}")
            await("manual-cancel").performClick(); awaitClosed()
            assertTrue(handle.keys().isEmpty())
            assertFalse(file.exists())
        } finally { ui.runOnIdle { editor.value.close() } }
    }

    @Test fun emptyTimetableToRealSeriesThenMenuCancelEditAndRemove() = runBlocking {
        val scenario = ui.activityRule.scenario
        fun attach() = scenario.onActivity { activity -> activity.setContent {
            StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
        } }
        attach()
        await("nav:courses").performClick()
        await("course-add").assertIsDisplayed().performClick()
        await("manual-course-sheet").assertIsDisplayed()
        ui.exerciseSheetAnchors("manual")
        ui.onNodeWithTag("termId").assertDoesNotExist()
        ui.onNodeWithTag("manual-title").assertDoesNotExist()
        ui.onNodeWithTag("manual-week:1").assertDoesNotExist()
        ui.onNodeWithTag("manual-next").assertIsNotEnabled()
        ui.chooseDate("termMonday", LocalDate.of(2026, 9, 7)); ui.chooseNumber("termWeeks", 3)
        await("termAdvanced").performScrollTo().performClick()
        ui.onNodeWithText("冬令").performScrollTo().performClick().assertIsSelected()
        next()
        field("manual-title", "线性代数"); field("manual-room", "东101")
        await("manual-day").performScrollTo().performClick()
        await("manual-day:1").assertIsSelected()
        await("manual-day:2").assertIsNotSelected().performClick()
        await("manual-periods").performScrollTo().performClick()
        ui.adjustWheel("wheel-first", 5); ui.adjustWheel("wheel-last", 6)
        ui.onNodeWithTag("wheel-confirm").performClick()
        next()
        await("manual-week:1").assertIsSelected()
        await("manual-week:3").performScrollTo().performClick()
        await("manual-back").performClick()
        await("manual-title").assertTextContains("线性代数")
        await("manual-periods").assertTextContains("5–6")
        next()
        scenario.recreate(); attach()
        await("manual-week:3").assertIsSelected()
        await("manual-preview").performClick()
        await("manual-change").assertExists()
        assertTrue(terms().isEmpty())
        await("manual-save").performClick(); awaitClosed(); await("grid-course:0").performClick()
        await("course-detail").assertExists()
        ui.exerciseSheetAnchors("course-detail")
        ui.onNode(hasText("线性代数") and hasAnyAncestor(hasTestTag("course-detail"))).assertExists()
        val firstWeek = week(1)
        val generated = terms().single()
        assertEquals(LocalDate.of(2026, 9, 7), generated.firstMonday)
        assertEquals(BellSchedule.WINTER, generated.initial)
        assertEquals(3, generated.weeks)
        assertEquals(generated.id, java.util.UUID.fromString(generated.id).toString())
        assertNotNull("Saved term missing from the week query", firstWeek.term)
        assertEquals("Wrong occurrences after confirming weeks 1 and 3: ${firstWeek.classes}; week 3: ${week(3).classes}",
            1, firstWeek.classes.size)
        val class1 = firstWeek.classes.single()
        assertEquals(MANUAL_SOURCE, class1.source); assertEquals(2, class1.meeting.day)
        assertEquals(5, class1.meeting.firstPeriod); assertEquals(6, class1.meeting.lastPeriod)
        assertEquals("东101", class1.meeting.room); assertTrue(week(2).classes.isEmpty()); assertEquals("线性代数", week(3).classes.single().meeting.title)
        val day = store.api.observe(PlannerQuery.Day(LocalDate.parse("2026-09-08"), campusZone)).first() as DayPlan
        assertEquals(class1, day.occurrences.mapNotNull { it.course }.single())
        // A school occurrence with the exact same pattern in week 3 must survive the manual edit and removal.
        val schoolMeeting = week(3).classes.single().meeting
        val importId = store.api.stageCourseImport(generated, listOf(schoolMeeting), HUST_SOURCE)
        try { store.apply(PlannerCommand.CourseImport(importId)) } finally { store.api.discardCourseImport(importId) }
        ui.onNodeWithText("关闭").performClick(); await("course-options").performClick(); await("course-add").performClick()
        await("manual-term:${generated.id}").assertIsSelected(); next()
        field("manual-title", "不保存"); await("manual-cancel").performScrollTo().performClick(); awaitClosed()
        await("course-options").performClick(); await("course-add").performClick(); next(); field("manual-title", "本周新课")
        next(); await("manual-preview").performClick(); await("manual-save-bottom").performScrollTo().performClick(); awaitClosed()
        assertEquals(listOf("本周新课", "线性代数"), week(1).classes.map { it.meeting.title })
        await("grid-course:1").performClick(); await("manual-edit").performClick(); next(); field("manual-title", "数值分析")
        next(); await("manual-preview").performClick(); await("manual-save").performClick()
        awaitClosed()
        val updated = week(3).classes.associateBy { it.source }
        assertEquals("数值分析", updated.getValue(MANUAL_SOURCE).meeting.title)
        assertEquals(schoolMeeting, updated.getValue(HUST_SOURCE).meeting)
        await("grid-course:1").performClick(); await("manual-edit").performClick(); next()
        await("manual-remove").performScrollTo().assertIsDisplayed().performClick()
        await("manual-save").performClick(); awaitClosed()
        assertEquals("本周新课", week(1).classes.single().meeting.title)
        val kept = week(3).classes.single()
        assertEquals(HUST_SOURCE, kept.source); assertEquals(schoolMeeting, kept.meeting)
    }
}
