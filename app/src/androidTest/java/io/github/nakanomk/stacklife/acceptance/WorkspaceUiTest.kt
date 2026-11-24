package io.github.nakanomk.stacklife.acceptance

import android.os.Bundle
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.notes.NoteEditorKey
import io.github.nakanomk.stacklife.notes.NoteEditorScreen
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceUiTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)

    private fun show() {
        ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        await(hasTestTag("nav:workspace")).performClick()
        await(hasTestTag("queue"))
    }

    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(timeoutMillis = 10_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return ui.onNode(matcher)
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(matcher)
    }

    private fun awaitQueue(vararg titles: String) = runBlocking {
        withTimeout(8_000) {
            store.api.observe(PlannerQuery.Workspace).first { result ->
                (result as Workspace).queue.map { it.title } == titles.toList()
            } as Workspace
        }
    }

    @Test fun referenceFrameOpensCompletedTaskAndDatedOccurrenceWithoutChangingTheSnapshot() = runBlocking {
        val date = java.time.LocalDate.now().plusDays(2); val zone = java.time.ZoneId.systemDefault()
        val p = store.proposal(PlannerCommand.TaskAdd(TaskInput("已完成原项", "源笔记", ref="done")),
            PlannerCommand.TaskFinish("@done"), PlannerCommand.TaskAdd(TaskInput("每日原项", ref="d")),
            PlannerCommand.SetSchedule("@d", SchedulePattern.Daily(listOf(DailySegment(date.toEpochDay(),600,zone.id,0)))),
            PlannerCommand.GraphCreate("依赖", "g"), PlannerCommand.GraphPatch("@g",listOf(
                GraphEdit.LinkExisting(GraphLink.Task("@done"),ref="a"),GraphEdit.LinkExisting(GraphLink.Occurrence("@d",date.toEpochDay()),ref="b"),GraphEdit.AddEdge("@a","@b"))),
            PlannerCommand.GraphFrame("@g","@b","引用快照",ref="f"))
        assertTrue(store.api.commit(p.proposalId) is CommitResult.Applied)
        val frame=store.state().frames.single(); assertEquals(0, frame.remaining)
        ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }, initialDetail=io.github.nakanomk.stacklife.transitions.DetailTarget("frame",frame.id)) }
        await(hasTestTag("frame-source:0")).performScrollTo().performClick()
        await(hasText("已完成原项")).assertIsDisplayed(); await(hasText("已完成")).assertIsDisplayed()
        await(hasText("编辑任务") and hasClickAction()).performClick()
        await(hasTestTag("title:0")).performTextReplacement("编辑后的原项")
        await(hasText("保存修改") and hasClickAction()).performClick()
        await(hasText("编辑后的原项")).assertIsDisplayed()
        ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle(); scrollTo(hasTestTag("frame-source:1")); await(hasTestTag("frame-source:1")).performClick()
        await(hasTestTag("slot:scope")).assertTextContains("仅这次")
        assertEquals(frame,store.state().frames.single()); assertEquals(1,store.state().queue.size)
    }

    @Test fun addTaskFloatsAboveCenteredNavigationOnTodayAndInTheQueue() {
        ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        fun checkPlacement() {
            val fab = await(hasTestTag("add-task")).fetchSemanticsNode().boundsInRoot
            val nav = ui.onNodeWithTag("navigation-capsule").fetchSemanticsNode().boundsInRoot
            val compose = ui.onNodeWithTag("assistant-input").fetchSemanticsNode().boundsInRoot
            assertTrue("Add and the assistant must have independent targets", fab.bottom < compose.top)
            assertTrue("Composer is above destination navigation", compose.bottom <= nav.top)
            val page = ui.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Add belongs above navigation, not alongside it", fab.bottom < nav.top)
            assertTrue("Add belongs on the right of the page", fab.left > page.center.x)
            assertEquals("A page action must not displace navigation", page.center.x, nav.center.x, 1f)
        }
        checkPlacement()
        await(hasTestTag("day-tray-toggle") and hasStateDescription("已展开")).performTouchInput { click() }
        val ball = await(hasTestTag("day-tray-toggle") and hasStateDescription("已收起")).fetchSemanticsNode().boundsInRoot
        val add = ui.onNodeWithTag("add-task").fetchSemanticsNode().boundsInRoot
        assertEquals(add.center.x, ball.center.x, 1f)
        assertTrue(ball.top > add.bottom && ball.bottom < ui.onNodeWithTag("assistant-input").fetchSemanticsNode().boundsInRoot.top)
        ui.onNodeWithTag("add-task").performTouchInput { click() }
        await(hasTestTag("title:0")).performTextInput("从时间轴创建")
        val save = hasText("添加到队列") and hasClickAction()
        ui.onNode(save).assertIsDisplayed()
        ui.onNode(save).performClick()
        val task = awaitQueue("从时间轴创建").queue.single()
        await(hasTestTag("day-tray-toggle") and hasStateDescription("已收起")).performClick()
        await(hasTestTag("tray:${task.id}")).assertIsDisplayed()
        ui.onNodeWithTag("nav:workspace").performClick()
        await(hasTestTag("task:${task.id}")).assertIsDisplayed()
        checkPlacement()
        ui.onNodeWithTag("nav:courses").performClick()
        await(hasTestTag("course-import")).assertIsDisplayed()
        ui.onNodeWithTag("add-task").assertDoesNotExist()
    }

    @Test fun keyboardTraversalVisitsTheVisibleWorkbenchAndSharedDockNotHiddenRoots() {
        show()
        val fab = ui.onNodeWithTag("add-task")
        for (backwards in listOf(false, true)) {
            fab.performSemanticsAction(SemanticsActions.RequestFocus)
            var foundDock = false
            for (i in 0..24) {
                ui.onRoot().performKeyInput {
                    if (backwards) withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } else pressKey(Key.Tab)
                }
                val focused = ui.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
                assertTrue("Keyboard focus left the visible semantics tree", focused.isNotEmpty())
                for (node in focused) {
                    val bounds = node.boundsInRoot
                    assertTrue("Off-screen keyboard target: ${node.id}", bounds.width > 0 && bounds.height > 0)
                    val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
                    if (tag.startsWith("nav:") || tag == "assistant-input") foundDock = true
                }
                ui.onNodeWithTag("queue").assertIsDisplayed()
                if (foundDock) break
            }
            assertTrue("Keyboard must reach the shared dock in either direction", foundDock)
        }
    }

    @Test fun pushSelectBThenResumeAThroughTheWorkbench() {
        runBlocking { store.apply(PlannerCommand.TaskAdd(TaskInput("A")), PlannerCommand.TaskAdd(TaskInput("B"))) }
        show()
        ui.onNodeWithTag("queue-push").performSemanticsAction(SemanticsActions.OnClick) { press -> press(); press() }
        val pushed = awaitQueue().frames.single()
        assertEquals("", pushed.reason)
        await(hasText("已压栈")).assertIsDisplayed()
        ui.onNodeWithTag("push-reason").assertDoesNotExist()
        ui.onNodeWithText("补充原因").performClick()
        await(hasTestTag("push-reason")).performTextInput("去开会")
        ui.onNodeWithText("保存").performClick()
        ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodesWithTag("push-reason").fetchSemanticsNodes().isEmpty() }
        assertEquals(pushed.copy(reason = "去开会"), runBlocking { store.state() }.frames.single())
        await(hasText("队列是空的"))
        ui.onNodeWithContentDescription("新增任务").performClick()
        ui.onNodeWithTag("title:0").performTextInput("X")
        val add = hasText("添加到队列") and hasClickAction()
        ui.onNode(add).assertIsDisplayed()
        ui.onNode(add).performClick()
        val interrupted = awaitQueue("X")
        await(hasText("栈") and hasClickAction()).performClick()
        ui.onNodeWithTag("frame:${interrupted.frames.single().id}").performClick()
        ui.onNodeWithText("选择恢复").performClick()
        val selectB = hasText("B") and isToggleable()
        scrollTo(selectB)
        ui.onNode(selectB).performClick()
        ui.onNodeWithText("恢复所选").performClick()
        val partial = awaitQueue("B", "X")
        assertEquals(listOf(true, false), partial.frames.single().items.map { it.restorable })
        ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodesWithText("恢复所选").fetchSemanticsNodes().isEmpty() }
        val all = hasText("全部恢复") and hasClickAction()
        scrollTo(all)
        ui.onNode(all).performClick()
        val restored = awaitQueue("A", "B", "X")
        assertEquals(interrupted.frames.single().items.map { it.taskId } + interrupted.queue.single().id, restored.queue.map { it.id })
        assertEquals(0, restored.frames.single().remaining)
    }

    @Test fun pushReasonRevealsOneFieldInTheOriginalBottomBarAndCancelsBackToItsReceipt() {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        runBlocking { store.apply(PlannerCommand.TaskAdd(TaskInput("唯一待办"))) }
        show()
        ui.onNodeWithTag("queue-push").performClick()
        val pushed = awaitQueue().frames.single()
        val edit = await(hasText("补充原因") and isEnabled())
        val shell = ui.onNodeWithTag("push-feedback")
        val before = shell.fetchSemanticsNode()
        val bottom = before.boundsInRoot.bottom
        val editor = ui.onNodeWithTag("push-reason-editor")
        ui.mainClock.autoAdvance = false
        try {
            edit.performClick()
            ui.mainClock.advanceTimeBy(64)
            val revealing = editor.fetchSemanticsNode().boundsInRoot.width
            ui.mainClock.advanceTimeBy(1000)
            val expanded = editor.fetchSemanticsNode().boundsInRoot.width
            assertTrue("An equal-height row still needs a horizontal reveal ($revealing / $expanded)",
                revealing > 0f && revealing < expanded)
            assertEquals(before.id, shell.fetchSemanticsNode().id)
            assertEquals(bottom, shell.fetchSemanticsNode().boundsInRoot.bottom, 1f)
        } finally { ui.mainClock.autoAdvance = true }
        ui.onAllNodes(hasTestTag("push-reason") and isEnabled()).assertCountEquals(1)
        ui.onNodeWithTag("push-reason").performClick().performTextInput("不应保存")
        fun checkClearances() {
            val bar = shell.fetchSemanticsNode().boundsInRoot
            assertTrue(ui.onNodeWithTag("add-task").fetchSemanticsNode().boundsInRoot.bottom <= bar.top)
            assertTrue(bar.bottom <= ui.onNodeWithTag("assistant-input").fetchSemanticsNode().boundsInRoot.top)
        }
        checkClearances()
        ui.onNodeWithText("取消").performClick()
        await(hasText("补充原因") and isEnabled()).assertIsDisplayed()
        ui.onNodeWithTag("push-reason").assertDoesNotExist()
        assertEquals(before.id, shell.fetchSemanticsNode().id)
        checkClearances()
        assertEquals(pushed, runBlocking { store.state() }.frames.single())
    }

    @Test fun reducedMotionPushShowsTheWholeReasonFieldWithoutWaitingForAReveal() {
        runBlocking { store.apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(
            Appearance(start = Destination.WORKSPACE, reduceMotion = true))), PlannerCommand.TaskAdd(TaskInput("立即压栈"))) }
        show()
        ui.onNodeWithTag("queue-push").performClick()
        val pushed = awaitQueue().frames.single()
        val edit = await(hasText("补充原因") and isEnabled())
        ui.mainClock.autoAdvance = false
        try {
            edit.performClick()
            ui.mainClock.advanceTimeByFrame()
            ui.mainClock.advanceTimeByFrame()
            val editor = ui.onNodeWithTag("push-reason-editor")
            val immediate = editor.fetchSemanticsNode().boundsInRoot
            assertTrue(immediate.width > 0f)
            ui.mainClock.advanceTimeBy(1000)
            assertEquals(immediate, editor.fetchSemanticsNode().boundsInRoot)
        } finally { ui.mainClock.autoAdvance = true }
        ui.onNodeWithTag("push-reason").performTextInput("减少动效")
        ui.onNodeWithText("保存").performClick()
        await(hasText("已压栈")).assertIsDisplayed()
        assertEquals(pushed.copy(reason = "减少动效"), runBlocking { store.state() }.frames.single())
        ui.onNodeWithText("补充原因").assertDoesNotExist()
    }

    @Test fun editTaskInFrameThenRestoreKeepsTheEditAndTheOriginalRecord() {
        val frame = runBlocking {
            store.apply(PlannerCommand.FrameCreate("复习", listOf(TaskInput("旧标题", "旧备注", 35))))
            store.state().frames.single()
        }
        val id = frame.items.single().taskId
        val edit = hasTestTag("frame-edit:$id")
        show()
        await(hasText("栈") and hasClickAction()).performClick()
        await(hasTestTag("frame:${frame.id}")).performClick()
        scrollTo(edit)
        ui.onNode(edit).performClick()
        await(hasTestTag("title:0")).performTextReplacement("新标题")
        ui.onNodeWithTag("notes:0").performClick()
        await(hasTestTag("note-source")).performTextReplacement("新备注")
        ui.onNodeWithTag("note-save").performClick()
        await(hasTestTag("title:0")).assertTextContains("新标题")
        assertEquals("旧备注", runBlocking { store.state() }.frames.single().items.single().current!!.notes)
        await(hasText("保存修改") and isEnabled()).performClick()
        await(hasText("帧内任务"))
        scrollTo(hasText("新备注"))
        ui.onNodeWithText("新备注").assertIsDisplayed()
        val item = runBlocking { store.state() }.frames.single().items.single()
        assertEquals("旧标题", item.title)
        assertEquals("旧备注", item.notes)
        assertEquals(QueueTask(id, "新标题", "新备注", 35), item.current)
        val direct = hasTestTag("task-note:$id")
        scrollTo(direct)
        ui.onNode(direct).performClick()
        await(hasTestTag("note-source")).performTextReplacement("**帧内修改**")
        ui.onNodeWithTag("note-save").performClick()
        await(hasText("帧内任务"))
        val edited = runBlocking { store.state() }.frames.single().items.single()
        assertEquals(item.copy(current = item.current!!.copy(notes = "**帧内修改**")), edited)
        val restore = hasText("全部恢复") and hasClickAction()
        scrollTo(restore)
        ui.onNode(restore).performClick()
        val restored = awaitQueue("新标题")
        assertEquals(edited.current, restored.queue.single())
        assertEquals(edited.copy(current = null), restored.frames.single().items.single())
        scrollTo(hasText("旧标题"))
        ui.onNode(edit).assertDoesNotExist()
        ui.onNode(direct).assertDoesNotExist()
    }

    @Test fun missingPrivateDraftDisablesSaveInsteadOfPresentingAnEmptyReplacement() {
        val before = runBlocking {
            store.apply(PlannerCommand.TaskAdd(TaskInput("原任务", "**保持原样**")))
            store.state()
        }
        val key = NoteEditorKey(java.util.UUID.randomUUID().toString(), before.queue.single().id, revision = before.revision)
        ui.setContent {
            StackLifeTheme(Appearance()) {
                NoteEditorScreen(key, viewModel { WorkspaceViewModel(store.api) }) { error("Unexpected navigation") }
            }
        }
        await(hasText("无法在此设备上读取或保存这篇笔记草稿。")).assertIsDisplayed()
        ui.onNodeWithTag("note-source").assertDoesNotExist()
        ui.onNodeWithTag("note-save").assertIsNotEnabled()
        assertEquals(before, runBlocking { store.state() })
    }

    @Test fun creatingTaskKeepsAcceptedNoteWhileNestedChangesAreRestoredThenDiscarded() {
        val scenario = ui.activityRule.scenario
        fun attach() = scenario.onActivity { activity ->
            activity.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        }
        fun back() = scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        attach()
        await(hasTestTag("add-task")).performClick()
        await(hasTestTag("title:0")).performTextInput("本机笔记测试")
        ui.onNodeWithTag("notes:0").performClick()
        val accepted = "## 标题\n\n**保留内容** 📝\n\n" + "已接受的本机长笔记。".repeat(12_000)
        await(hasTestTag("note-source")).performTextReplacement(accepted)
        ui.onNodeWithTag("note-save").performClick()
        await(hasTestTag("notes:0") and isEnabled()).performClick()
        val working = "未接受的子页修改\n\n" + "单独保留在草稿文件。".repeat(12_000)
        await(hasTestTag("note-source")).performTextReplacement(working)
        scenario.onActivity { activity ->
            val bundle = Bundle()
            InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(activity, bundle)
            val parcel = Parcel.obtain()
            try {
                bundle.writeToParcel(parcel, 0)
                assertTrue("Neither long source belongs in Activity state (${parcel.dataSize()} bytes)",
                    parcel.dataSize() < minOf(accepted.length, working.length))
            } finally { parcel.recycle() }
        }
        scenario.recreate()
        attach()
        await(hasTestTag("note-source")).assertTextContains("未接受的子页修改", substring = true)
        back()
        await(hasText("继续编辑")).performClick()
        ui.onNodeWithTag("note-source").assertTextContains("未接受的子页修改", substring = true)
        back()
        await(hasTestTag("note-discard")).performClick()
        await(hasTestTag("title:0")).assertTextContains("本机笔记测试")
        assertTrue(runBlocking { store.state() }.queue.isEmpty())
        await(hasText("添加到队列") and isEnabled()).performClick()
        assertEquals(accepted, awaitQueue("本机笔记测试").queue.single().notes)

        await(hasTestTag("add-task")).performClick()
        await(hasTestTag("notes:0")).performClick()
        await(hasTestTag("note-source")).performTextReplacement("不要创建")
        ui.onNodeWithTag("note-save").performClick()
        await(hasTestTag("title:0"))
        back()
        await(hasTestTag("add-task"))
        assertEquals(listOf("本机笔记测试"), runBlocking { store.state().queue.map { it.title } })
    }

    @Test fun directNoteRejectsAnOutdatedRevisionWithoutLosingTheEditableText() {
        val task = runBlocking {
            store.apply(PlannerCommand.TaskAdd(TaskInput("打开时的任务", "原文")))
            store.state().queue.single()
        }
        show()
        await(hasTestTag("task:${task.id}")).performClick()
        await(hasTestTag("task-note:${task.id}")).performClick()
        await(hasTestTag("note-source")).performTextReplacement("我的修改")
        runBlocking { store.apply(PlannerCommand.TaskEdit(task.id, notes = "同时保存的笔记")) }
        ui.onNodeWithTag("note-save").performClick()
        await(hasText("日程已变更", substring = true))
        ui.onNodeWithTag("note-source").assertIsEnabled().assertTextContains("我的修改")
        assertEquals(task.copy(notes = "同时保存的笔记"), runBlocking { store.state().queue.single() })
        ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        await(hasTestTag("note-discard")).performClick()
        await(hasTestTag("task-note:${task.id}"))
    }

    @Test fun selectedPushKeepsItsReasonTargetAcrossAnotherPushAndRecreation() {
        runBlocking { for (name in listOf("A", "B", "C")) store.apply(PlannerCommand.TaskAdd(TaskInput(name))) }
        val scenario = ui.activityRule.scenario
        fun attach() = scenario.onActivity { activity ->
            activity.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        }
        attach()
        await(hasTestTag("nav:workspace")).performClick()
        await(hasTestTag("queue-select")).performClick()
        ui.onNodeWithTag("queue-push").assertIsNotEnabled()
        ui.onNode(hasText("C") and isToggleable()).performClick()
        ui.onNode(hasText("A") and isToggleable()).performClick()
        ui.onNodeWithTag("queue-push").performClick()
        val first = awaitQueue("B").frames.single()
        assertEquals(listOf("A", "C"), first.items.map { it.title })
        await(hasText("补充原因")).performClick()
        // Nonmodal: B can be pushed while the original editor is still on screen.
        await(hasText("压栈") and isEnabled()).performClick()
        val second = awaitQueue().frames.single { it.id != first.id }
        await(hasTestTag("push-reason")).performTextInput("接电话")
        scenario.recreate()
        attach()
        await(hasTestTag("push-reason")).assertTextContains("接电话")
        ui.onNodeWithText("保存").performClick()
        await(hasText("补充原因")).performClick() // the second push now offers its own action
        await(hasTestTag("push-reason")).performTextInput("不要保存")
        ui.onNodeWithText("取消").performClick()
        val frames = runBlocking { store.state() }.frames
        assertEquals(listOf(second, first.copy(reason = "接电话")), frames)
        await(hasText("栈") and hasClickAction()).performClick()
        await(hasTestTag("frame:${second.id}")).assertTextContains("暂存的队列").performClick()
        await(hasText("暂存的队列")).assertIsDisplayed()
    }

    @Test fun failedDirectPushShowsErrorAndRetryCreatesJustOneFrame() {
        runBlocking { store.apply(PlannerCommand.TaskAdd(TaskInput("A"))) }
        val before = runBlocking { store.state() }
        val db = store.db.openHelper.writableDatabase
        db.execSQL("CREATE TRIGGER reject_push BEFORE INSERT ON frame_items BEGIN SELECT RAISE(ABORT, 'push failure'); END")
        show()
        ui.onNodeWithTag("queue-push").performClick()
        await(hasText("未能保存更改", substring = true)).assertIsDisplayed()
        ui.onNodeWithTag("push-feedback").assertDoesNotExist()
        assertEquals(before, runBlocking { store.state() })
        db.execSQL("DROP TRIGGER reject_push")
        ui.onNodeWithTag("queue-push").performClick()
        await(hasText("已压栈")).assertIsDisplayed()
        val dismissPush = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and
            (hasText("已压栈") or hasAnyDescendant(hasText("已压栈")))
        await(dismissPush).performSemanticsAction(SemanticsActions.Dismiss)
        ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodesWithTag("push-feedback").fetchSemanticsNodes().isEmpty() }
        val frame = awaitQueue().frames.single()
        assertEquals("", frame.reason)
        assertEquals(listOf(before.queue.single().id), frame.items.map { it.taskId })
    }

    @Test fun recreatingDuringSaveConsumesTheOriginalReceipt() = runBlocking {
        // Same real Activity, ViewModel owner and saved-state call site before and after recreation.
        val scenario = ui.activityRule.scenario
        fun attach() = scenario.onActivity { activity ->
            activity.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        }
        attach()
        await(hasTestTag("nav:workspace")).performClick()
        await(hasContentDescription("新增任务") and hasClickAction()).performClick()
        ui.onNodeWithTag("title:0").performTextInput("只创建一次")
        val add = hasText("添加到队列")
        ui.onNode(add).assertIsDisplayed()

        // Hold the actual Room writer until the new Activity has restored the in-flight form.
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = launch(Dispatchers.IO) {
            store.db.withTransaction { locked.complete(Unit); release.await() }
        }
        try {
            withTimeout(5_000) { locked.await() }
            ui.onNode(add).performClick()
            ui.onNode(add).assertIsNotEnabled()
            scenario.recreate()
            attach()
            await(hasTestTag("title:0")).assertTextContains("只创建一次")
            ui.onNode(add).assertIsDisplayed().assertIsNotEnabled()
        } finally {
            release.complete(Unit)
            writer.join()
        }
        await(hasTestTag("queue")).assertIsDisplayed()
        assertEquals(listOf("只创建一次"), store.state().queue.map { it.title })
        ui.onNodeWithTag("title:0").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun narrowQueueCardWrapsMetadataAndKeepsItsMoveHandleIndependent() {
        val title = "核对阶段安排与日期".repeat(5)
        val note = "核对报名信息后再提交"
        val id = runBlocking {
            store.apply(PlannerCommand.TaskAdd(TaskInput(title, note, 45, ref = "first")),
                exact("@first", java.time.OffsetDateTime.parse("2026-06-12T09:00:00+08:00"), 45),
                PlannerCommand.TaskAdd(TaskInput("第二项")))
            store.state().queue.first().id
        }
        ui.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 760.dp)) then DeviceConfigurationOverride.FontScale(2f)) {
                StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
            }
        }
        await(hasTestTag("nav:workspace")).performClick()
        val card = hasTestTag("task:$id")
        await(card).assertHeightIsAtLeast(48.dp)
        fun field(text: String, substring: Boolean = false) =
            ui.onNode(hasText(text, substring = substring) and hasAnyAncestor(card), useUnmergedTree = true)
        field(title).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(get(layouts))
            assertEquals(2, layouts.single().lineCount)
            assertTrue(layouts.single().isLineEllipsized(1))
        }
        val scheduled = field("已安排", substring = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val estimate = field("预计 45 分钟").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val summary = field(note).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(estimate.bottom <= scheduled.top && scheduled.bottom <= summary.top)
        ui.onNodeWithContentDescription("移动${title}，拖动排序或点按选择位置")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performTouchInput { click() }
        await(hasText("移到队尾")).performClick()
        assertEquals(id, awaitQueue("第二项", title).queue.last().id)
    }

    @Test fun draggingAndMenuMoveTheOriginalTasks() {
        runBlocking { store.apply(PlannerCommand.TaskAdd(TaskInput("A")), PlannerCommand.TaskAdd(TaskInput("B"))) }
        val original = runBlocking { store.state().queue }
        show()
        val handle = ui.onNodeWithContentDescription("移动A，拖动排序或点按选择位置")
        val start = handle.fetchSemanticsNode().boundsInRoot.center
        val target = ui.onNodeWithTag("task:${original.last().id}").fetchSemanticsNode().boundsInRoot.center
        val root = ui.onRoot()
        fun dragPastB() {
            root.performTouchInput { down(start) }
            root.performTouchInput {
                for (i in 1..12) moveTo(Offset(start.x, start.y + (target.y - start.y + 16f) * i / 12), delayMillis = 32)
            }
        }
        dragPastB()
        root.performTouchInput { cancel() }
        assertEquals(original, runBlocking { store.state().queue })
        assertEquals(start, handle.fetchSemanticsNode().boundsInRoot.center)
        dragPastB()
        assertEquals(original, runBlocking { store.state().queue })
        root.performTouchInput { up() }
        assertEquals(original.reversed(), awaitQueue("B", "A").queue)
        ui.onNodeWithContentDescription("移动A，拖动排序或点按选择位置").performTouchInput { click() }
        ui.onNodeWithText("移到队首").performClick()
        assertEquals(original, awaitQueue("A", "B").queue)
    }
}
