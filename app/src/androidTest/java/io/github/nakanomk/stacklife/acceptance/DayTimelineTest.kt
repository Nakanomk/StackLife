package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

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
        val tick = ui.onNodeWithTag("hour:$label", useUnmergedTree = true)
        tick.performScrollTo()
        val axis = ui.onNodeWithTag("day-axis")
        val center = axis.fetchSemanticsNode().boundsInRoot.center
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
        val target = ui.onNodeWithTag("hour:01:15", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
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
        val bounds = axis.fetchSemanticsNode().boundsInRoot
        val start = ui.onNodeWithTag("tray:${task.id}").fetchSemanticsNode().boundsInRoot.center
        val edge = Offset(bounds.center.x, bounds.bottom - 4f)
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
        await(hasTestTag("reason")).performTextInput("开会")
        ui.onNodeWithText("保存到栈").performClick()
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
