package io.github.nakanomk.stacklife.acceptance

import io.github.nakanomk.stacklife.R
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.accessibilityservice.AccessibilityServiceInfo
import android.provider.Settings
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityManager
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.platform.ViewRootForTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.courses.*
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import io.github.nakanomk.stacklife.today.TodayRoute
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

// An adjacent civil day is precomposed for paging. The merged accessibility tree exposes
// only the selected axis; scope its unmerged scale marks to that same live node.
internal fun SemanticsNodeInteractionsProvider.dayTick(label: String): SemanticsNodeInteraction = dayMark("hour:$label")

private fun SemanticsNodeInteractionsProvider.dayMark(tag: String): SemanticsNodeInteraction {
    val axis = onNodeWithTag("day-axis").fetchSemanticsNode().id
    return onNode(hasTestTag(tag) and hasAnyAncestor(SemanticsMatcher("selected day axis") { it.id == axis }),
        useUnmergedTree = true)
}

internal fun SemanticsNodeInteractionsProvider.assertFloatingAxisMeetsDock() {
    fun box(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    val top = box("assistant-composer").top
    val rail = box("day-rail")
    val midnight = dayMark("time-day-end").fetchSemanticsNode()
    assertEquals("The rail reaches the assistant unless midnight is already visible",
        minOf(top, midnight.positionInRoot.y + midnight.size.height), rail.bottom, 1f)
    assertEquals(top, box("day-axis").bottom, 1f)
    assertEquals(top, box("day-drop-area").bottom, 1f)
    onAllNodesWithTag("day-tray-container").fetchSemanticsNodes().forEach {
        assertFalse("Opaque queue must not obscure the rail", rail.overlaps(it.boundsInRoot))
    }
}

internal fun once(id: String, start: OffsetDateTime, minutes: Int): PlannerCommand.SetSchedule = PlannerCommand.SetSchedule(id,
    SchedulePattern.Once(ExactInterval(start.toInstant().toEpochMilli(), start.plusMinutes(minutes.toLong()).toInstant().toEpochMilli())))

@RunWith(AndroidJUnit4::class)
class DayTimelineTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    private val query = PlannerQuery.Day(LocalDate.now(), ZoneId.systemDefault())
    private fun at(h: Int, m: Int) = query.date.atTime(h, m).atZone(query.zone).toOffsetDateTime()
    private fun readDay(match: (DayPlan) -> Boolean = { true }): DayPlan = runBlocking {
        withTimeout(10_000) { store.api.observe(query).first { match(it as DayPlan) } as DayPlan }
    }
    private fun show() {
        ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        await(hasTestTag("day-axis"))
    }
    // Navigation3 also owns Android SavedStateRegistry state. Recreate that owner, not
    // only a subtree's Compose saveable registry, then attach the app to the new Activity.
    private fun attachRestorableWindow() = ui.activityRule.scenario.onActivity { window ->
        window.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
    }
    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(10_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return ui.onNode(matcher)
    }
    private fun centerTick(label: String): Offset {
        val tick = ui.dayTick(label)
        tick.performScrollTo()
        val axis = ui.onNodeWithTag("day-axis")
        val area = ui.onNodeWithTag("day-drop-area").fetchSemanticsNode().boundsInRoot
        val tray = ui.onNodeWithTag("day-tray-container").fetchSemanticsNode().boundsInRoot
        val floor = if (area.center.x >= tray.left && area.center.x < tray.right) minOf(area.bottom, tray.top) else area.bottom
        val center = Offset(area.center.x, (area.top + floor) / 2)
        val shift = tick.fetchSemanticsNode().boundsInRoot.top - center.y
        axis.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, shift) }
        ui.waitForIdle()
        return center.copy(y = tick.fetchSemanticsNode().boundsInRoot.top)
    }
    private fun pickup(start: Offset, end: Offset) {
        val root = ui.onRoot()
        root.performTouchInput { down(start); advanceEventTime(700); moveTo(start + Offset(0f, 1f)) }
        root.performTouchInput { for (i in 1..8) moveTo(start + (end - start) * (i / 8f), delayMillis = 16) }
    }

    private fun armEnd(id: String) {
        val bar = await(hasTestTag("resize-bar:$id"))
        assertEquals("显示结束手柄", bar.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        bar.assertWidthIsAtLeast(48.dp).performTouchInput { longClick() }
        await(hasTestTag("resize-end:$id")).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    private fun dragEndTo(id: String, time: String, slightlyToward: String? = null) {
        val handle = await(hasTestTag("resize-end:$id")).fetchSemanticsNode().boundsInRoot
        val y = ui.dayTick(time).fetchSemanticsNode().boundsInRoot.top
        val start = handle.center
        val extra = slightlyToward?.let {
            (ui.dayTick(it).fetchSemanticsNode().boundsInRoot.top - y) / 5
        } ?: 0f
        val distance = y + extra - handle.bottom
        ui.onRoot().performTouchInput {
            down(start)
            for (i in 1..5) moveTo(start + Offset(0f, distance * i / 5))
        }
    }

    @Test fun longPressRailThenDragLowerHandleSnapsOnlyTheEndOnRelease() {
        runBlocking { store.apply(TaskAdd(TaskInput("写提纲", ref = "a")), once("@a", at(15, 45), 30)) }
        val before = readDay(); val task = before.occurrences.single()
        show(); centerTick("16:15")
        ui.onNodeWithTag("resize-end:${task.taskId}").assertDoesNotExist()
        armEnd(requireNotNull(task.taskId))
        // 16:33 snaps forward to 16:35. No long press is needed on the revealed endpoint.
        dragEndTo(requireNotNull(task.taskId), "16:30", slightlyToward = "16:45")
        await(hasText("15:45–16:35 · 松手调整结束时间")).assertIsDisplayed()
        assertEquals(before, readDay())
        ui.onRoot().performTouchInput { up() }
        val after = readDay { it.revision > before.revision }
        assertEquals(before.revision + 1, after.revision)
        assertEquals(task.copy(endMillis = at(16, 35).toInstant().toEpochMilli(), version = after.revision), after.occurrences.single())
        centerTick("16:15"); armEnd(requireNotNull(task.taskId)); dragEndTo(requireNotNull(task.taskId), "16:00")
        await(hasText("15:45–16:00 · 松手调整结束时间"))
        ui.onRoot().performTouchInput { up() }
        assertEquals(task.copy(endMillis = at(16, 0).toInstant().toEpochMilli(), version = after.revision + 1),
            readDay { it.revision > after.revision }.occurrences.single())
    }

    @Test fun endpointAllowsOverlapPreviewButCancellationOutsideAndChangedRevisionNeverWrite() {
        runBlocking {
            store.apply(TaskAdd(TaskInput("提纲", ref = "a")), once("@a", at(15, 45), 30),
                TaskAdd(TaskInput("已约", ref = "b")), once("@b", at(16, 30), 30), TaskFinish("@b"))
        }
        val before = readDay()
        val (task, done) = before.occurrences
        show(); centerTick("16:15")
        ui.onNodeWithTag("resize-bar:${done.taskId}").assertDoesNotExist()
        armEnd(requireNotNull(task.taskId)); dragEndTo(requireNotNull(task.taskId), "16:45")
        await(hasText("15:45–16:45 · 松手调整结束时间")).assertIsDisplayed()
        ui.onRoot().performTouchInput { cancel() }
        assertEquals(before, readDay())
        dragEndTo(requireNotNull(task.taskId), "16:30")
        await(hasText("15:45–16:30 · 松手调整结束时间"))
        ui.onRoot().performTouchInput { cancel() }
        ui.onNodeWithTag("drop-preview").assertDoesNotExist()
        assertEquals(before, readDay())
        dragEndTo(requireNotNull(task.taskId), "16:30")
        val outside = ui.onNodeWithTag("day-drop-area").fetchSemanticsNode().boundsInRoot.bottom + 2f
        ui.onRoot().performTouchInput { moveTo(Offset(center.x, outside)); up() }
        assertEquals(before, readDay())
        dragEndTo(requireNotNull(task.taskId), "16:30")
        await(hasTestTag("drop-preview"))
        runBlocking { store.apply(TaskAdd(TaskInput("另一个任务"))) }
        ui.onRoot().performTouchInput { up() }
        val current = readDay()
        assertEquals(before.occurrences, current.occurrences); assertEquals(before.revision + 1, current.revision)
    }

    @Test fun overnightEndCanCrossTheRepeatedHourWithoutMovingItsPreviousDayStart() {
        val date = LocalDate.of(2026, 11, 1)
        val zone = ZoneId.of("America/New_York")
        val start = date.minusDays(1).atTime(23, 45).atZone(zone).toOffsetDateTime()
        val q = PlannerQuery.Day(date, zone)
        fun snapshot() = runBlocking { store.api.observe(q).first() as DayPlan }
        runBlocking { store.apply(TaskAdd(TaskInput("跨午夜", ref = "a")), once("@a", start, 120)) }
        val task = snapshot().occurrences.single()
        ui.setContent {
            val vm = viewModel { WorkspaceViewModel(store.api) }
            StackLifeTheme { TodayRoute(vm, date, zone, {}, {}, {}, null, {}, {}) }
        }
        await(hasTestTag("day-axis")); centerTick("01:45:-04:00")
        armEnd(requireNotNull(task.taskId)); dragEndTo(requireNotNull(task.taskId), "01:15:-05:00")
        await(hasText("10/31 23:45–01:15 -05:00 · 松手调整结束时间"))
        ui.onRoot().performTouchInput { up() }
        ui.waitUntil(10_000) { snapshot().occurrences.single().endMillis != task.endMillis }
        assertEquals(task.copy(endMillis = start.plusMinutes(150).toInstant().toEpochMilli(), version = snapshot().revision), snapshot().occurrences.single())
    }

    @Test fun selectingLongRailRevealsRealEndAndHoldingHandleAtEdgeExtendsPreviewOnly() {
        runBlocking { store.apply(TaskAdd(TaskInput("长时段", ref = "a")), once("@a", at(10, 0), 540)) }
        val before = readDay(); val id = requireNotNull(before.occurrences.single().taskId)
        show()
        val middle = centerTick("12:00")
        val axis = ui.onNodeWithTag("day-axis")
        fun scroll() = axis.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val bar = await(hasTestTag("resize-bar:$id"))
        val press = Offset(bar.fetchSemanticsNode().boundsInRoot.center.x, middle.y)
        ui.onRoot().performTouchInput { longClick(press) }
        val handle = await(hasTestTag("resize-end:$id"))
        handle.assertIsDisplayed().assertContentDescriptionContains("19:00", substring = true)
        val box = handle.fetchSemanticsNode().boundsInRoot
        val endTick = ui.dayTick("19:00")
        assertEquals(endTick.fetchSemanticsNode().boundsInRoot.top, box.bottom, 2f)
        val drop = ui.onNodeWithTag("day-drop-area").fetchSemanticsNode().boundsInRoot
        val tray = ui.onNodeWithTag("day-tray-container").fetchSemanticsNode().boundsInRoot
        val floor = if (box.center.x in tray.left..tray.right) minOf(drop.bottom, tray.top) else drop.bottom
        assertTrue("Revealed handle must clear the tray", box.bottom <= floor)
        val edge = Offset(box.center.x, floor - 4f)
        ui.mainClock.autoAdvance = false
        try {
            ui.onRoot().performTouchInput { down(box.center); moveTo(edge) }
            ui.mainClock.advanceTimeBy(64)
            val first = scroll()
            val preview = ui.onNode(hasAnyAncestor(hasTestTag("drop-preview")) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            fun range() = preview.fetchSemanticsNode().config[SemanticsProperties.Text].single().text.substringBefore(" · ")
            val earlier = range()
            ui.mainClock.advanceTimeBy(640)
            val later = range()
            assertTrue(scroll() > first)
            assertTrue(earlier.startsWith("10:00–") && later.startsWith("10:00–"))
            assertNotEquals(earlier, later)
            ui.onRoot().performTouchInput { cancel() }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.onNodeWithTag("drop-preview").assertDoesNotExist()
        assertEquals(before, readDay())
    }

    private fun dateHeading(date: LocalDate) = hasTestTag("day-date") and
        hasContentDescription("${date.year}年${date.monthValue}月${date.dayOfMonth}日", substring = true)

    @Test fun revisitingTodayShowsNowWhileRotationPreservesAChosenScrollAndCollapsedTray() {
        attachRestorableWindow()
        val toggle = hasTestTag("day-tray-toggle")
        await(toggle and hasStateDescription("已展开"))
        ui.onNodeWithText("队列中没有待排任务").assertIsDisplayed()
        ui.onNode(toggle).performTouchInput { click() }
        await(toggle and hasStateDescription("已收起"))
        // Far from now but not at the midnight stop, where the tray intentionally leaves the screen.
        val away = if (LocalTime.now().hour < 12) "17:00" else "07:00"
        centerTick(away)
        val axis = hasTestTag("day-axis")
        ui.onNodeWithTag("now-line").assertDoesNotExist()
        await(axis).performTouchInput { swipeLeft() }
        await(dateHeading(query.date.plusDays(1))).assertIsDisplayed()
        await(toggle and hasStateDescription("已收起"))
        await(hasTestTag("nav:workspace")).performClick()
        await(hasTestTag("nav:today")).performClick()
        await(toggle and hasStateDescription("已收起"))
        await(dateHeading(query.date)).assertIsDisplayed()
        await(hasTestTag("now-line")).assertIsDisplayed()
        centerTick(away)
        ui.onNodeWithTag("now-line").assertDoesNotExist()
        await(hasTestTag("nav:today")).performClick()
        await(hasTestTag("now-line")).assertIsDisplayed()
        centerTick(away)
        await(toggle and hasStateDescription("已收起")).assertIsDisplayed()
        val before = await(axis).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        ui.activityRule.scenario.recreate(); attachRestorableWindow()
        await(dateHeading(query.date)).assertIsDisplayed()
        await(toggle and hasStateDescription("已收起")).assertIsDisplayed()
        ui.onNodeWithTag("day-tray").assertDoesNotExist()
        assertEquals(before, await(axis).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        assertEquals(0L, readDay().revision)
    }

    @Test fun openingTodayFromSettingsOnTomorrowReturnsToTheCurrentDayAndTime() {
        show()
        ui.onNodeWithContentDescription("后一天").performClick()
        await(dateHeading(query.date.plusDays(1))).assertIsDisplayed()
        await(hasContentDescription("设置") and hasClickAction()).performClick()
        val settings = hasAnyAncestor(hasTestTag("route:settings"))
        val jump = hasTestTag("open-feature:today")
        await(hasScrollToIndexAction() and settings).performScrollToNode(jump)
        await(jump and settings).performClick()
        val feature = hasAnyAncestor(hasTestTag("route:feature:today"))
        await(dateHeading(query.date) and feature).assertIsDisplayed()
        await(hasTestTag("now-line") and feature).assertIsDisplayed()
    }

    @Test fun explicitLandingWinsThisVisitWithoutPaintingTheEventAndNextVisitReturnsToNow() {
        val hour = if (LocalTime.now().hour < 12) 21 else 3
        runBlocking { store.apply(TaskAdd(TaskInput("提醒落点", ref = "target")), once("@target", at(hour, 0), 30)) }
        val event = readDay().occurrences.single()
        val reveal = mutableStateOf<OccurrenceKey?>(event.key)
        val visit = mutableStateOf<Int?>(null)
        val accent = Color.Magenta
        var consumed = 0
        ui.setContent {
            val model = viewModel { WorkspaceViewModel(store.api) }
            StackLifeTheme(Appearance(reduceMotion = true)) {
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(secondaryContainer = accent)) {
                    TodayRoute(model, query.date, query.zone, {}, {}, {}, reveal.value,
                        { assertEquals(event.key, it); consumed++; reveal.value = null }, {}, todayVisit = visit.value)
                }
            }
        }
        await(hasTestTag("day-axis"))
        ui.runOnIdle { assertEquals(0, consumed); visit.value = 1 }
        ui.waitUntil(10_000) { reveal.value == null }
        val card = await(hasTestTag("block:${event.taskId}")).assertIsDisplayed().assertHasClickAction()
        ui.onNodeWithTag("now-line").assertDoesNotExist()
        val pixels = card.captureToImage().toPixelMap()
        val accentArgb = accent.toArgb()
        for (y in 0 until pixels.height step 4) for (x in 0 until pixels.width step 4) {
            assertNotEquals("Revealing a card must not apply a persistent container", accentArgb, pixels[x, y].toArgb())
        }
        ui.runOnIdle { assertEquals(1, consumed); visit.value = 2 }
        await(hasTestTag("now-line")).assertIsDisplayed()
        card.assertIsNotDisplayed()
    }

    @Test fun sameQueueSurfaceGrowsFromTheBallAndReversesBeforeEitherEndpoint() {
        runBlocking { store.apply(*(1..12).map { TaskAdd(TaskInput("待办 $it")) }.toTypedArray()) }
        show()
        val toggle = ui.onNodeWithTag("day-tray-toggle")
        val panel = ui.onNodeWithTag("day-tray-container")
        fun bounds() = panel.fetchSemanticsNode().boundsInRoot
        toggle.assert(hasStateDescription("已展开"))
        val initial = bounds()
        ui.assertFloatingAxisMeetsDock()
        toggle.performTouchInput { click() }
        val closed = bounds()
        val identity = panel.fetchSemanticsNode().id
        ui.assertFloatingAxisMeetsDock()
        toggle.assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp).assert(hasStateDescription("已收起"))
        val fab = ui.onNodeWithTag("add-task").fetchSemanticsNode().boundsInRoot
        assertEquals(fab.width, closed.width, 1f)
        assertEquals(fab.height, closed.height, 1f)
        assertEquals(fab.center.x, closed.center.x, 1f)
        assertEquals("展开待排队列", toggle.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        ui.onNodeWithTag("day-tray").assertDoesNotExist()
        ui.mainClock.autoAdvance = false
        try {
            toggle.performTouchInput { click() }; ui.mainClock.advanceTimeBy(112)
            val opening = bounds()
            assertTrue(opening.center.x != closed.center.x && opening.top < closed.top)
            assertTrue(opening.width > closed.width && opening.height > closed.height)
            toggle.performTouchInput { click() }; ui.mainClock.advanceTimeBy(96)
            val reversing = bounds()
            assertTrue(reversing.width < opening.width && reversing.width > closed.width)
            assertTrue(reversing.height < opening.height && reversing.height > closed.height)
            toggle.performTouchInput { click() }; ui.mainClock.advanceTimeBy(96)
            val growingAgain = bounds()
            assertTrue(growingAgain.width > reversing.width && growingAgain.height > reversing.height)
            ui.mainClock.advanceTimeBy(400)
            val open = bounds()
            assertTrue(open.width > opening.width && open.height > opening.height)
            assertTrue(open.width > growingAgain.width && open.height > growingAgain.height)
            assertEquals(identity, panel.fetchSemanticsNode().id)
            toggle.assert(hasStateDescription("已展开"))
            assertEquals("收起待排队列", toggle.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(initial, bounds())
        ui.onNodeWithTag("day-tray").performScrollToNode(hasText("待办 12"))
        val last = ui.onNodeWithText("待办 12")
        last.assertIsDisplayed().performTouchInput { click() }
        await(hasTestTag("slot:time")).assertExists()
        assertTrue(readDay().occurrences.isEmpty())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun focusedComposerAndKeyboardResizeTheActualDayViewport() {
        val hidden = WindowInsetsCompat.Builder().build()
        val insets = mutableStateOf(hidden)
        ui.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowInsets(insets.value)) {
                StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
            }
        }
        val axis = await(hasTestTag("day-axis"))
        fun bottom() = axis.fetchSemanticsNode().boundsInRoot.bottom
        val before = bottom()
        ui.onNodeWithTag("assistant-input").performSemanticsAction(SemanticsActions.RequestFocus)
        val height = with(ui.density) { 200.dp.roundToPx() }
        ui.runOnIdle {
            insets.value = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, height))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build()
        }
        ui.waitUntil(10_000) { bottom() < before - height / 4 }
        ui.assertFloatingAxisMeetsDock()
        ui.runOnIdle { insets.value = hidden }
        ui.waitUntil(10_000) { kotlin.math.abs(bottom() - before) <= 1f }
        ui.assertFloatingAxisMeetsDock()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun assertOverlayKeepsDayStable(pinned: Boolean, landscape: Boolean) {
        // Compare the rail to a uniform donor: neighboring plaid pixels can differ even without a rail.
        runBlocking { store.apply(EditAppearance(AppearancePatch.Replace(Appearance(floatingDayTray = !pinned, background = WallpaperSpec())))) }
        ui.setContent {
            if (landscape) DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(780.dp, 360.dp))) {
                StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
            } else StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
        }
        val toggle = await(hasTestTag("day-tray-toggle"))
        toggle.assert(hasStateDescription("已展开"))
        val axis = ui.onNodeWithTag("day-axis")
        fun bounds(tag: String) = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        // A moving tail inset used to silently clamp scroll only at the very end of a day.
        // Check an interior time first, regardless of the device clock.
        ui.dayTick("12:00").performScrollTo()
        assertEquals(bounds("assistant-composer").top, bounds("day-rail").bottom, 1f)
        axis.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        fun geometry(): List<Any> {
            val scroll = axis.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            return listOf(bounds("day-axis"), bounds("day-drop-area"), bounds("day-rail"),
                scroll.value(), scroll.maxValue(), ui.dayTick("23:45").getUnclippedBoundsInRoot().top)
        }
        val original = geometry()
        toggle.assertDoesNotExist()
        ui.onNodeWithTag("add-task").assertDoesNotExist()
        fun assertPaint() {
            ui.assertFloatingAxisMeetsDock()
            val rail = bounds("day-rail")
            val end = ui.dayMark("time-day-end").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Midnight is the final visible instant", end.bottom, rail.bottom, 1f)
            assertEquals("Only a fixed breathing space follows midnight",
                with(axis.fetchSemanticsNode().layoutInfo.density) { 16.dp.toPx() }, bounds("assistant-composer").top - end.bottom, 1f)
            val pixels = ui.onRoot().captureToImage().toPixelMap()
            val x = rail.center.x.toInt()
            val adjacent = rail.left.toInt() - 3
            for (rise in listOf(1, 4, 8)) {
                val y = rail.bottom.toInt() - rise
                assertNotEquals("Day rail disappeared before midnight at $x,$y", pixels[adjacent, y], pixels[x, y])
            }
            for (below in listOf(2, 6, 10)) {
                val y = rail.bottom.toInt() + below
                assertEquals("No rail may continue into the day-end inset at $x,$y", pixels[adjacent, y], pixels[x, y])
            }
            val scroll = axis.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals("There must be no scroll range beyond this boundary", scroll.maxValue(), scroll.value(), 0f)
        }
        assertPaint()
        axis.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -180f) }
        toggle.assertIsDisplayed().assert(hasStateDescription("已展开")).performClick()
        toggle.assert(hasStateDescription("已收起"))
        axis.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        toggle.assertDoesNotExist(); ui.onNodeWithTag("add-task").assertDoesNotExist()
        assertEquals(original, geometry())
        axis.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -180f) }
        toggle.assertIsDisplayed().assert(hasStateDescription("已收起")).performClick()
        axis.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        assertEquals(original, geometry())
        assertPaint()
    }

    @Test fun pinnedQueueIsOnlyAnOverlayAtEndOfEmptyDay() = assertOverlayKeepsDayStable(true, false)
    @Test fun landscapePinnedQueueNeverMovesTheClockOrViewport() = assertOverlayKeepsDayStable(true, true)
    @Test fun floatingQueueDoesNotChangeScrollRangeAtEndOfDay() = assertOverlayKeepsDayStable(false, false)

    private fun selectDayView(mode: String) {
        await(hasTestTag("day-view")).performClick()
        await(hasTestTag("day-view-$mode")).performClick()
        val label = ui.activity.getString(if (mode == "events") R.string.today_events else R.string.today_time)
        await(hasTestTag("day-view")).assert(hasStateDescription(label))
            .assertContentDescriptionEquals(ui.activity.getString(R.string.today_switch_view))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
    }

    @Test fun midnightStopsJustAboveAssistantAndLastTaskIsReachable() {
        runBlocking {
            store.apply(TaskAdd(TaskInput("Late class", ref = "last")), once("@last", at(23, 30), 30),
                TaskAdd(TaskInput("Unscheduled task")))
        }
        val before = readDay()
        show()
        val axis = ui.onNodeWithTag("day-axis")
        axis.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        fun bounds(tag: String) = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val end = ui.dayMark("time-day-end").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val rail = bounds("day-rail")
        assertEquals(end.bottom, rail.bottom, 1f)
        assertEquals(with(ui.density) { 16.dp.toPx() }, bounds("assistant-composer").top - end.bottom, 1f)
        assertEquals("The 23:30–00:00 task ends at the same boundary", end.bottom, bounds("block:${before.occurrences.single().taskId}").bottom, 1f)
        ui.onNodeWithTag("day-tray-toggle").assertDoesNotExist()
        ui.onNodeWithTag("add-task").assertDoesNotExist()
        val complete = ui.activity.getString(R.string.agenda_complete, "Late class")
        ui.onNodeWithContentDescription(complete).performTouchInput { click() }
        val finished = readDay { it.occurrences.single().completed }
        assertEquals(before.tray, finished.tray)
        selectDayView("events")
        assertEquals(bounds("assistant-composer").top, bounds("day-rail").bottom, 1f)
        selectDayView("time")
        assertEquals(end, ui.dayMark("time-day-end").fetchSemanticsNode().boundsInRoot)
        assertEquals(rail, bounds("day-rail"))
        ui.onNodeWithTag("day-tray-toggle").assertDoesNotExist()
        ui.onNodeWithTag("block:${before.occurrences.single().taskId}").performTouchInput { click() }
        await(hasTestTag("slot:time")).assertIsDisplayed()
    }

    @Test fun eventViewCompressesFreeTimeAndAddsOnlyOneScheduledTaskOnConfirmation() {
        runBlocking {
            store.apply(TaskAdd(TaskInput("早班", ref = "a", icon = TaskIcon.PERSON)), once("@a", at(9, 0), 30),
                TaskAdd(TaskInput("评审", ref = "b")), once("@b", at(11, 0), 30))
        }
        val before = readDay()
        show()
        ui.onNodeWithTag("day-tray-toggle").performClick()
        selectDayView("events")
        val list = await(hasTestTag("day-events"))
        val early = before.occurrences.single { it.title == "早班" }
        val blockTag = hasTestTag("block:${early.taskId}")
        list.performScrollToNode(blockTag)
        val symbol = hasContentDescription("人物") and hasAnyAncestor(hasTestTag("day-events"))
        ui.onAllNodes(symbol, useUnmergedTree = true).assertCountEquals(1)
        val glyph = ui.onNode(symbol, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val rail = ui.onNodeWithTag("day-rail").fetchSemanticsNode().boundsInRoot
        assertEquals(rail.center.x, glyph.center.x, 2f)
        await(blockTag).assertHasClickAction()
        val gap = hasTestTag("day-gap:${at(9, 30).toInstant().toEpochMilli()}")
        val firstGap = hasTestTag("day-gap:${query.startMillis}")
        list.performScrollToNode(firstGap)
        val nineHours = ui.onNode(firstGap).fetchSemanticsNode().size.height
        list.performScrollToNode(gap)
        assertEquals("Long free periods use one compact row, not proportional whitespace", nineHours,
            await(gap).fetchSemanticsNode().size.height)
        await(gap).assert(hasText("空闲 · 1 小时 30 分钟")).performClick()
        await(hasTestTag("gap-title")).performTextInput("在空档读论文")
        ui.chooseClock("gap-time", 10, 15)
        ui.chooseDuration("gap-duration", 46)
        ui.onNodeWithTag("gap-save").assertIsNotEnabled()
        assertEquals(before, readDay())
        ui.chooseDuration("gap-duration", 15)
        await(hasTestTag("gap-save") and isEnabled()).performClick()
        val after = readDay { it.revision > before.revision }
        assertEquals(before.revision + 1, after.revision)
        val task = after.occurrences.single { it.title == "在空档读论文" }
        assertEquals(TaskIcon.TASK, task.icon)
        assertEquals(at(10, 15).toInstant().toEpochMilli(), task.startMillis)
        assertEquals(at(10, 30).toInstant().toEpochMilli(), task.endMillis)
        assertEquals(3, after.occurrences.size); assertTrue(after.tray.isEmpty())
        assertEquals(1, runBlocking { store.state().queue.count { it.id == task.taskId } })
        val block = hasTestTag("block:${task.taskId}")
        list.performScrollToNode(block)
        await(block).performClick()
        await(hasText("取消安排") and hasClickAction()).performScrollTo().performClick()
        assertEquals(task.taskId, readDay { it.occurrences.size == 2 }.tray.single().id)
        await(hasTestTag("day-events")).assertIsDisplayed()
    }

    @Test fun emptyEventDaySurvivesDateTabAndRotationAndRefusesStaleGapDraft() {
        attachRestorableWindow()
        await(hasTestTag("day-axis"))
        selectDayView("events")
        fun bounds(tag: String) = await(hasTestTag(tag)).fetchSemanticsNode().boundsInRoot
        val rail = bounds("day-rail")
        val list = bounds("day-events")
        assertEquals(bounds("assistant-composer").top, rail.bottom, 1f)
        assertEquals(rail.bottom, list.bottom, 1f)
        assertFalse(rail.overlaps(bounds("day-tray-container")))
        ui.onNodeWithTag("day-tray-toggle").performClick()
        assertEquals(rail, bounds("day-rail")); assertEquals(list, bounds("day-events"))
        ui.onNodeWithContentDescription("后一天").performClick()
        await(hasTestTag("nav:workspace")).performClick()
        await(hasTestTag("nav:today")).performClick()
        ui.activityRule.scenario.recreate(); attachRestorableWindow()
        val gap = await(hasTestTag("day-gap:${query.startMillis}"))
        gap.assert(hasText("空闲 · ${(query.endMillis - query.startMillis) / 3_600_000L} 小时")).performClick()
        await(hasTestTag("gap-title")).performTextInput("过期待办")
        runBlocking { store.apply(TaskAdd(TaskInput("同步变化"))) }
        await(hasText("日程已变更，请关闭并重新选择空闲时段。"))
        ui.onNodeWithTag("gap-save").assertIsNotEnabled()
        ui.onNodeWithText("取消").performClick()
        assertEquals(listOf("同步变化"), runBlocking { store.state().queue.map { it.title } })
        selectDayView("time"); await(hasTestTag("day-axis")).assertIsDisplayed()
    }

    @Test fun eventViewFixedCourseOpensItsActualDetails() {
        val date = LocalDate.of(2026, 11, 2)
        val term = TermSpec("秋季", date.minusWeeks(8), 18, BellSchedule.SUMMER, date)
        runBlocking {
            val draft = store.api.stageCourseImport(term, listOf(term.meeting(9, 1, "操作系统", "B", 5, 6)))
            try { store.apply(CourseImport(draft)) } finally { store.api.discardCourseImport(draft) }
        }
        ui.setContent {
            val vm = viewModel { WorkspaceViewModel(store.api) }
            StackLifeTheme { TodayRoute(vm, date, campusZone, {}, {}, {}, null, {}, {}) }
        }
        await(hasTestTag("day-axis")); selectDayView("events")
        val course = hasTestTag("day-course")
        await(hasTestTag("day-events")).performScrollToNode(course)
        val card = await(course).assert(hasText("固定课程") and hasText("14:00–15:35"))
        val title = ui.onNode(hasText("操作系统") and hasAnyAncestor(course), useUnmergedTree = true)
        val bounds = title.fetchSemanticsNode().boundsInRoot.intersect(card.fetchSemanticsNode().boundsInRoot)
        ui.onRoot().performTouchInput { click(bounds.center) }
        await(hasText("B") and hasAnyAncestor(hasTestTag("course-detail"))).performScrollTo().assertIsDisplayed()
        await(hasText("第 9 周") and hasAnyAncestor(hasTestTag("course-detail"))).performScrollTo().assertIsDisplayed()
    }

    @Test fun reducedMotionKeepsRtlKeyboardFocusAndTheLastDaySlotCanClearTheControls() {
        runBlocking { store.apply(EditAppearance(AppearancePatch.Replace(Appearance(reduceMotion = true)))) }
        lateinit var inputMode: InputModeManager
        ui.setContent {
            inputMode = LocalInputModeManager.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
            }
        }
        val toggle = await(hasTestTag("day-tray-toggle"))
        toggle.assert(hasStateDescription("已展开")).performTouchInput { click() }
        fun box(tag: String) = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val ball = box("day-tray-container"); val fab = box("add-task")
        assertTrue(ball.center.x < box("day-axis").center.x && ball.top > fab.bottom)
        assertEquals(fab.center.x, ball.center.x, 1f)
        ui.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        toggle.performSemanticsAction(SemanticsActions.RequestFocus)
        toggle.assertIsFocused()
        ui.mainClock.autoAdvance = false
        try {
            ui.onRoot().performKeyInput { pressKey(Key.Enter) }; ui.mainClock.advanceTimeByFrame()
            val open = box("day-tray-container")
            assertTrue(open.width > ball.width && open.height > ball.height)
            toggle.assertIsFocused().assert(hasStateDescription("已展开"))
            ui.mainClock.advanceTimeBy(144); assertEquals(open, box("day-tray-container"))
            ui.onRoot().performKeyInput { pressKey(Key.Enter) }; ui.mainClock.advanceTimeByFrame()
            assertEquals(ball, box("day-tray-container"))
        } finally { ui.mainClock.autoAdvance = true }
        toggle.assertIsFocused()
        ui.onNodeWithTag("day-axis").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        val tick = ui.dayTick("23:45").fetchSemanticsNode().boundsInRoot
        assertTrue(tick.height > 0f && !tick.overlaps(fab) && !tick.overlaps(ball))
        assertEquals(with(ui.density) { 16.dp.toPx() },
            box("assistant-composer").top - ui.dayMark("time-day-end").fetchSemanticsNode().boundsInRoot.bottom, 1f)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun showThreeTrayRows(): DayPlan {
        runBlocking { store.apply(TaskAdd(TaskInput("first")), TaskAdd(TaskInput("middle")), TaskAdd(TaskInput("last"))) }
        val before = readDay()
        ui.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(410.dp, 900.dp))) {
                StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
            }
        }
        await(hasTestTag("day-axis"))
        ui.dayTick("12:00").performScrollTo()
        await(hasTestTag("tray:${before.tray.last().id}")).assertIsDisplayed()
        return before
    }

    @Test fun middleTrayPickupReflowsAndInterruptedSnapCommitsTheReleaseTimeOnly() {
        val before = showThreeTrayRows()
        val middle = before.tray[1]
        fun box(tag: String) = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val firstTime = centerTick("15:45").copy(x = box("day-drop-area").left + 12f)
        val laterTime = firstTime.copy(y = ui.dayTick("16:00").fetchSemanticsNode().boundsInRoot.top)
        val open = box("day-tray-container")
        val lastTop = box("tray:${before.tray.last().id}").top - open.top
        val row = box("tray:${middle.id}")
        val grab = Offset(row.left + 10f, row.center.y)
        val viewport = box("day-drop-area")
        val root = ui.onRoot()
        ui.mainClock.autoAdvance = false
        try {
            root.performTouchInput { down(grab); advanceEventTime(700); moveBy(Offset(0f, 1f)) }
            ui.mainClock.advanceTimeBy(480)
            ui.onNodeWithTag("tray:${middle.id}").assertDoesNotExist()
            ui.onNodeWithText(middle.title).assertDoesNotExist()
            val copy = ui.onNodeWithTag("day-tray-pickup")
            copy.assertIsDisplayed().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            val reduced = box("day-tray-container")
            assertTrue(reduced.height < open.height)
            assertTrue(box("tray:${before.tray.last().id}").top - reduced.top < lastTop)
            assertEquals(viewport, box("day-drop-area"))
            ui.assertFloatingAxisMeetsDock()
            assertEquals(before, readDay())

            root.performTouchInput { moveTo(firstTime) }
            ui.mainClock.advanceTimeBy(480)
            assertEquals(firstTime.y, box("drop-interval").top, 2f)
            assertEquals(firstTime.x - 10f, copy.fetchSemanticsNode().positionInRoot.x, 2f)
            root.performTouchInput { moveTo(laterTime) }
            ui.mainClock.advanceTimeBy(48)
            val moving = box("drop-interval").top
            assertTrue("The bound must cross an intermediate position", firstTime.y < moving && moving < laterTime.y)
            ui.mainClock.advanceTimeBy(96)
            assertEquals("The snap settles without a long spring tail", laterTime.y, box("drop-interval").top, 2f)
            ui.onNodeWithText("16:00–16:30 · 松手安排").assertIsDisplayed()
            assertEquals(before, readDay())
            root.performTouchInput { moveTo(firstTime); up() }
        } finally { ui.mainClock.autoAdvance = true }
        val after = readDay { it.revision > before.revision }
        val scheduled = after.occurrences.single()
        assertEquals(before.revision + 1, after.revision)
        assertEquals(middle.id, scheduled.taskId)
        assertEquals(Owner.QUEUE, scheduled.owner)
        assertEquals(at(15, 45).toInstant().toEpochMilli(), scheduled.startMillis)
        assertEquals(at(16, 15).toInstant().toEpochMilli(), scheduled.endMillis)
        val queue = runBlocking { store.state().queue }
        assertEquals(before.tray.map { it.id }, queue.map { it.id })
        assertNotNull(queue.single { it.id == middle.id }.schedule)
        assertEquals(before.tray.filterNot { it.id == middle.id }, after.tray)
        ui.onNodeWithTag("day-tray-toggle").assert(hasStateDescription("已展开"))
        ui.onNodeWithTag("day-tray-pickup").assertDoesNotExist()
    }

    @Test fun exposedSourceTraySecondFingerAndReassignedPickupCannotCreateAnAppointment() {
        val before = showThreeTrayRows()
        val task = before.tray[1]
        fun bounds(tag: String) = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val target = centerTick("12:00")
        val origin = bounds("tray:${task.id}").center
        val open = bounds("day-tray-container")
        val root = ui.onRoot()
        fun restored() {
            ui.onNodeWithTag("day-tray-pickup").assertDoesNotExist()
            ui.onNodeWithTag("tray:${task.id}").assertIsDisplayed()
            assertEquals(before, readDay())
            assertEquals(before.tray, runBlocking { store.state().queue })
        }
        pickup(origin, target)
        val exposed = Offset(open.left + 4f, open.top + 2f)
        assertFalse(bounds("day-tray-container").contains(exposed))
        root.performTouchInput { moveTo(exposed); up() }
        restored()
        pickup(origin, target)
        root.performTouchInput { down(1, target + Offset(8f, 4f)); up(1); up(0) }
        restored()
        root.performTouchInput {
            down(origin); down(1, target); up(1); advanceEventTime(700); moveTo(target); up()
        }
        restored()
        pickup(origin, target)
        await(hasTestTag("drop-preview")).assertIsDisplayed()
        runBlocking { store.apply(FramePush("interrupted", listOf(task.id))) }
        root.performTouchInput { up() }
        ui.waitForIdle()
        val current = readDay()
        assertEquals(before.revision + 1, current.revision)
        assertTrue(current.occurrences.isEmpty())
        assertEquals(before.tray.filterNot { it.id == task.id }, current.tray)
        assertEquals(current.tray, runBlocking { store.state().queue })
    }

    @Test fun dragFromTrayThenMoveSameBlockAndCancelInEditor() {
        runBlocking { store.apply(TaskAdd(TaskInput("报告提纲"))) }
        val task = readDay().tray.single()
        show()
        val target = centerTick("15:45")
        val tray = await(hasTestTag("tray:${task.id}"))
        pickup(tray.fetchSemanticsNode().boundsInRoot.center, target)
        await(hasText("15:45–16:15 · 松手安排")).assertIsDisplayed()
        assertEquals(listOf(task), readDay().tray); assertTrue(readDay().occurrences.isEmpty())
        ui.onRoot().performTouchInput { up() }
        val first = readDay { it.occurrences.isNotEmpty() }.occurrences.single()
        assertEquals(task.id, first.taskId); assertEquals(at(15, 45).toInstant().toEpochMilli(), first.startMillis)
        assertEquals(at(16, 15).toInstant().toEpochMilli(), first.endMillis)
        val block = await(hasTestTag("block:${task.id}"))
        val move = centerTick("16:00")
        val box = block.fetchSemanticsNode().boundsInRoot
        pickup(Offset(box.center.x, box.top + 5f), move + Offset(0f, 5f))
        await(hasText("16:00–16:30 · 松手安排")).assertIsDisplayed()
        assertEquals(first, readDay().occurrences.single()); ui.onRoot().performTouchInput { up() }
        val moved = readDay { it.occurrences.singleOrNull()?.startMillis == at(16, 0).toInstant().toEpochMilli() }.occurrences.single()
        assertEquals(task.id, moved.taskId); assertEquals(at(16, 30).toInstant().toEpochMilli(), moved.endMillis)
        await(hasTestTag("block:${task.id}")).performClick()
        await(hasText("取消安排") and hasClickAction()).performScrollTo().performClick()
        assertEquals(listOf(task.id), readDay { it.occurrences.isEmpty() }.tray.map { it.id })
        await(hasTestTag("tray:${task.id}")).assertIsDisplayed()
        assertEquals(listOf(task), runBlocking { store.state().queue })
    }

    @Test fun overlapIsAllowedButOutsideDropAndPointerCancelDoNotWrite() {
        runBlocking { store.apply(TaskAdd(TaskInput("已约", ref = "busy")), once("@busy", at(15, 45), 30), TaskAdd(TaskInput("待排"))) }
        val before = readDay(); val task = before.tray.single()
        show()
        val inside = centerTick("16:00")
        val source = await(hasTestTag("tray:${task.id}"))
        val start = source.fetchSemanticsNode().boundsInRoot.center
        // The translucent list has real times underneath. Holding and releasing on the row
        // itself (not just returning after an excursion) must not schedule one of them.
        ui.onRoot().performTouchInput { down(start); advanceEventTime(700); moveBy(Offset(0f, 1f)); up() }
        ui.onNodeWithTag("drop-preview").assertDoesNotExist()
        assertEquals(before, readDay())
        pickup(start, inside)
        await(hasText("16:00–16:30 · 松手安排")).assertIsDisplayed()
        ui.onRoot().performTouchInput { cancel() }
        assertEquals(before, readDay())
        val free = centerTick("17:00")
        pickup(start, free)
        await(hasText("17:00–17:30 · 松手安排"))
        ui.onRoot().performTouchInput { moveTo(start); up() }
        assertEquals(before, readDay())
        for (chrome in listOf("add-task", "assistant-composer", "navigation-capsule")) {
            pickup(start, free); await(hasTestTag("drop-preview"))
            val covered = ui.onNodeWithTag(chrome).fetchSemanticsNode().boundsInRoot.center
            ui.onRoot().performTouchInput { moveTo(covered) }
            ui.onNodeWithTag("drop-preview").assertDoesNotExist()
            ui.onRoot().performTouchInput { up() }
            assertEquals(before, readDay())
        }
        pickup(start, free)
        await(hasTestTag("drop-preview"))
        ui.onRoot().performTouchInput { cancel() }
        ui.onNodeWithTag("drop-preview").assertDoesNotExist()
        assertEquals(before, readDay())
    }

    @Test fun draggingClippedTailKeepsTheGrabOffsetAcrossMidnight() {
        val previous = query.date.minusDays(1).atTime(23, 45).atZone(query.zone).toOffsetDateTime()
        runBlocking { store.apply(TaskAdd(TaskInput("跨午夜", ref = "n")), once("@n", previous, 45)) }
        val original = readDay().occurrences.single(); show()
        val grab = centerTick("00:15")
        val target = ui.dayTick("01:15").fetchSemanticsNode().boundsInRoot.top
        pickup(grab, grab.copy(y = target))
        await(hasText("00:45–01:30 · 松手安排")).assertIsDisplayed()
        assertEquals(original, readDay().occurrences.single()); ui.onRoot().performTouchInput { up() }
        val moved = readDay { it.occurrences.single().startMillis == at(0, 45).toInstant().toEpochMilli() }.occurrences.single()
        assertEquals(original.taskId, moved.taskId); assertEquals(at(1, 30).toInstant().toEpochMilli(), moved.endMillis)
        val yesterday = PlannerQuery.Day(query.date.minusDays(1), query.zone)
        assertTrue(runBlocking { (store.api.observe(yesterday).first() as DayPlan).occurrences.isEmpty() })
    }

    @Test fun stationaryEdgeScrollAdvancesTheGhostAndStaleReleaseCannotWrite() {
        runBlocking { store.apply(TaskAdd(TaskInput("报告提纲"))) }
        val task = readDay().tray.single(); show(); centerTick("13:00")
        val axis = ui.onNodeWithTag("day-axis")
        val bounds = ui.onNodeWithTag("day-drop-area").fetchSemanticsNode().boundsInRoot
        val start = ui.onNodeWithTag("tray:${task.id}").fetchSemanticsNode().boundsInRoot.center
        val tray = ui.onNodeWithTag("day-tray-container").fetchSemanticsNode().boundsInRoot
        val edge = Offset(tray.center.x, minOf(tray.top, bounds.bottom) - 4f)
        fun scroll() = axis.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val root = ui.onRoot()
        ui.mainClock.autoAdvance = false
        try {
            pickup(start, edge)
            ui.mainClock.advanceTimeBy(48)
            val before = scroll()
            val preview = ui.onNode(hasAnyAncestor(hasTestTag("drop-preview")) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            val first = preview.fetchSemanticsNode().config[SemanticsProperties.Text].single().text.substringBefore('·')
            ui.mainClock.advanceTimeBy(640)
            assertTrue("Stationary pointer did not scroll the day", scroll() > before)
            val later = preview.fetchSemanticsNode().config[SemanticsProperties.Text].single().text.substringBefore('·')
            assertNotEquals("Edge scroll must update the snapped interval", first, later)
            assertTrue(readDay().occurrences.isEmpty())
            runBlocking { store.apply(TaskAdd(TaskInput("同时到达的待办"))) }
            root.performTouchInput { up() }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        await(hasText("日程已更新", substring = true) or hasText("日程已有变更", substring = true)).assertIsDisplayed()
        assertTrue(readDay().occurrences.isEmpty())
        assertEquals(listOf("报告提纲", "同时到达的待办"), readDay().tray.map { it.title })
    }

    @Test fun accessibleEditorReusesTaskAndFrameOccupancy() {
        runBlocking { store.apply(TaskAdd(TaskInput("报告提纲"))) }
        val task = readDay().tray.single(); show()
        val tray = await(hasTestTag("tray:${task.id}"))
        assertEquals("安排时间", tray.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        tray.performSemanticsAction(SemanticsActions.OnClick)
        await(hasTestTag("slot:time"))
        ui.chooseClock("slot:time", 15, 45)
        val save = hasText("保存时段") and hasClickAction() and isEnabled()
        await(save).performScrollTo().performClick()
        val scheduled = readDay { it.occurrences.isNotEmpty() }.occurrences.single()
        assertEquals(task.id, scheduled.taskId); assertEquals(at(15, 45).toInstant().toEpochMilli(), scheduled.startMillis)
        val block = hasTestTag("block:${task.id}")
        await(block).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        await(hasTestTag("slot:time"))
        ui.chooseClock("slot:time", 16, 0)
        ui.chooseDuration("slot:duration", 40)
        await(save).performScrollTo().performClick()
        val changed = readDay { it.occurrences.singleOrNull()?.endMillis == at(16, 40).toInstant().toEpochMilli() }.occurrences.single()
        // Change ownership through the real workbench; assert the same block is live on returning to Today.
        await(hasTestTag("nav:workspace")).performClick()
        await(hasText("压栈") and hasClickAction()).performClick()
        assertEquals(changed.copy(owner = Owner.FRAME), readDay { it.occurrences.single().owner == Owner.FRAME }.occurrences.single())
        await(hasTestTag("nav:today")).performClick(); await(hasTestTag("day-axis")); centerTick("16:00")
        await(hasText("已压栈 · 仍占用时段", substring = true)).assertIsDisplayed()
        await(hasTestTag("nav:workspace")).performClick(); await(hasText("栈") and hasClickAction()).performClick()
        val frame = runBlocking { store.state().frames.single() }
        await(hasTestTag("frame:${frame.id}")).performClick()
        await(hasText("全部恢复") and hasClickAction()).performClick()
        assertEquals(changed, readDay { it.occurrences.single().owner == Owner.QUEUE }.occurrences.single())
        assertEquals(listOf(task.id), runBlocking { store.state().queue.map { it.id } })
    }

    private fun captureSchedule(name: String, surface: SemanticsNodeInteraction, stage: String = "U5") {
        if (InstrumentationRegistry.getArguments().getString("capture$stage") != "yes") return
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val view = (surface.fetchSemanticsNode().root as ViewRootForTest).view
            val committed = CountDownLatch(1)
            ui.runOnUiThread { view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }; view.invalidate() }
            assertTrue("Schedule window frame was not submitted", committed.await(4, TimeUnit.SECONDS))
        }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "${stage.lowercase()}-$name.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { image.recycle() }
    }

    @Test fun warmLandingOnAnotherDateAcknowledgesTheHiddenOccurrenceInItsOwnDrawer() {
        val later = PlannerQuery.Day(query.date.plusDays(3), query.zone)
        val atLater = later.date.atTime(18, 55).atZone(query.zone).toOffsetDateTime()
        runBlocking {
            store.apply(*buildList {
                add(TaskAdd(TaskInput("第一个提醒", ref = "first"))); add(once("@first", at(9, 0), 0))
                for (i in 1..6) {
                    add(TaskAdd(TaskInput("后续提醒$i", ref = "later$i"))); add(once("@later$i", atLater, 0))
                }
            }.toTypedArray())
        }
        val first = readDay().occurrences.single()
        val hidden = runBlocking { (store.api.observe(later).first() as DayPlan).occurrences.last() }
        val owner = mutableStateOf(query.date)
        val reveal = mutableStateOf<OccurrenceKey?>(first.key)
        val consumed = mutableListOf<OccurrenceKey>()
        ui.setContent {
            val model = viewModel { WorkspaceViewModel(store.api) }
            StackLifeTheme { TodayRoute(model, owner.value, query.zone, { owner.value = it }, {}, {}, reveal.value,
                { key -> consumed.add(key); if (reveal.value == key) reveal.value = null }, {}) }
        }
        ui.waitUntil(10_000) { reveal.value == null }
        await(hasTestTag("block:${first.taskId}")).assertIsDisplayed()
        ui.runOnIdle { owner.value = later.date; reveal.value = hidden.key }
        ui.waitUntil(10_000) { reveal.value == null }
        await(hasTestTag("overlap-item:${hidden.stableKey}")).assertIsDisplayed()
        ui.runOnIdle { assertEquals(listOf(first.key, hidden.key), consumed); assertEquals(later.date, owner.value) }
    }

    @Test fun twentiethPointCanBeMovedAndCompletedWithoutMovingOrCompletingItsSeries() {
        val pattern = SchedulePattern.Daily(listOf(DailySegment(query.date.toEpochDay(), 10 * 60, query.zone.id, 0)))
        runBlocking { store.apply(*buildList {
            for (i in 1..20) {
                add(TaskAdd(TaskInput("单点$i", notes = "笔记$i", minutes = 0, ref = "p$i")))
                add(SetSchedule("@p$i", pattern))
            }
        }.toTypedArray()) }
        val before = readDay()
        val target = before.occurrences.last()
        show(); ui.onNodeWithTag("day-tray-toggle").performClick()
        centerTick("10:00")
        val more = await(hasTestTag("overlap:${target.startMillis}"))
        more.assertTextContains("+18").assertIsDisplayed()
        captureSchedule("points-overlap-closed", more)
        more.performTouchInput { click() }
        val list = await(hasTestTag("overlap-list"))
        val shell = ui.onNodeWithTag("planner-sheet-shell", useUnmergedTree = true)
        val window = ui.onNode(isDialog(), useUnmergedTree = true).getUnclippedBoundsInRoot()
        if (shell.getUnclippedBoundsInRoot().top > window.top + 1.dp) {
            ui.onNodeWithTag("planner-sheet-handle").performClick()
        }
        assertEquals(window.top.value, shell.getUnclippedBoundsInRoot().top.value, 1f)
        list.performScrollToIndex(before.occurrences.size / 4)
        captureSchedule("points-drawer-middle", list)
        val row = hasTestTag("overlap-item:${target.stableKey}")
        list.performScrollToNode(row)
        ui.onNode(row).assertIsDisplayed()
        captureSchedule("points-drawer-last", list)
        ui.onNode(hasText(target.title) and hasAnyAncestor(row) and hasClickAction()).performClick()
        await(hasTestTag("slot:scope")).assertTextContains("仅这次")
        ui.onNodeWithTag("slot:length").assertTextContains("单点事件")
        captureSchedule("point-editor", ui.onNodeWithTag("slot:length"))
        val lead = ui.onNodeWithText(ui.activity.getString(R.string.reminder_minutes_before, 15L)).performScrollTo().assertIsDisplayed()
        captureSchedule("point-reminder-editor", lead)
        ui.chooseClock("slot:time", 11, 30)
        val save = await(hasText("保存时段") and hasClickAction() and isEnabled()).performScrollTo()
        assertTrue("Save is above the assistant, not behind its attachment button", save.fetchSemanticsNode().boundsInRoot.bottom <=
            ui.onNodeWithTag("assistant-composer").fetchSemanticsNode().boundsInRoot.top + 1f)
        save.performTouchInput { click() }
        val moved = readDay { it.revision > before.revision }
        val same = moved.occurrences.single { it.key == target.key }
        assertEquals(at(11, 30).toInstant().toEpochMilli(), same.startMillis); assertTrue(same.point)
        assertTrue(same.conflicts.isEmpty())
        val peers = moved.occurrences.filter { it.key != same.key }
        assertTrue(peers.all { it.conflicts.isNotEmpty() })
        assertEquals(before.occurrences.dropLast(1).map { it.copy(conflicts = emptyList()) }, peers.map { it.copy(conflicts = emptyList()) })
        ui.onNodeWithTag("resize-bar:${same.stableKey}").assertDoesNotExist()
        ui.onNodeWithTag("resize-end:${same.stableKey}").assertDoesNotExist()
        val check = ui.activity.getString(R.string.slot_complete_this, same.title)
        await(hasContentDescription(check)).performClick()
        val marked = readDay { it.occurrences.any { e -> e.key == same.key && e.completed } }
        assertEquals(listOf(same.key), marked.occurrences.filter { it.completed }.map { it.key })
        val tomorrow = runBlocking { store.api.observe(PlannerQuery.Day(query.date.plusDays(1), query.zone)).first() as DayPlan }
        val next = tomorrow.occurrences.single { it.taskId == target.taskId }
        assertNotEquals(target.key, next.key); assertFalse(next.completed)
        assertEquals(query.date.plusDays(1).atTime(10, 0).atZone(query.zone).toInstant().toEpochMilli(), next.startMillis)
    }

    @Test fun draggingAPointFromMidnightRetainsZeroDurationAndTheOriginalKey() {
        runBlocking { store.apply(TaskAdd(TaskInput("零点", minutes = 0, ref = "p")), once("@p", at(0, 0), 0)) }
        val before = readDay().occurrences.single(); show()
        ui.onNodeWithTag("day-tray-toggle").performClick()
        centerTick("00:00")
        val block = await(hasTestTag("block:${before.taskId}"))
        ui.onNodeWithTag("resize-bar:${before.taskId}").assertDoesNotExist()
        val bounds = block.fetchSemanticsNode().boundsInRoot
        val grab = Offset(bounds.center.x, bounds.top + 2f)
        val target = ui.dayTick("00:30").fetchSemanticsNode().boundsInRoot.top
        pickup(grab, Offset(grab.x, target + 2f)); await(hasTestTag("drop-preview"))
        ui.onRoot().performTouchInput { up() }
        val moved = readDay { it.occurrences.single().startMillis != before.startMillis }.occurrences.single()
        assertEquals(before.key, moved.key); assertEquals(at(0, 30).toInstant().toEpochMilli(), moved.startMillis)
        assertEquals(moved.startMillis, moved.endMillis)
        block.performTouchInput { click() }; await(hasTestTag("slot:length")).assertTextContains("单点事件")
    }

    private fun appearance(value: Appearance) = runBlocking {
        store.apply(EditAppearance(AppearancePatch.Replace(value)))
    }

    // Android's real animator setting and accessibility-service flag, not test-only wheel inputs.
    // Restore both even if a pixel or gesture assertion fails on the isolated instrumentation device.
    private fun withWheelPlatform(test: (setAnimators: (Boolean) -> Unit, explore: (Boolean) -> Unit) -> Unit) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val automation = inst.uiAutomation
        val key = Settings.Global.ANIMATOR_DURATION_SCALE
        val resolver = inst.targetContext.contentResolver
        val original = Settings.Global.getString(resolver, key)
        val access = inst.targetContext.getSystemService(AccessibilityManager::class.java)
        val flags = automation.serviceInfo.flags
        val exploration = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        fun animators(value: String?) {
            val command = if (value == null) "settings delete global $key" else "settings put global $key $value"
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
            ui.waitUntil(5_000) {
                val stored = Settings.Global.getString(resolver, key)
                stored == value || value != null && stored?.toFloatOrNull() == value.toFloat()
            }
        }
        fun explore(on: Boolean) {
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = if (on) flags or exploration else flags and exploration.inv()
            }
            ui.waitUntil(5_000) { access.isTouchExplorationEnabled == on }
        }
        try {
            if (original?.toFloatOrNull()?.let { it > 0 } == false) animators("1")
            explore(false)
            test({ animators(if (it) "1" else "0") }, ::explore)
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            if (Settings.Global.getString(resolver, key) != original) animators(original)
        }
    }

    private fun painted(rect: Rect): IntArray {
        val image = ui.onRoot().captureToImage().toPixelMap()
        val x = rect.left.toInt().coerceIn(0, image.width - 1)
        val y = rect.top.toInt().coerceIn(0, image.height - 1)
        val w = (rect.right.toInt().coerceAtMost(image.width) - x).coerceAtLeast(1)
        val h = (rect.bottom.toInt().coerceAtMost(image.height) - y).coerceAtLeast(1)
        return IntArray(w * h) { image[x + it % w, y + it / w].toArgb() }
    }

    private fun chooseWheelInLayout() {
        ui.onNodeWithContentDescription("设置").performClick()
        await(hasText(ui.activity.getString(R.string.ui_layout)) and hasClickAction()).performClick()
        val list = await(hasTestTag("appearance-scroll"))
        val preview = hasTestTag("day-wheel-preview")
        fun previewInk(): IntArray {
            list.performScrollToNode(preview)
            val row = ui.onNode(preview).performScrollTo()
            val pixels = row.captureToImage().toPixelMap()
            return IntArray(pixels.width * pixels.height) { pixels[it % pixels.width, it / pixels.width].toArgb() }
        }
        val before = previewInk()
        val setting = hasText(ui.activity.getString(R.string.appearance_day_scroll)) and hasClickAction()
        list.performScrollToNode(setting); ui.onNode(setting).performScrollTo().performClick()
        await(hasText(ui.activity.getString(R.string.appearance_scroll_wheel)) and hasClickAction()).performClick()
        val after = previewInk()
        assertEquals(before.size, after.size)
        assertTrue("Changing the preference must repaint the clock rows", before.indices.count { before[it] != after[it] } > 100)
        captureSchedule("layout-wheel", ui.onNode(preview), "U8")
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.ui_back)).performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("appearance-scroll").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithContentDescription("返回").performClick()
        await(hasTestTag("day-axis"))
    }

    @Test fun wheelPaintsBothDayViewsAndSystemFallbacksRestoreTheirFlatPixels() = withWheelPlatform { setAnimators, explore ->
        runBlocking { store.apply(
            TaskAdd(TaskInput("晨间记录", minutes = 0, ref = "p")), once("@p", at(0, 30), 0),
            TaskAdd(TaskInput("整理照片", ref = "a")), once("@a", at(1, 30), 30),
            TaskAdd(TaskInput("准备清单", ref = "b")), once("@b", at(1, 30), 30),
            TaskAdd(TaskInput("核对行程", ref = "c")), once("@c", at(1, 30), 30),
            TaskAdd(TaskInput("读书", ref = "d")), once("@d", at(2, 30), 45)
        ) }
        val point = readDay().occurrences.first()
        val flat = Appearance(theme = ThemeMode.LIGHT, palette = PaletteMode.SEED,
            dayScrollEffect = DayScrollEffect.FLAT, background = WallpaperSpec())
        val wheel = flat.copy(dayScrollEffect = DayScrollEffect.WHEEL)
        appearance(flat); show()
        ui.onNodeWithTag("day-tray-toggle").performClick()
        centerTick("00:00")
        for (mode in listOf("time", "events")) {
            if (mode == "events") { appearance(flat); selectDayView(mode) }
            val cardTag = hasTestTag("block:${point.taskId}")
            val viewport = ui.onNodeWithTag(if (mode == "time") "day-axis" else "day-events")
            if (mode == "events") viewport.performScrollToNode(cardTag)
            val card = await(cardTag).assertIsDisplayed()
            val top = ui.onNodeWithTag("day-rail").fetchSemanticsNode().boundsInRoot.top
            val shift = card.fetchSemanticsNode().boundsInRoot.center.y - top - with(ui.density) { 28.dp.toPx() }
            viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, shift) }
            fun scrollOffset() = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val baselineOffset = scrollOffset()
            val region = card.fetchSemanticsNode().boundsInRoot
            val baseline = painted(region)
            val rail = ui.onNodeWithTag("day-rail").fetchSemanticsNode().boundsInRoot
            fun difference(): Int {
                val pixels = painted(region)
                return pixels.indices.count { i ->
                    val a = baseline[i]; val b = pixels[i]
                    kotlin.math.abs((a ushr 16 and 255) - (b ushr 16 and 255)) +
                        kotlin.math.abs((a ushr 8 and 255) - (b ushr 8 and 255)) +
                        kotlin.math.abs((a and 255) - (b and 255)) > 45
                }
            }
            captureSchedule("$mode-flat", card, "U8")
            if (mode == "time") {
                chooseWheelInLayout()
                // Returning to Today intentionally lands on the current minute, not the 00:30 fixture.
                val correction = baselineOffset - scrollOffset()
                viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, correction) }
                assertEquals(baselineOffset, scrollOffset(), 1f)
            } else appearance(wheel)
            ui.waitUntil(10_000) { difference() > maxOf(40, baseline.size / 180) }
            assertEquals(rail, ui.onNodeWithTag("day-rail").fetchSemanticsNode().boundsInRoot)
            captureSchedule("$mode-wheel", card, "U8")
            val inkChanged = difference()
            fun flatPixels() = ui.waitUntil(10_000) { difference() <= inkChanged / 20 }
            fun wheelPixels() = ui.waitUntil(10_000) { difference() >= inkChanged * 9 / 10 }
            appearance(wheel.copy(reduceMotion = true)); flatPixels()
            appearance(wheel); wheelPixels()
            setAnimators(false); flatPixels()
            setAnimators(true); wheelPixels()
            explore(true); flatPixels()
            explore(false); wheelPixels()

            val title = ui.onNode(hasText(point.title) and hasAnyAncestor(hasTestTag("block:${point.taskId}")), useUnmergedTree = true)
            val titleHit = title.fetchSemanticsNode().boundsInRoot.center
            ui.onRoot().performTouchInput { click(titleHit) }
            await(hasTestTag("slot:length")).assertTextContains("单点事件")
            ui.onNodeWithText(point.title).assertIsDisplayed()
            ui.onNodeWithContentDescription("返回").performClick()
            val overlap = hasTestTag("overlap:${at(1, 30).toInstant().toEpochMilli()}")
            if (mode == "events") await(hasTestTag("day-events")).performScrollToNode(overlap)
            val more = await(overlap).performScrollTo()
            val hit = more.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
            ui.onRoot().performTouchInput { click(hit) }
            await(hasTestTag("overlap-list")).performScrollToNode(hasText("核对行程"))
            ui.onNode(hasText("核对行程") and hasAnyAncestor(hasTestTag("overlap-list"))).assertIsDisplayed().performClick()
            await(hasTestTag("slot:time")); ui.onNodeWithText("核对行程").assertIsDisplayed()
            ui.onNodeWithContentDescription("返回").performClick()
            await(hasTestTag("day-view"))
        }
    }

    @Test fun wheelMovesPointAndLongCardByTheGrabbedTimeAndResizesOnlyTheEnd() = withWheelPlatform { _, _ ->
        runBlocking { store.apply(
            TaskAdd(TaskInput("出发", minutes = 0, ref = "p")), once("@p", at(0, 30), 0),
            TaskAdd(TaskInput("全天排练", ref = "l")), once("@l", at(2, 0), 1260)
        ) }
        val flat = Appearance(theme = ThemeMode.LIGHT, dayScrollEffect = DayScrollEffect.FLAT, background = WallpaperSpec())
        val wheel = flat.copy(dayScrollEffect = DayScrollEffect.WHEEL)
        appearance(flat); show()
        ui.onNodeWithTag("day-tray-toggle").performClick()
        val (point, long) = readDay().occurrences
        fun box(tag: String) = await(hasTestTag(tag)).fetchSemanticsNode().boundsInRoot
        fun y(t: String) = ui.dayTick(t).fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero).y
        fun proposed(time: String) {
            ui.onNode(hasAnyAncestor(hasTestTag("drop-preview")) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                useUnmergedTree = true).assertTextContains(time, substring = true)
        }
        fun move(grab: Offset, delta: Float) {
            pickup(grab, grab + Offset(0f, delta))
            await(hasTestTag("drop-preview")).assertIsDisplayed()
        }
        // The true centre (12:30) is below this viewport; only its clipped rail and body are visible.
        val firstMid = centerTick("07:30")
        val viewport = box("day-drop-area")
        assertTrue(ui.dayTick("12:30").fetchSemanticsNode().positionInRoot.y > viewport.bottom)
        fun railSample(y: Float): Rect {
            val x = box("resize-bar:${long.taskId}").center.x
            return Rect(x - 6 * ui.density.density, y - 4, x + 6 * ui.density.density, y + 4)
        }
        val expected = painted(railSample(firstMid.y))
        assertTrue("Flat reference must contain the painted rail", expected.distinct().size > 1)
        centerTick("00:00")
        // Sample inside the wheel band, outside the separately tested edge-autoscroll band.
        val deltaToEdge = box("block:${point.taskId}").center.y - viewport.top - with(ui.density) { 42.dp.toPx() }
        ui.onNodeWithTag("day-axis").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, deltaToEdge) }
        val pointRegion = box("block:${point.taskId}")
        val before = painted(pointRegion)
        appearance(wheel)
        ui.waitUntil(10_000) { val after = painted(pointRegion); before.indices.count { before[it] != after[it] } > 100 }
        val pointGrab = box("block:${point.taskId}").center
        move(pointGrab, y("01:00") - y("00:30"))
        proposed("01:00")
        assertEquals(point, readDay().occurrences.first())
        ui.onRoot().performTouchInput { up() }
        val movedPoint = readDay { it.occurrences.first().startMillis != point.startMillis }.occurrences.first()
        assertEquals(point.key, movedPoint.key); assertEquals(at(1, 0).toInstant().toEpochMilli(), movedPoint.startMillis)
        assertEquals(movedPoint.startMillis, movedPoint.endMillis)

        val mid = centerTick("07:30")
        assertArrayEquals("A viewport-spanning event must keep its visible stroke intact",
            expected, painted(railSample(mid.y)))
        val grab = Offset(box("block:${long.taskId}").center.x, mid.y)
        move(grab, y("08:00") - y("07:30"))
        proposed("02:30–23:30")
        assertEquals(long.startMillis, readDay().occurrences.last().startMillis)
        ui.onRoot().performTouchInput { up() }
        val moved = readDay { it.occurrences.last().startMillis != long.startMillis }.occurrences.last()
        assertEquals(long.key, moved.key); assertEquals(at(2, 30).toInstant().toEpochMilli(), moved.startMillis)
        assertEquals(at(23, 30).toInstant().toEpochMilli(), moved.endMillis)
        val railHit = Offset(box("resize-bar:${long.taskId}").center.x, mid.y)
        ui.onRoot().performTouchInput { longClick(railHit) }
        val handle = box("resize-end:${long.taskId}").center
        // Use unchanged clock ticks, not the projected bottom edge of the handle, as the ruler.
        val earlier = y("23:15") - y("23:30")
        ui.onRoot().performTouchInput { down(handle); moveTo(handle + Offset(0f, earlier)) }
        await(hasTestTag("drop-preview")).assertIsDisplayed()
        proposed("02:30–23:15")
        ui.onRoot().performTouchInput { up() }
        val resized = readDay { it.occurrences.last().endMillis != moved.endMillis }.occurrences.last()
        assertEquals(moved.startMillis, resized.startMillis); assertEquals(at(23, 15).toInstant().toEpochMilli(), resized.endMillis)
        assertEquals(moved.key, resized.key)
        val visibleBody = centerTick("07:30").copy(x = box("block:${long.taskId}").center.x)
        ui.onRoot().performTouchInput { click(visibleBody) }
        await(hasTestTag("slot:time")); ui.onNodeWithText(long.title).assertIsDisplayed()
    }

    @Test fun dateWheelCreatesBoundedDailyAndScopeChoicesPreservePastOrChangeTheWholeSeries() {
        runBlocking { store.apply(TaskAdd(TaskInput("每日校对", minutes = 15))) }
        val id = readDay().tray.single().id
        show(); await(hasTestTag("tray:$id")).performClick()
        await(hasTestTag("slot:repeat")).performClick()
        ui.onNodeWithText("每日重复").performClick()
        ui.chooseClock("slot:time", 9, 5)
        ui.chooseDate("slot:until", query.date.plusDays(2))
        val save = hasText("保存时段") and hasClickAction() and isEnabled()
        await(save).performScrollTo().performClick()
        val today = readDay { it.occurrences.any { e -> e.taskId == id } }.occurrences.single()
        fun onDate(delta: Long) = runBlocking {
            (store.api.observe(PlannerQuery.Day(query.date.plusDays(delta), query.zone)).first() as DayPlan).occurrences
        }
        assertEquals(OccurrenceKey.Schedule(id, query.date.toEpochDay()), today.key)
        assertTrue(onDate(3).isEmpty())
        ui.onNodeWithContentDescription("后一天").performClick()
        val next = onDate(1).single()
        val card = hasTestTag("block:${next.stableKey}")
        await(card).performScrollTo().performClick()
        await(hasTestTag("slot:scope")).performClick(); ui.onNodeWithText("这次及以后").performClick()
        ui.chooseClock("slot:time", 12, 45)
        await(save).performScrollTo().performClick()
        ui.waitUntil(8_000) { onDate(1).single().startMillis == query.date.plusDays(1).atTime(12,45).atZone(query.zone).toInstant().toEpochMilli() }
        assertEquals(today.startMillis, onDate(0).single().startMillis)
        assertEquals(next.key, onDate(1).single().key)
        await(card).performScrollTo().performClick()
        await(hasTestTag("slot:scope")).performClick(); ui.onNodeWithText("整个系列").performClick()
        ui.chooseClock("slot:time", 7, 10)
        await(save).performScrollTo().performClick()
        ui.waitUntil(8_000) { onDate(0).single().startMillis == at(7,10).toInstant().toEpochMilli() }
        assertEquals(today.key, onDate(0).single().key)
        assertEquals(query.date.plusDays(2).atTime(7,10).atZone(query.zone).toInstant().toEpochMilli(), onDate(2).single().startMillis)
        assertTrue(onDate(3).isEmpty())
    }
}
