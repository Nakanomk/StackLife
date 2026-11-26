package io.github.nakanomk.stacklife.acceptance

import android.animation.ValueAnimator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.room.withTransaction
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.transitions.noCardBounds
import io.github.nakanomk.stacklife.workspace.WorkspaceScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceTransferTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    private fun attach() = ui.activityRule.scenario.onActivity { activity ->
        activity.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
    }
    private fun prepare(reduced: Boolean, vararg commands: PlannerCommand): Workspace = runBlocking {
        store.apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(Appearance(start = Destination.WORKSPACE,
            background = WallpaperSpec(), glass = false, compact = true, theme = ThemeMode.LIGHT, reduceMotion = reduced))), *commands)
        store.state()
    }
    private fun result(check: (Workspace) -> Boolean): Workspace = runBlocking { withTimeout(8_000) {
        store.api.observe(PlannerQuery.Workspace).first { check(it as Workspace) } as Workspace
    } }
    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(8_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return ui.onNode(matcher)
    }
    private fun tag(id: String) = node(hasTestTag(id))
    private fun button(label: Int) = node(hasText(ui.activity.getString(label)) and hasClickAction())
    private fun moveClockToQueueInk(returning: Boolean) {
        ui.waitUntil(8_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodesWithTag("workspace-transfer", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Sample the queue side of the handoff, while the container is still well short of
        // its endpoint. Each direction must show *both* real titles, not just a frame outline.
        ui.mainClock.advanceTimeBy(if (returning) 200L else 64L)
    }
    private fun movingBounds(expanded: Rect, titles: List<Rect>, capture: String? = null): Rect {
        val group = ui.onNodeWithTag("workspace-transfer", useUnmergedTree = true)
        group.assertIsDisplayed()
        val coords = group.fetchSemanticsNode().layoutInfo.coordinates
        val box = ui.runOnIdle { Rect(coords.localToRoot(Offset.Zero),
            coords.localToRoot(Offset(coords.size.width.toFloat(), coords.size.height.toFloat()))) }
        // Synchronize the submitted window frame with the paused Compose clock. Read the
        // whole root, not the semantics node's independently rounded/transformed crop.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val submitted = java.util.concurrent.CountDownLatch(1)
            ui.runOnUiThread { with(ui.activity.window.decorView) {
                viewTreeObserver.registerFrameCommitCallback { submitted.countDown() }; invalidate()
            } }
            assertTrue("Workspace frame was not submitted", submitted.await(4, java.util.concurrent.TimeUnit.SECONDS))
        }
        val map = ui.onRoot().captureToImage().toPixelMap()
        if (capture != null && androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("captureMotion") == "yes") {
            val bitmap = android.graphics.Bitmap.createBitmap(map.buffer, map.bufferOffset, map.stride,
                map.width, map.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                java.io.File(ui.activity.getExternalFilesDir(null), "repair-$capture.png").outputStream().use {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
        fun ink(area: Rect): Int {
            var count = 0
            for (y in (area.top.toInt() + 2).coerceAtLeast(0) until (area.bottom.toInt() - 2).coerceAtMost(map.height) step 2)
                for (x in (area.left.toInt() + 2).coerceAtLeast(0) until (area.right.toInt() - 2).coerceAtMost(map.width) step 2)
                    if (map[x, y].luminance() < .45f) count++
            return count
        }
        assertTrue("No card ink at $box", ink(Rect(box.left, box.top, box.center.x, box.bottom)) > 12)
        fun x(p: Float) = box.left + (p - expanded.left) * box.width / expanded.width
        fun y(p: Float) = box.top + (p - expanded.top) * box.height / expanded.height
        for (title in titles) {
            val projected = Rect(x(title.left), y(title.top), x(title.right), y(title.bottom))
            assertTrue("Missing task title at $projected within $box", ink(projected) > 12)
        }
        return box
    }

    @Test fun theActualTwoCardGroupCompressesToItsReceiptFrameAndExpandsBackInOrder() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val before = prepare(false, PlannerCommand.TaskAdd(TaskInput("First meeting", "project agenda")),
            PlannerCommand.TaskAdd(TaskInput("Second meeting", "project notes")))
        val first = before.queue[0].id; val second = before.queue[1].id
        attach()
        val a = tag("task:$first").fetchSemanticsNode().boundsInRoot
        val b = tag("task:$second").fetchSemanticsNode().boundsInRoot
        val expanded = b.bottom - a.top
        val original = Rect(a.left, a.top, a.right, b.bottom)
        val titles = before.queue.map { task ->
            ui.onNode(hasText(task.title) and hasAnyAncestor(hasTestTag("task:${task.id}")), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
        }
        ui.mainClock.autoAdvance = false
        try {
            tag("queue-push").performClick()
            val saved = result { it.queue.isEmpty() }.frames.single()
            assertEquals(listOf(first, second), saved.items.map { it.taskId })
            moveClockToQueueInk(returning = false)
            val packing = movingBounds(original, titles)
            ui.mainClock.advanceTimeBy(1400)
            val frame = tag("frame:${saved.id}")
            val packed = frame.fetchSemanticsNode().boundsInRoot.height
            assertTrue("Group $expanded -> ${packing.height} -> frame $packed", packing.height < expanded && packing.height > packed)
            assertEquals(a.left, packing.left, 2f)
            ui.onNodeWithTag("workspace-transfer", useUnmergedTree = true).assertDoesNotExist()
            ui.mainClock.autoAdvance = true
            frame.performClick()
            val restore = button(R.string.ui_restore_all).performScrollTo()
            ui.mainClock.autoAdvance = false
            restore.performClick()
            val restored = result { it.queue.map(QueueTask::id) == listOf(first, second) }
            assertTrue(restored.frames.isEmpty())
            moveClockToQueueInk(returning = true)
            val unpacking = movingBounds(original, titles)
            ui.mainClock.advanceTimeBy(1400)
            val top = tag("task:$first").fetchSemanticsNode().boundsInRoot.top
            val bottom = tag("task:$second").fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("Frame $packed -> ${unpacking.height} -> group ${bottom - top}", unpacking.height > packed && unpacking.height < bottom - top)
            assertEquals(a.left, unpacking.left, 2f)
            ui.onNodeWithTag("workspace-transfer", useUnmergedTree = true).assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
        tag("receipt-keep").performClick()
        val kept = result { it.frames.singleOrNull()?.remaining == 0 }
        assertEquals(before.queue, kept.queue)
        tag("queue").assertIsDisplayed()
        tag("receipt-undo").performClick()
        val undone = result { it.frames.singleOrNull()?.remaining == 2 }
        assertTrue(undone.queue.isEmpty())
        tag("queue").assertIsDisplayed() // Undo is a data operation, not a second transfer
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun narrowReceiptKeepsHistoryBesideUndoAndCloseAboveWithoutRevertingTheQueue() {
        val before = prepare(true, PlannerCommand.TaskAdd(TaskInput("Pack this task")))
        val direction = mutableStateOf(LayoutDirection.Ltr)
        ui.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 760.dp)) then
                DeviceConfigurationOverride.FontScale(2f)) {
                CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                    StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
                }
            }
        }
        tag("queue-push").performClick()
        val frame = result { it.queue.isEmpty() }.frames.single()
        tag("frame:${frame.id}").performClick()
        button(R.string.ui_restore_all).performScrollTo().performClick()
        val restored = result { it.frames.isEmpty() && it.queue == before.queue }
        val close = hasContentDescription(ui.activity.getString(R.string.ui_close_panel)) and hasClickAction() and
            hasAnyAncestor(hasTestTag("push-feedback"))
        for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            ui.runOnIdle { direction.value = layout }
            val undo = tag("receipt-undo").assertContentDescriptionEquals("撤回").assertHasClickAction()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            val keep = tag("receipt-keep").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val x = node(close).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Close must not overlap undo: $x / $undo", x.bottom <= undo.top + 1f)
            assertEquals(x.center.x, undo.center.x, 1f)
            assertEquals(keep.center.y, undo.center.y, 1f)
            if (layout == LayoutDirection.Ltr) assertEquals(keep.right, undo.left, 1f)
            else assertEquals(keep.left, undo.right, 1f)
        }
        node(close).performClick()
        ui.onNodeWithTag("receipt-undo").assertDoesNotExist()
        assertEquals(restored, runBlocking { store.state() })
    }

    @Test fun longQueueFliesOnlyMeasuredRowsInBothDirectionsWithOneOrderedReceipt() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val before = prepare(false, *List(24) { PlannerCommand.TaskAdd(TaskInput("Meeting $it", "Agenda $it")) }.toTypedArray())
        val ids = before.queue.map { it.id }
        attach()
        val first = tag("task:${ids.first()}").fetchSemanticsNode().boundsInRoot
        val titles = before.queue.take(2).map { task ->
            ui.onNode(hasText(task.title) and hasAnyAncestor(hasTestTag("task:${task.id}")), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
        }
        ui.onNodeWithTag("task:${ids.last()}").assertDoesNotExist()
        fun sample(returning: Boolean): Pair<Rect, Float> {
            moveClockToQueueInk(returning)
            // The unscaled design size is the union of *participating* measured rows,
            // which may differ on return (receipt bar, scroll retention, insets).
            val size = ui.onNodeWithTag("workspace-transfer", useUnmergedTree = true).fetchSemanticsNode().size
            val group = first.copy(bottom = first.top + size.height)
            assertTrue("Not the whole queue: ${size.height}", size.height < first.height * ids.size)
            assertTrue("Both measured title slots should participate", titles.last().bottom < group.bottom)
            return movingBounds(group, titles, if (returning) "many-restore" else "many-push") to group.height
        }
        ui.mainClock.autoAdvance = false
        try {
            tag("queue-push").performClick()
            val pushed = result { it.queue.isEmpty() }
            val saved = pushed.frames.single()
            assertEquals(ids, saved.items.map { it.taskId })
            val (packing, expanded) = sample(false)
            ui.mainClock.advanceTimeBy(1400)
            val frame = tag("frame:${saved.id}")
            val folded = frame.fetchSemanticsNode().boundsInRoot.height
            assertTrue("Push must compress from measured rows to the saved frame", packing.height in folded..expanded &&
                packing.height < expanded - 1f && packing.height > folded + 1f)
            ui.mainClock.autoAdvance = true
            frame.performClick()
            val restore = button(R.string.ui_restore_all).performScrollTo()
            ui.mainClock.autoAdvance = false
            restore.performClick()
            val restored = result { it.queue.size == ids.size }
            val (expanding, landing) = sample(true)
            assertTrue("Restore must unfold from the same frame into real queue slots",
                expanding.height > folded + 1f && expanding.height < landing - 1f)
            ui.mainClock.advanceTimeBy(1400)
            tag("task:${ids.first()}").assertIsDisplayed()
            tag("queue").performScrollToKey(ids.last())
            tag("task:${ids.last()}").assertIsDisplayed()
            assertEquals(ids, restored.queue.map { it.id })
            assertTrue(restored.frames.isEmpty())
            assertEquals(before.revision + 2, restored.revision)
            ui.onNodeWithTag("workspace-transfer", useUnmergedTree = true).assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun narrowingDuringPushDropsTheFlightOntoItsRealFrameAndUndoRestoresTheOriginalQueue() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val before = prepare(false, PlannerCommand.FrameCreate("Earlier frame", listOf(TaskInput("Not moving"))),
            PlannerCommand.TaskAdd(TaskInput("Pack first", "first note")), PlannerCommand.TaskAdd(TaskInput("Pack second", "second note")))
        val oldFrame = before.frames.single()
        val window = mutableStateOf(DpSize(480.dp, 1000.dp))
        ui.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(window.value)) {
                StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
            }
        }
        val wide = tag("queue").fetchSemanticsNode().boundsInRoot.width
        val first = tag("task:${before.queue.first().id}").fetchSemanticsNode().boundsInRoot
        val last = tag("task:${before.queue.last().id}").fetchSemanticsNode().boundsInRoot
        ui.mainClock.autoAdvance = false
        try {
            tag("queue-push").performClick()
            val pushed = result { it.queue.isEmpty() }
            val target = pushed.frames.single { it.id != oldFrame.id }
            assertEquals(before.revision + 1, pushed.revision)
            assertEquals(before.queue.map { it.id }, target.items.map { it.taskId })
            moveClockToQueueInk(returning = false)
            val flight = ui.onNodeWithTag("workspace-transfer", useUnmergedTree = true)
            val folding = flight.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("The group must already be compressing", folding.height > 0f && folding.height < last.bottom - first.top)

            ui.runOnIdle { window.value = DpSize(300.dp, 1000.dp) }
            // Allow layout, invalidation and handoff, not enough virtual time for the spring to finish.
            repeat(3) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() }
            flight.assertDoesNotExist()
            val landed = ui.onNodeWithTag("frames").assertIsDisplayed().fetchSemanticsNode()
            assertTrue("The new viewport must invalidate the old row geometry", landed.boundsInRoot.width < wide)
            assertEquals("The real stack page must be fully landed, not sliding past another frame",
                landed.layoutInfo.coordinates.size.width.toFloat(), landed.boundsInRoot.width, 1f)
            ui.onNodeWithTag("frame:${target.id}").assertIsDisplayed().assertTextContains("Pack first", substring = true)
                .assertTextContains("Pack second", substring = true)
            assertEquals(pushed, runBlocking { store.state() })
        } finally { ui.mainClock.autoAdvance = true }
        tag("receipt-undo").performClick()
        val undone = result { it.queue.map { row -> row.id } == before.queue.map { row -> row.id } }
        assertEquals(before.copy(revision = before.revision + 2), undone)
        tag("frame:${oldFrame.id}").assertIsDisplayed()
    }

    @Test fun appendSubsetLandsInExistingQueueWithoutMovingTheOtherFrameItemOrReplayingOnReturn() {
        val before = prepare(true, PlannerCommand.TaskAdd(TaskInput("X")),
            PlannerCommand.FrameCreate("F", listOf(TaskInput("A"), TaskInput("B"))), PlannerCommand.SetRestorePolicy(RestorePolicy.APPEND))
        val frame = before.frames.single(); val x = before.queue.single(); val (a, b) = frame.items
        attach()
        button(R.string.ui_stack).performClick(); tag("frame:${frame.id}").performClick()
        button(R.string.ui_select_to_restore).performScrollTo().performClick()
        val pick = hasText("B") and isToggleable()
        ui.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex)).performScrollToNode(pick)
        ui.onNode(pick).performClick()
        button(R.string.ui_restore_selected).performClick()
        tag("task:${b.taskId}").assertIsDisplayed()
        val after = result { it.frames.singleOrNull()?.remaining == 1 }
        assertEquals(listOf(x.id, b.taskId), after.queue.map { it.id })
        assertEquals(a.taskId, after.frames.single().items.single { it.restorable }.taskId)
        ui.onNodeWithTag("workspace-transfer", useUnmergedTree = true).assertDoesNotExist()
        button(R.string.ui_stack).performClick(); tag("frame:${frame.id}").assertIsDisplayed()
        ui.activityRule.scenario.recreate(); attach()
        tag("frame:${frame.id}").assertIsDisplayed()
        assertEquals(after, runBlocking { store.state() })
    }

    @Test fun swapOpensRestoredTasksNotTheFrameCreatedFromTheOldQueue() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val before = prepare(false, PlannerCommand.TaskAdd(TaskInput("X")),
            PlannerCommand.FrameCreate("F", listOf(TaskInput("A"))), PlannerCommand.SetRestorePolicy(RestorePolicy.SWAP))
        val id = before.frames.single().items.single().taskId
        attach(); button(R.string.ui_stack).performClick(); tag("frame:${before.frames.single().id}").performClick()
        button(R.string.ui_restore_all).performScrollTo().performClick()
        tag("task:$id").assertIsDisplayed().assertTextContains("A")
        button(R.string.ui_queue).assertIsSelected()
        ui.onNodeWithTag("task:${before.queue.single().id}").assertDoesNotExist()
        val after = result { it.queue.singleOrNull()?.id == id }
        assertEquals(before.queue.single().id, after.frames.single().items.single().taskId)
        assertNotEquals(before.frames.single().id, after.frames.single().id)
        tag("receipt-undo").performClick()
        tag("task:${before.queue.single().id}").assertIsDisplayed()
        assertEquals(before.frames, runBlocking { store.state() }.frames)
    }

    @Test fun offscreenAppendOpensTheMovedTaskRatherThanFlyingToTheFirstVisibleNeighbor() {
        val tasks = List(18) { PlannerCommand.TaskAdd(TaskInput("Existing $it")) }.toTypedArray()
        val before = prepare(false, *tasks, PlannerCommand.FrameCreate("F", listOf(TaskInput("Appended"))),
            PlannerCommand.SetRestorePolicy(RestorePolicy.APPEND))
        val frame = before.frames.single()
        val moved = frame.items.single().taskId
        attach(); tag("queue").assertIsDisplayed()
        button(R.string.ui_stack).performClick(); tag("frame:${frame.id}").performClick()
        button(R.string.ui_restore_all).performScrollTo().performClick()
        tag("task:$moved").assertIsDisplayed()
        ui.onNodeWithTag("workspace-transfer", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(before.queue.map { it.id } + moved, runBlocking { store.state() }.queue.map { it.id })
    }

    @Test fun staleVisibleQueueCannotStartAnAnimationOrReplaceTheNewerQueue() {
        val stale = prepare(true, PlannerCommand.TaskAdd(TaskInput("Initially visible")))
        runBlocking { store.apply(PlannerCommand.TaskAdd(TaskInput("Added after the read"))) }
        val current = runBlocking { store.state() }
        // Deliberately hold the last Room emission, as when a workspace read lags a writer.
        ui.setContent {
            StackLifeTheme {
                val model = viewModel { WorkspaceViewModel(store.api) }
                val notice = remember { SnackbarHostState() }
                LaunchedEffect(model) {
                    notice.showSnackbar(model.notices.first(), duration = androidx.compose.material3.SnackbarDuration.Indefinite)
                }
                var stack by remember { mutableStateOf(false) }
                Box {
                    WorkspaceScreen(stale, model, {}, {}, {}, noCardBounds, stack, { stack = it }, {}, {})
                    SnackbarHost(notice, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        tag("queue-push").performClick()
        node(hasText("The workspace changed.", substring = true) or hasText("工作台已变更", substring = true)).assertIsDisplayed()
        tag("queue").assertIsDisplayed()
        ui.onNodeWithTag("workspace-transfer", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(current, runBlocking { store.state() })
    }

    @Test fun recreatingDuringAPendingPushConsumesExactlyThatWriteAndReturningLaterDoesNotReplayIt() {
        val before = prepare(true, PlannerCommand.TaskAdd(TaskInput("Save just once")))
        attach(); tag("queue-push").assertIsDisplayed()
        runBlocking {
            val locked = CompletableDeferred<Unit>()
            val unlock = CompletableDeferred<Unit>()
            val writer = launch(Dispatchers.IO) { store.db.withTransaction { locked.complete(Unit); unlock.await() } }
            try {
                withTimeout(8_000) { locked.await() }
                tag("queue-push").performClick()
                ui.activityRule.scenario.recreate(); attach()
            } finally { unlock.complete(Unit); writer.join() }
        }
        val after = result { it.queue.isEmpty() }
        assertEquals(before.revision + 1, after.revision)
        val saved = after.frames.single()
        tag("frame:${saved.id}").assertIsDisplayed()
        button(R.string.ui_queue).performClick()
        ui.activityRule.scenario.recreate(); attach()
        tag("queue").assertIsDisplayed()
        ui.onNodeWithTag("workspace-transfer", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(after, runBlocking { store.state() })
    }

    @Test fun aPushFromAnotherCallerCannotSteerAnOpenWorkspace() {
        val before = prepare(true, PlannerCommand.TaskAdd(TaskInput("External push")))
        attach(); tag("queue").assertIsDisplayed()
        ui.runOnIdle {
            androidx.lifecycle.ViewModelProvider(ui.activity)[WorkspaceViewModel::class.java]
                .start(PlannerCommand.FramePush(taskIds = listOf(before.queue.single().id)))
        }
        val after = result { it.queue.isEmpty() }
        tag("receipt-undo").assertIsDisplayed()
        tag("queue").assertIsDisplayed()
        ui.onNodeWithTag("workspace-transfer", useUnmergedTree = true).assertDoesNotExist()
        button(R.string.ui_stack).performClick(); tag("frame:${after.frames.single().id}").assertIsDisplayed()
    }
}
