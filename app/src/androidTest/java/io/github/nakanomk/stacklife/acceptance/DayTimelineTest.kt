package io.github.nakanomk.stacklife.acceptance

import io.github.nakanomk.stacklife.R
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
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
    val top = box("assistant-input").top
    val rail = box("day-rail")
    assertEquals("The time rail fills the viewport up to the assistant, including at maximum scroll", top, rail.bottom, 1f)
    assertEquals(top, box("day-axis").bottom, 1f)
    assertEquals(top, box("day-drop-area").bottom, 1f)
    assertFalse("Opaque queue must not obscure the rail", rail.overlaps(box("day-tray-container")))
}

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
        runBlocking { store.apply(TaskAdd(TaskInput("写提纲", ref = "a")), SlotSet("@a", at(15, 45), 30)) }
        val before = readDay(); val task = before.blocks.single()
        show(); centerTick("16:15")
        ui.onNodeWithTag("resize-end:${task.taskId}").assertDoesNotExist()
        armEnd(task.taskId)
        // 16:33 snaps forward to 16:35. No long press is needed on the revealed endpoint.
        dragEndTo(task.taskId, "16:30", slightlyToward = "16:45")
        await(hasText("15:45–16:35 · 松手调整结束时间")).assertIsDisplayed()
        assertEquals(before, readDay())
        ui.onRoot().performTouchInput { up() }
        val after = readDay { it.revision > before.revision }
        assertEquals(before.revision + 1, after.revision)
        assertEquals(task.copy(endMillis = at(16, 35).toInstant().toEpochMilli()), after.blocks.single())
        centerTick("16:15"); armEnd(task.taskId); dragEndTo(task.taskId, "16:00")
        await(hasText("15:45–16:00 · 松手调整结束时间"))
        ui.onRoot().performTouchInput { up() }
        assertEquals(task.copy(endMillis = at(16, 0).toInstant().toEpochMilli()),
            readDay { it.revision > after.revision }.blocks.single())
    }

    @Test fun endpointRejectsConflictCancellationOutsideAndChangedRevision() {
        runBlocking {
            store.apply(TaskAdd(TaskInput("提纲", ref = "a")), SlotSet("@a", at(15, 45), 30),
                TaskAdd(TaskInput("已约", ref = "b")), SlotSet("@b", at(16, 30), 30), TaskFinish("@b"))
        }
        val before = readDay()
        val (task, done) = before.blocks
        show(); centerTick("16:15")
        ui.onNodeWithTag("resize-bar:${done.taskId}").assertDoesNotExist()
        armEnd(task.taskId); dragEndTo(task.taskId, "16:45")
        await(hasText("时段冲突：", substring = true) and hasText("已约", substring = true)).assertIsDisplayed()
        ui.onRoot().performTouchInput { up() }
        await(hasText("知道了")).performClick(); assertEquals(before, readDay())
        dragEndTo(task.taskId, "16:30")
        await(hasText("15:45–16:30 · 松手调整结束时间"))
        ui.onRoot().performTouchInput { cancel() }
        ui.onNodeWithTag("drop-preview").assertDoesNotExist()
        assertEquals(before, readDay())
        dragEndTo(task.taskId, "16:30")
        val outside = ui.onNodeWithTag("day-drop-area").fetchSemanticsNode().boundsInRoot.bottom + 2f
        ui.onRoot().performTouchInput { moveTo(Offset(center.x, outside)); up() }
        assertEquals(before, readDay())
        dragEndTo(task.taskId, "16:30")
        await(hasTestTag("drop-preview"))
        runBlocking { store.apply(TaskAdd(TaskInput("另一个任务"))) }
        ui.onRoot().performTouchInput { up() }
        val current = readDay()
        assertEquals(before.blocks, current.blocks); assertEquals(before.revision + 1, current.revision)
    }

    @Test fun overnightEndCanCrossTheRepeatedHourWithoutMovingItsPreviousDayStart() {
        val date = LocalDate.of(2026, 11, 1)
        val zone = ZoneId.of("America/New_York")
        val start = date.minusDays(1).atTime(23, 45).atZone(zone).toOffsetDateTime()
        val q = PlannerQuery.Day(date, zone)
        fun snapshot() = runBlocking { store.api.observe(q).first() as DayPlan }
        runBlocking { store.apply(TaskAdd(TaskInput("跨午夜", ref = "a")), SlotSet("@a", start, 120)) }
        val task = snapshot().blocks.single()
        ui.setContent {
            val vm = viewModel { WorkspaceViewModel(store.api) }
            StackLifeTheme { TodayRoute(vm, date, zone, {}, {}, {}, null, {}, {}) }
        }
        await(hasTestTag("day-axis")); centerTick("01:45:-04:00")
        armEnd(task.taskId); dragEndTo(task.taskId, "01:15:-05:00")
        await(hasText("10/31 23:45–01:15 -05:00 · 松手调整结束时间"))
        ui.onRoot().performTouchInput { up() }
        ui.waitUntil(10_000) { snapshot().blocks.single().endMillis != task.endMillis }
        assertEquals(task.copy(endMillis = start.plusMinutes(150).toInstant().toEpochMilli()), snapshot().blocks.single())
    }

    @Test fun selectingLongRailRevealsRealEndAndHoldingHandleAtEdgeExtendsPreviewOnly() {
        runBlocking { store.apply(TaskAdd(TaskInput("长时段", ref = "a")), SlotSet("@a", at(10, 0), 540)) }
        val before = readDay(); val id = before.blocks.single().taskId
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

    @Test fun horizontalSwipeAndSavedStateRestoreKeepTheScrollAndCollapsedQueue() {
        val restore = StateRestorationTester(ui)
        restore.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        val toggle = hasTestTag("day-tray-toggle")
        await(toggle and hasStateDescription("已展开"))
        ui.onNodeWithText("队列中没有待排任务").assertIsDisplayed()
        ui.onNode(toggle).performTouchInput { click() }
        await(toggle and hasStateDescription("已收起"))
        centerTick("12:00")
        val axis = hasTestTag("day-axis")
        val before = await(axis).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        fun dateHeading(date: LocalDate) = hasTestTag("day-date") and
            hasContentDescription("${date.year}年${date.monthValue}月${date.dayOfMonth}日", substring = true)
        await(axis).performTouchInput { swipeLeft() }
        await(dateHeading(query.date.plusDays(1))).assertIsDisplayed()
        await(toggle and hasStateDescription("已收起"))
        await(hasTestTag("nav:workspace")).performClick()
        await(hasTestTag("nav:today")).performClick()
        await(dateHeading(query.date.plusDays(1))).assertIsDisplayed()
        await(toggle and hasStateDescription("已收起"))
        await(axis).performTouchInput { swipeRight() }
        await(dateHeading(query.date)).assertIsDisplayed()
        val restored = await(axis).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before, restored, 1f)
        restore.emulateSavedInstanceStateRestore()
        await(dateHeading(query.date)).assertIsDisplayed()
        await(toggle and hasStateDescription("已收起")).assertIsDisplayed()
        ui.onNodeWithTag("day-tray").assertDoesNotExist()
        assertEquals(before, await(axis).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        assertEquals(0L, readDay().revision)
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
        await(hasTestTag("slot:hour")).assertExists()
        assertTrue(readDay().blocks.isEmpty())
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
        runBlocking { store.apply(EditAppearance(AppearancePatch.Replace(Appearance(floatingDayTray = !pinned)))) }
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
        assertEquals(bounds("assistant-input").top, bounds("day-rail").bottom, 1f)
        axis.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        fun geometry(): List<Any> {
            val scroll = axis.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            return listOf(bounds("day-axis"), bounds("day-drop-area"), bounds("day-rail"),
                scroll.value(), scroll.maxValue(), ui.dayTick("23:45").getUnclippedBoundsInRoot().top)
        }
        val original = geometry()
        val panel = bounds("day-tray-container")
        fun assertPaint() {
            ui.assertFloatingAxisMeetsDock()
            val rail = bounds("day-rail")
            val end = ui.dayMark("time-day-end").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Midnight is the final visible instant", end.bottom, rail.bottom, 1f)
            assertEquals("No scrollable space between midnight and the assistant", end.bottom, bounds("assistant-input").top, 1f)
            val pixels = ui.onRoot().captureToImage().toPixelMap()
            val x = rail.center.x.toInt()
            val adjacent = rail.left.toInt() - 3
            for (rise in listOf(1, 4, 8)) {
                val y = rail.bottom.toInt() - rise
                assertNotEquals("Day rail disappeared before midnight at $x,$y", pixels[adjacent, y], pixels[x, y])
            }
            val scroll = axis.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals("There must be no scroll range beyond this boundary", scroll.maxValue(), scroll.value(), 0f)
        }
        assertPaint()
        ui.mainClock.autoAdvance = false
        try {
            toggle.performClick(); ui.mainClock.advanceTimeBy(144)
            assertNotEquals(panel, bounds("day-tray-container")); assertEquals(original, geometry())
            ui.mainClock.advanceTimeBy(640)
            toggle.assert(hasStateDescription("已收起")); ui.onNodeWithTag("day-tray").assertDoesNotExist()
            assertEquals(original, geometry())
            toggle.performClick(); ui.mainClock.advanceTimeBy(144)
            assertEquals(original, geometry())
            ui.mainClock.advanceTimeBy(640)
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(original, geometry()); assertEquals(panel, bounds("day-tray-container"))
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

    @Test fun midnightMeetsAssistantAndLastTaskIsReachableWithoutScrollingIntoTomorrow() {
        runBlocking {
            store.apply(TaskAdd(TaskInput("Late class", ref = "last")), SlotSet("@last", at(23, 30), 30),
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
        assertEquals(end.bottom, bounds("assistant-input").top, 1f)
        assertEquals("The 23:30–00:00 task ends at the same boundary", end.bottom, bounds("block:${before.blocks.single().taskId}").bottom, 1f)
        val source = bounds("tray:${before.tray.single().id}").center
        val dock = bounds("assistant-input").center
        for (cancelGesture in listOf(true, false)) {
            pickup(source, dock)
            ui.onNodeWithTag("drop-preview").assertDoesNotExist()
            ui.onRoot().performTouchInput { if (cancelGesture) cancel() else up() }
            assertEquals(before, readDay())
        }
        selectDayView("events")
        assertEquals(bounds("assistant-input").top, bounds("day-rail").bottom, 1f)
        selectDayView("time")
        assertEquals(end, ui.dayMark("time-day-end").fetchSemanticsNode().boundsInRoot)
        assertEquals(rail, bounds("day-rail"))
        ui.onNodeWithTag("day-tray-toggle").performClick()
        ui.onNodeWithTag("block:${before.blocks.single().taskId}").performTouchInput { click() }
        await(hasTestTag("slot:hour")).assertIsDisplayed()
    }

    @Test fun eventViewCompressesFreeTimeAndAddsOnlyOneScheduledTaskOnConfirmation() {
        runBlocking {
            store.apply(TaskAdd(TaskInput("早班", ref = "a")), SlotSet("@a", at(9, 0), 30),
                TaskAdd(TaskInput("评审", ref = "b")), SlotSet("@b", at(11, 0), 30))
        }
        val before = readDay()
        show()
        ui.onNodeWithTag("day-tray-toggle").performClick()
        selectDayView("events")
        val list = await(hasTestTag("day-events"))
        val gap = hasTestTag("day-gap:${at(9, 30).toInstant().toEpochMilli()}")
        val nineHours = ui.onNodeWithTag("day-gap:${query.startMillis}").fetchSemanticsNode().size.height
        list.performScrollToNode(gap)
        assertEquals("Long free periods use one compact row, not proportional whitespace", nineHours,
            await(gap).fetchSemanticsNode().size.height)
        await(gap).assert(hasText("空闲 · 1 小时 30 分钟")).performClick()
        await(hasTestTag("gap-title")).performTextInput("在空档读论文")
        ui.onNodeWithTag("gap-time").performTextReplacement("10:15")
        ui.onNodeWithTag("gap-duration").performTextReplacement("46")
        ui.onNodeWithTag("gap-save").assertIsNotEnabled()
        assertEquals(before, readDay())
        ui.onNodeWithTag("gap-duration").performTextReplacement("15")
        ui.onNodeWithTag("gap-save").assertIsEnabled().performClick()
        val after = readDay { it.revision > before.revision }
        assertEquals(before.revision + 1, after.revision)
        val task = after.blocks.single { it.title == "在空档读论文" }
        assertEquals(at(10, 15).toInstant().toEpochMilli(), task.startMillis)
        assertEquals(at(10, 30).toInstant().toEpochMilli(), task.endMillis)
        assertEquals(3, after.blocks.size); assertTrue(after.tray.isEmpty())
        assertEquals(1, runBlocking { store.state().queue.count { it.id == task.taskId } })
        val block = hasTestTag("block:${task.taskId}")
        list.performScrollToNode(block)
        await(block).performClick()
        await(hasText("取消安排") and hasClickAction()).performScrollTo().performClick()
        assertEquals(task.taskId, readDay { it.blocks.size == 2 }.tray.single().id)
        await(hasTestTag("day-events")).assertIsDisplayed()
    }

    @Test fun emptyEventDaySurvivesDateTabAndRotationAndRefusesStaleGapDraft() {
        val restore = StateRestorationTester(ui)
        restore.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        await(hasTestTag("day-axis"))
        selectDayView("events")
        fun bounds(tag: String) = await(hasTestTag(tag)).fetchSemanticsNode().boundsInRoot
        val rail = bounds("day-rail")
        val list = bounds("day-events")
        assertEquals(bounds("assistant-input").top, rail.bottom, 1f)
        assertEquals(rail.bottom, list.bottom, 1f)
        assertFalse(rail.overlaps(bounds("day-tray-container")))
        ui.onNodeWithTag("day-tray-toggle").performClick()
        assertEquals(rail, bounds("day-rail")); assertEquals(list, bounds("day-events"))
        ui.onNodeWithContentDescription("后一天").performClick()
        await(hasTestTag("nav:workspace")).performClick()
        await(hasTestTag("nav:today")).performClick()
        restore.emulateSavedInstanceStateRestore()
        val tomorrow = PlannerQuery.Day(query.date.plusDays(1), query.zone)
        val gap = await(hasTestTag("day-gap:${tomorrow.startMillis}"))
        gap.assert(hasText("空闲 · ${(tomorrow.endMillis - tomorrow.startMillis) / 3_600_000L} 小时")).performClick()
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
        await(course).assert(hasText("固定课程") and hasText("14:00–15:35")).performClick()
        await(hasText("教室：B")).assertIsDisplayed()
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
        assertEquals(box("assistant-input").top, ui.dayMark("time-day-end").fetchSemanticsNode().boundsInRoot.bottom, 1f)
    }

    @Test fun dragFromTrayThenMoveSameBlockAndCancelInEditor() {
        runBlocking { store.apply(TaskAdd(TaskInput("报告提纲"))) }
        val task = readDay().tray.single()
        show()
        val target = centerTick("15:45")
        val tray = await(hasTestTag("tray:${task.id}"))
        pickup(tray.fetchSemanticsNode().boundsInRoot.center, target)
        await(hasText("15:45–16:15 · 松手安排")).assertIsDisplayed()
        assertEquals(listOf(task), readDay().tray); assertTrue(readDay().blocks.isEmpty())
        ui.onRoot().performTouchInput { up() }
        val first = readDay { it.blocks.isNotEmpty() }.blocks.single()
        assertEquals(task.id, first.taskId); assertEquals(at(15, 45).toInstant().toEpochMilli(), first.startMillis)
        assertEquals(at(16, 15).toInstant().toEpochMilli(), first.endMillis)
        val block = await(hasTestTag("block:${task.id}"))
        val move = centerTick("16:00")
        val box = block.fetchSemanticsNode().boundsInRoot
        pickup(Offset(box.center.x, box.top + 5f), move + Offset(0f, 5f))
        await(hasText("16:00–16:30 · 松手安排")).assertIsDisplayed()
        assertEquals(first, readDay().blocks.single()); ui.onRoot().performTouchInput { up() }
        val moved = readDay { it.blocks.singleOrNull()?.startMillis == at(16, 0).toInstant().toEpochMilli() }.blocks.single()
        assertEquals(task.id, moved.taskId); assertEquals(at(16, 30).toInstant().toEpochMilli(), moved.endMillis)
        await(hasTestTag("block:${task.id}")).performClick()
        await(hasText("取消安排") and hasClickAction()).performScrollTo().performClick()
        assertEquals(listOf(task.id), readDay { it.blocks.isEmpty() }.tray.map { it.id })
        await(hasTestTag("tray:${task.id}")).assertIsDisplayed()
        assertEquals(listOf(task), runBlocking { store.state().queue })
    }

    @Test fun conflictingGestureOutsideDropAndPointerCancelDoNotWrite() {
        runBlocking { store.apply(TaskAdd(TaskInput("已约", ref = "busy")), SlotSet("@busy", at(15, 45), 30), TaskAdd(TaskInput("待排"))) }
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
        await(hasText("时段冲突：", substring = true) and hasText("已约", substring = true)).assertIsDisplayed()
        ui.onRoot().performTouchInput { up() }
        await(hasText("知道了")).performClick()
        assertEquals(before, readDay())
        val free = centerTick("17:00")
        pickup(start, free)
        await(hasText("17:00–17:30 · 松手安排"))
        ui.onRoot().performTouchInput { moveTo(start); up() }
        assertEquals(before, readDay())
        for (chrome in listOf("add-task", "assistant-input", "navigation-capsule")) {
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
        runBlocking { store.apply(TaskAdd(TaskInput("跨午夜", ref = "n")), SlotSet("@n", previous, 45)) }
        val original = readDay().blocks.single(); show()
        val grab = centerTick("00:15")
        val target = ui.dayTick("01:15").fetchSemanticsNode().boundsInRoot.top
        pickup(grab, grab.copy(y = target))
        await(hasText("00:45–01:30 · 松手安排")).assertIsDisplayed()
        assertEquals(original, readDay().blocks.single()); ui.onRoot().performTouchInput { up() }
        val moved = readDay { it.blocks.single().startMillis == at(0, 45).toInstant().toEpochMilli() }.blocks.single()
        assertEquals(original.taskId, moved.taskId); assertEquals(at(1, 30).toInstant().toEpochMilli(), moved.endMillis)
        val yesterday = PlannerQuery.Day(query.date.minusDays(1), query.zone)
        assertTrue(runBlocking { (store.api.observe(yesterday).first() as DayPlan).blocks.isEmpty() })
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
            assertTrue(readDay().blocks.isEmpty())
            runBlocking { store.apply(TaskAdd(TaskInput("同时到达的待办"))) }
            root.performTouchInput { up() }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        await(hasText("日程已更新", substring = true) or hasText("日程已有变更", substring = true)).assertIsDisplayed()
        assertTrue(readDay().blocks.isEmpty())
        assertEquals(listOf("报告提纲", "同时到达的待办"), readDay().tray.map { it.title })
    }

    @Test fun accessibleEditorReusesTaskAndFrameOccupancy() {
        runBlocking { store.apply(TaskAdd(TaskInput("报告提纲"))) }
        val task = readDay().tray.single(); show()
        val tray = await(hasTestTag("tray:${task.id}"))
        assertEquals("安排时间", tray.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        tray.performSemanticsAction(SemanticsActions.OnClick)
        await(hasTestTag("slot:hour")).performTextReplacement("15")
        ui.onNodeWithTag("slot:minute").performTextReplacement("45")
        val save = hasText("保存时段") and hasClickAction() and isEnabled()
        await(save).performScrollTo().performClick()
        val scheduled = readDay { it.blocks.isNotEmpty() }.blocks.single()
        assertEquals(task.id, scheduled.taskId); assertEquals(at(15, 45).toInstant().toEpochMilli(), scheduled.startMillis)
        val block = hasTestTag("block:${task.id}")
        await(block).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        await(hasTestTag("slot:hour")).performTextReplacement("16"); ui.onNodeWithTag("slot:minute").performTextReplacement("00")
        ui.onNodeWithTag("slot:duration").performTextReplacement("40")
        await(save).performScrollTo().performClick()
        val changed = readDay { it.blocks.singleOrNull()?.endMillis == at(16, 40).toInstant().toEpochMilli() }.blocks.single()
        // Change ownership through the real workbench; assert the same block is live on returning to Today.
        await(hasTestTag("nav:workspace")).performClick()
        await(hasText("压栈") and hasClickAction()).performClick()
        assertEquals(changed.copy(state = ScheduledState.FRAME), readDay { it.blocks.single().state == ScheduledState.FRAME }.blocks.single())
        await(hasTestTag("nav:today")).performClick(); await(hasTestTag("day-axis")); centerTick("16:00")
        await(hasText("已压栈 · 仍占用时段", substring = true)).assertIsDisplayed()
        await(hasTestTag("nav:workspace")).performClick(); await(hasText("栈") and hasClickAction()).performClick()
        val frame = runBlocking { store.state().frames.single() }
        await(hasTestTag("frame:${frame.id}")).performClick()
        await(hasText("全部恢复") and hasClickAction()).performClick()
        assertEquals(changed, readDay { it.blocks.single().state == ScheduledState.QUEUE }.blocks.single())
        assertEquals(listOf(task.id), runBlocking { store.state().queue.map { it.id } })
    }
}
