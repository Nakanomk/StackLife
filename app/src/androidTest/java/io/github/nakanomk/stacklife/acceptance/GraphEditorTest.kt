package io.github.nakanomk.stacklife.acceptance

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.LocalPlannerGlassAllowed
import io.github.nakanomk.stacklife.appearance.PlannerPopupHost
import io.github.nakanomk.stacklife.appearance.glassUnavailableReason
import io.github.nakanomk.stacklife.graphs.GoalReview
import io.github.nakanomk.stacklife.graphs.GraphEditorRoute
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
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
class GraphEditorTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    private lateinit var ids: Map<String, String>
    private fun id(alias: String) = ids.getValue(alias)
    private fun graph(): GraphDetail = runBlocking { store.api.observe(PlannerQuery.Graph(id("g"))).first() as GraphDetail }
    private fun expectGraph(stage: String, match: (GraphDetail) -> Boolean): GraphDetail {
        var actual = graph()
        try {
            ui.waitUntil(10_000) { actual = graph(); match(actual) }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("$stage: ${actual}\n${ui.onRoot().printToString()}", timeout)
        }
        return actual
    }
    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(10_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return ui.onNode(matcher)
    }
    private fun tag(value: String) = await(hasTestTag(value))
    private fun canvas(alias: String) = tag("canvas-node:${id(alias)}")
    private fun wire(alias: String) = tag("canvas-link:${id(alias)}")
    private fun attach() = ui.activityRule.scenario.onActivity { activity ->
        activity.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
    }
    private fun seed() {
        runBlocking {
            val ready = store.proposal(TaskAdd(TaskInput("眼前的工作")), GraphCreate("期末交付", ref = "g"), GraphPatch("@g", listOf(
                GraphEdit.AddNode("A", "原备注", 10, 10f, 20f, "a"), GraphEdit.AddNode("B", x = 176f, y = 20f, ref = "b"),
                GraphEdit.AddNode("C", x = 10f, y = 126f, ref = "c"), GraphEdit.AddNode("D", x = 176f, y = 126f, ref = "d"),
                GraphEdit.AddNode("X", x = 10f, y = 234f, ref = "x"), GraphEdit.AddEdge("@b", "@d"), GraphEdit.AddEdge("@c", "@d")) ))
            ids = ready.refs
            assertTrue(store.api.commit(ready.proposalId) is CommitResult.Applied)
        }
    }
    private fun open() {
        seed()
        attach()
        tag("nav:graphs").performClick(); tag("open-graph:${id("g")}").performClick()
        canvas("a").assertIsDisplayed()
    }
    private fun goalTitles(alias: String) = runBlocking {
        (store.api.observe(PlannerQuery.Goal(id("g"), id(alias))).first() as GoalOrder).nodes.map { it.title }
    }
    private fun review(alias: String) {
        canvas(alias).performClick(); tag("graph-goal").performClick(); tag("goal-item:0")
    }
    private fun bringIntoReview(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        tag("goal-order").performScrollToNode(matcher)
        return await(matcher and hasAnyAncestor(hasTestTag("goal-order")))
    }

    @Test fun graphCardCountsAndNestedMenuUseTheSelectedGraphId() {
        seed()
        runBlocking { store.apply(GraphCreate("期末交付")) }
        attach()
        tag("nav:graphs").performClick()
        val card = hasTestTag("open-graph:${id("g")}")
        await(card).assertTextContains("节点：5").assertTextContains("依赖：2")
        await(hasContentDescription("管理") and hasAnyAncestor(card))
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performTouchInput { click() }
        await(hasText("重命名")).performClick()
        tag("graph-title").performTextReplacement("有依赖的图")
        tag("graph-save").performClick()
        await(card and hasText("有依赖的图")).performTouchInput { click() }
        canvas("a").assertIsDisplayed()
    }

    @Test fun addingNodeEditsAnIndependentNoteDraftAndWritesOnlyOnParentSave() {
        open()
        val before = graph()
        val taskState = runBlocking { store.state() }
        tag("graph-add").performClick()
        tag("graph-new-node").performClick()
        tag("node-title").performTextReplacement("取消的节点")
        tag("node-notes").performClick()
        tag("note-source").performTextReplacement("不会落入图")
        tag("note-save").performClick()
        tag("node-title").assertTextContains("取消的节点")
        assertEquals(before, graph())
        await(hasText("取消") and hasAnyAncestor(isDialog())).performClick()
        tag("graph-add").performClick()
        tag("graph-new-node").performClick()
        tag("node-title").performTextReplacement("保留的节点")
        tag("node-notes").performClick()
        tag("note-source").performTextReplacement("草稿中")
        ui.activityRule.scenario.recreate()
        attach()
        tag("note-source").assertTextContains("草稿中")
        val source = "检查 **发布**\n\n- [ ] 离线核对"
        tag("note-source").performTextReplacement(source)
        tag("note-save").performClick()
        tag("node-title").assertTextContains("保留的节点")
        assertEquals(before, graph())
        await(hasTestTag("graph-save") and isEnabled()).performClick()
        val after = expectGraph("Save new node and its note together") { it.nodes.size == before.nodes.size + 1 }
        assertEquals(before.revision + 1, after.revision)
        assertEquals(source, after.nodes.single { it.title == "保留的节点" }.notes)
        assertFalse(after.nodes.any { it.title == "取消的节点" })
        assertEquals(taskState.queue, runBlocking { store.state() }.queue)
    }

    @Test fun drawDiamondMoveAndGenerateFromTheReviewedGoal() {
        open()
        val queue = runBlocking { store.state().queue }
        val start = wire("a").fetchSemanticsNode().boundsInRoot.center
        val end = canvas("b").fetchSemanticsNode().boundsInRoot.center
        ui.onRoot().performTouchInput { swipe(start, end, 420) }
        expectGraph("Drag A → B") { GraphEdge(id("a"), id("b")) in it.edges }
        canvas("a").performClick(); tag("graph-node-actions").performClick()
        await(hasText("移除连线")).performClick()
        tag("unlink-choice:${id("a")}:${id("b")}").performClick()
        tag("graph-save").performClick()
        expectGraph("Remove A → B on canvas") { GraphEdge(id("a"), id("b")) !in it.edges }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("graph-save").fetchSemanticsNodes().isEmpty() }
        wire("a").performTouchInput { click() }; await(hasText("A → 请选择后继"))
        canvas("b").performTouchInput { click() }
        expectGraph("Reconnect A → B by tapping") { GraphEdge(id("a"), id("b")) in it.edges }

        tag("graph-toggle-view").performClick()
        tag("list-node:${id("a")}").performTouchInput { click() }
        tag("graph-node-actions").performClick(); await(hasText("从此节点连线")).performClick()
        await(hasText("A → 请选择后继"))
        tag("graph-list").performScrollToNode(hasTestTag("list-node:${id("c")}"))
        tag("list-node:${id("c")}").performTouchInput { click() }
        val diamond = expectGraph("Select A → C from list") { it.edges.size == 4 }
        tag("graph-list").performScrollToNode(hasText("先做「A」，再做「C」"))
        await(hasText("先做「A」，再做「C」")).assertIsDisplayed()
        tag("graph-toggle-view").performClick()
        val expected = listOf("A", "B", "C", "D")
        assertEquals(expected, goalTitles("d"))

        val original = diamond.nodes.single { it.id == id("c") }
        val center = canvas("c").fetchSemanticsNode().boundsInRoot.center
        val delta = Offset(34f, 60f)
        ui.onRoot().performTouchInput { down(center); for (i in 1..6) moveTo(center + delta * (i / 6f), 32) }
        assertEquals(diamond, graph())
        ui.onRoot().performTouchInput { up() }
        val moved = expectGraph("Release C") { it.revision > diamond.revision }
        val c = moved.nodes.single { it.id == original.id }
        assertTrue(c.x > original.x && c.y > original.y)
        assertEquals(expected, goalTitles("d"))

        wire("d").performClick(); canvas("a").performClick()
        await(hasText("环", substring = true))
        assertEquals(moved, graph()); assertTrue(runBlocking { store.state().frames.isEmpty() })
        review("d")
        for ((i, title) in expected.withIndex()) bringIntoReview(hasText("${i + 1}. $title")).assertIsDisplayed()
        ui.onNodeWithText("5. X").assertDoesNotExist()
        bringIntoReview(hasTestTag("graph-frame-reason")).performTextReplacement("一次交付")
        val generate = bringIntoReview(hasTestTag("graph-generate"))
        await(hasTestTag("graph-generate") and isEnabled())
        generate.performClick()
        await(hasText("栈帧详情"))
        await(hasText("一次交付"))
        val state = runBlocking { store.state() }
        assertEquals(queue, state.queue)
        assertEquals(expected, state.frames.single().items.map { it.title })
        assertEquals("原备注", state.frames.single().items.first().notes)
        assertEquals(FrameSource.GRAPH, state.frames.single().source)
        runBlocking { store.apply(GraphPatch(id("g"), listOf(GraphEdit.RemoveNode(id("a"))))) }
        assertEquals(state.frames, runBlocking { store.state().frames })
    }

    private fun goalBack() = await(hasContentDescription("返回") and hasAnyAncestor(isDialog())).assertIsDisplayed()

    @Test fun compactGoalControlsKeepTheirTouchTargetsAndDismissWithoutWriting() {
        open()
        val before = runBlocking { store.state() }
        val launcher = tag("graph-goal").assertHeightIsAtLeast(48.dp)
        val first = ui.onNodeWithTag("graph-goal-pill", useUnmergedTree = true).assertHeightIsEqualTo(40.dp).getUnclippedBoundsInRoot()
        tag("graph-add").assertHeightIsAtLeast(48.dp)
        val next = ui.onNodeWithTag("graph-add-pill", useUnmergedTree = true).assertHeightIsEqualTo(40.dp).getUnclippedBoundsInRoot()
        assertEquals(first.top, next.top)
        assertTrue("Independent actions need visible space between the lenses", next.left - first.right >= 8.dp)
        launcher.performClick()
        val handle = tag("planner-sheet-handle").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        ui.exerciseSheetAnchors("goal")
        ui.onNodeWithTag("planner-sheet-grip", useUnmergedTree = true).assertWidthIsEqualTo(36.dp).assertHeightIsEqualTo(5.dp)
        val x = hasTestTag("goal-choice:${id("x")}")
        bringIntoReview(x).assertIsNotSelected().assertHeightIsAtLeast(48.dp).performClick()
        tag("goal-change").performClick()
        bringIntoReview(x).assertIsSelected()
        ui.onNodeWithTag("goal-check:${id("x")}", useUnmergedTree = true).assertWidthIsEqualTo(20.dp).assertHeightIsEqualTo(20.dp)
        val other = bringIntoReview(hasTestTag("goal-choice:${id("d")}"))
        other.performTouchInput { down(center) }
        other.assertIsNotSelected()
        other.performTouchInput { cancel() }
        bringIntoReview(x).assertIsSelected()
        other.performClick()
        bringIntoReview(hasText("3. D")).assertIsDisplayed()
        bringIntoReview(hasTestTag("goal-change")).performClick()
        bringIntoReview(hasTestTag("goal-choice:${id("d")}")).assertIsSelected()
        bringIntoReview(x).assertIsNotSelected()
        handle.performSemanticsAction(SemanticsActions.Dismiss)
        bringIntoReview(hasText("3. D")).assertIsDisplayed()
        handle.performSemanticsAction(SemanticsActions.Dismiss)
        fun awaitClosed() = ui.waitUntil(10_000) { ui.onAllNodesWithTag("planner-sheet-handle").fetchSemanticsNodes().isEmpty() }
        awaitClosed()
        launcher.performClick()
        tag("planner-sheet-handle").performTouchInput { swipe(center, center + Offset(0f, height * 5f), durationMillis = 180) }
        awaitClosed()
        assertEquals(before, runBlocking { store.state() })
    }

    @Test fun goalGripAndSelectedRowSampleTheDonorWindow() = checkGoalMaterial(glass = true)
    @Test fun reducedTransparencyKeepsTheSameGoalControlsOpaque() = checkGoalMaterial(glass = false)

    private fun checkGoalMaterial(glass: Boolean) {
        seed()
        val state = ReadState.Loaded(graph())
        val donor = mutableStateOf(Color.Red)
        val appearance = Appearance(theme = ThemeMode.LIGHT, palette = PaletteMode.SEED, glass = true,
            reduceTransparency = !glass, reduceMotion = true)
        var available = false
        ui.setContent {
            StackLifeTheme(appearance) {
                available = glassUnavailableReason(appearance) == null
                CompositionLocalProvider(LocalPlannerGlassAllowed provides true) {
                    PlannerPopupHost {
                        Spacer(Modifier.fillMaxSize().background(donor.value))
                        var goal by remember { mutableStateOf(id("d")) }
                        GoalReview(viewModel { WorkspaceViewModel(store.api) }, id("g"), state, goal, { goal = it }, {}, {}, {})
                    }
                }
            }
        }
        if (glass) ui.runOnIdle { assumeTrue("Requires hardware-backed glass", available) }
        tag("goal-change").performClick()
        val row = bringIntoReview(hasTestTag("goal-choice:${id("d")}")).assertIsSelected()
        val grip = ui.onNodeWithTag("planner-sheet-grip", useUnmergedTree = true)
        fun probe(node: SemanticsNodeInteraction, x: Float): IntOffset {
            val n = node.assertIsDisplayed().fetchSemanticsNode()
            return ui.runOnIdle { IntOffset((n.positionOnScreen.x + n.size.width * x).toInt(),
                (n.positionOnScreen.y + n.size.height / 2f).toInt()) }
        }
        // Capture the composed display (including the dialog window), away from the text and check.
        val probes = listOf(probe(grip, .5f), probe(row, .72f))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun sample(): List<Int> {
            val shot = checkNotNull(automation.takeScreenshot())
            val image = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false).also { shot.recycle() } else shot
            return try { probes.map { image.getPixel(it.x, it.y) } } finally { image.recycle() }
        }
        val red = sample()
        ui.runOnIdle { donor.value = Color.Blue }
        ui.waitForIdle()
        fun blueMinusRed(pixel: Int) = (pixel and 255) - ((pixel ushr 16) and 255)
        if (glass) ui.waitUntil(10_000) {
            sample().zip(red).all { (blue, old) -> blueMinusRed(blue) - blueMinusRed(old) > 6 }
        } else assertEquals("The opaque goal controls transmitted the changed graph window", red, sample())
    }

    @Test fun chooseTargetInlineOrUseSoleSinkAndAppendOneReviewedBatchEvenWithSwap() = runBlocking {
        open()
        val initial = store.state()
        tag("graph-goal").performClick()
        bringIntoReview(hasTestTag("goal-choice:${id("x")}")).performClick()
        tag("goal-item:0")
        bringIntoReview(hasText("1. X")).assertIsDisplayed()
        assertEquals(initial, store.state())
        goalBack().performClick()

        store.apply(SetRestorePolicy(RestorePolicy.SWAP), GraphPatch(id("g"), listOf(
            GraphEdit.RemoveNode(id("x")), GraphEdit.AddEdge(id("a"), id("b")), GraphEdit.AddEdge(id("a"), id("c")))))
        expectGraph("Only D is a sink") { it.nodes.size == 4 && it.edges.size == 4 }
        tag("graph-goal").performClick()
        tag("goal-item:0")
        for ((i, title) in listOf("A", "B", "C", "D").withIndex())
            bringIntoReview(hasText("${i + 1}. $title")).assertIsDisplayed()
        val button = hasTestTag("graph-append")
        bringIntoReview(button).assertIsEnabled()
        val before = store.state()
        val staged = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val edit = launch(Dispatchers.IO) {
            store.db.withTransaction {
                store.apply(GraphPatch(id("g"), listOf(GraphEdit.EditNode(id("a"), "A 新版"))))
                staged.complete(Unit)
                release.await()
            }
        }
        try {
            withTimeout(10_000) { staged.await() }
            ui.onNode(button).performClick()
        } finally {
            release.complete(Unit); edit.join()
        }
        await(hasText("重新预览"))
        bringIntoReview(hasText("重新预览")).performClick()
        assertEquals(before.queue, store.state().queue)
        assertEquals(before.frames, store.state().frames)
        bringIntoReview(hasText("1. A 新版")).assertIsDisplayed()
        val click = bringIntoReview(button and isEnabled()).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        ui.runOnIdle { click(); click() }
        await(hasText("已将 4 个新事件按此顺序加入队尾。"))
        val after = store.state()
        assertEquals(before.revision + 2, after.revision) // one concurrent graph edit + one queue batch
        assertEquals(before.queue, after.queue.take(before.queue.size))
        assertEquals(listOf("A 新版", "B", "C", "D"), after.queue.drop(before.queue.size).map { it.title })
        assertEquals("原备注", after.queue[before.queue.size].notes)
        assertEquals(10, after.queue[before.queue.size].minutes)
        assertEquals(before.frames, after.frames)
        assertEquals(RestorePolicy.SWAP, after.restorePolicy)
    }

    @Test fun arrangeLayersFollowAllEdgesAndKeepExistingFrameAndOrder() = runBlocking {
        open()
        store.apply(GraphPatch(id("g"), listOf(GraphEdit.AddEdge(id("a"), id("b")), GraphEdit.AddEdge(id("a"), id("d")))),
            GraphFrame(id("g"), id("d")))
        val before = graph()
        val state = store.state()
        val expected = goalTitles("d")
        tag("graph-arrange").performClick()
        val result = expectGraph("Arrange all nodes in one commit") { it.revision == before.revision + 1 }
        val positions = result.nodes.associateBy { it.id }
        for (edge in result.edges) assertTrue(positions.getValue(edge.from).x < positions.getValue(edge.to).x)
        assertEquals(result.nodes.size, result.nodes.distinctBy { it.x to it.y }.size)
        assertEquals(expected, goalTitles("d"))
        assertEquals(before.edges, result.edges)
        assertEquals(state.frames, store.state().frames)
        val viewport = tag("graph-canvas").fetchSemanticsNode().layoutInfo.coordinates
        for (node in result.nodes) for (part in listOf("canvas-node", "canvas-link")) {
            val child = tag("$part:${node.id}").fetchSemanticsNode().layoutInfo.coordinates
            ui.runOnIdle {
                val rect = viewport.localBoundingBoxOf(child, clipBounds = false)
                assertTrue("${node.title}/$part $rect outside ${viewport.size}", !rect.isEmpty &&
                    rect.left >= -1 && rect.top >= -1 && rect.right <= viewport.size.width + 1 && rect.bottom <= viewport.size.height + 1)
            }
        }
        tag("graph-arrange").performClick()
        assertEquals(result, graph())
    }

    @Test fun overlappingDropTargetsVisibleNodeAndPendingGenerationSurvivesRecreation() = runBlocking {
        open()
        canvas("a").performTouchInput { click() }
        tag("graph-goal").assertIsEnabled()
        val a = graph().nodes.single { it.id == id("a") }
        store.apply(GraphPatch(id("g"), listOf(GraphEdit.MoveNode(id("b"), a.x, a.y))))
        val target = canvas("a").fetchSemanticsNode().boundsInRoot.center
        ui.waitUntil(10_000) { canvas("b").fetchSemanticsNode().boundsInRoot.center == target }
        val start = wire("c").fetchSemanticsNode().boundsInRoot.center
        ui.onRoot().performTouchInput { swipe(start, target, 420) }
        val dropped = expectGraph("Drop C onto selected A in front of B") { GraphEdge(id("c"), id("a")) in it.edges }
        assertFalse(GraphEdge(id("c"), id("b")) in dropped.edges)

        review("a")
        bringIntoReview(hasText("1. C")).assertIsDisplayed()
        bringIntoReview(hasText("2. A")).assertIsDisplayed()
        val confirm = hasTestTag("graph-generate")
        bringIntoReview(confirm); await(confirm and isEnabled())
        val before = store.state()
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = launch(Dispatchers.IO) { store.db.withTransaction { locked.complete(Unit); release.await() } }
        try {
            withTimeout(10_000) { locked.await() }
            ui.onNode(confirm).performClick().assertIsNotEnabled()
            ui.activityRule.scenario.recreate()
            attach()
            goalBack().assertIsNotEnabled()
        } finally {
            release.complete(Unit)
            writer.join()
        }
        await(hasText("栈帧详情"))
        await(hasText("为目标〈A〉生成"))
        val after = store.state()
        assertEquals(before.revision + 1, after.revision)
        assertEquals(before.queue, after.queue)
        assertEquals(listOf("C", "A"), after.frames.single().items.map { it.title })
        ui.activityRule.scenario.recreate()
        attach()
        await(hasText("栈帧详情"))
        await(hasText("为目标〈A〉生成"))
        assertEquals(after, store.state())
    }

    @Test fun cancelAndStaleDragRestorePositionAndChangedReviewRequiresAnotherLook() {
        open()
        val before = graph()
        val center = canvas("c").fetchSemanticsNode().boundsInRoot.center
        val root = ui.onRoot()
        fun drag() { root.performTouchInput {
            down(center); for (i in 1..6) moveTo(center + Offset(5f * i, 10f * i), 32)
        } }
        drag(); root.performTouchInput { cancel() }; ui.waitForIdle()
        assertEquals(before, graph())
        assertEquals(center, canvas("c").fetchSemanticsNode().boundsInRoot.center)
        drag()
        runBlocking { store.apply(TaskAdd(TaskInput("新工作"))) }
        root.performTouchInput { up() }; ui.waitForIdle()
        assertEquals(before.nodes, graph().nodes)
        assertEquals(center, canvas("c").fetchSemanticsNode().boundsInRoot.center)

        review("x")
        bringIntoReview(hasText("1. X")).assertIsDisplayed()
        bringIntoReview(hasTestTag("graph-generate")); await(hasTestTag("graph-generate") and isEnabled())
        goalBack().performClick()
        canvas("x").assertIsDisplayed(); assertTrue(runBlocking { store.state().frames.isEmpty() })
        review("x")
        bringIntoReview(hasTestTag("graph-generate")); await(hasTestTag("graph-generate") and isEnabled())
        runBlocking { store.apply(GraphPatch(id("g"), listOf(GraphEdit.EditNode(id("x"), "新目标", "", 45)))) }
        await(hasText("重新预览")).performClick()
        bringIntoReview(hasText("1. 新目标")).assertIsDisplayed()
        bringIntoReview(hasTestTag("graph-generate")); await(hasTestTag("graph-generate") and isEnabled()).performClick()
        await(hasText("栈帧详情"))
        await(hasText("为目标〈新目标〉生成"))
        assertEquals(listOf("新目标"), runBlocking { store.state().frames.single().items.map { it.title } })
    }

    private fun mountEditor(api: PlannerApi = store.api, onSource: (GraphLink) -> Unit = { error("Unexpected source $it") },
        onFrame: (String) -> Unit = { error("Unexpected frame $it") }) {
        ui.setContent {
            StackLifeTheme(Appearance(reduceMotion = true)) {
                PlannerPopupHost {
                    GraphEditorRoute(viewModel { WorkspaceViewModel(api) }, id("g"), {}, onSource, onFrame)
                }
            }
        }
    }

    private fun captureGraph(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureGraph") != "yes") return
        ui.waitForIdle()
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.waitForIdle(450, 5000)
        val bitmap = checkNotNull(inst.uiAutomation.takeScreenshot())
        try { java.io.File(inst.targetContext.getExternalFilesDir(null), "u6-$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }

    @Test fun multiSelectPreservesIdentitiesAcrossPagesFiltersAndOccurrenceDates() = runBlocking {
        seed()
        store.apply(*Array(51) { TaskAdd(TaskInput("分页同名")) })
        val dates = LocalDate.now()
        store.apply(TaskAdd(TaskInput("分页同名", ref = "framed")), FramePush("归档", listOf("@framed")),
            TaskAdd(TaskInput("每日练习", ref = "daily")), SetSchedule("@daily", SchedulePattern.Daily(listOf(
                DailySegment(dates.minusDays(1).toEpochDay(), 12 * 60, ZoneId.systemDefault().id, 0)))))
        val graphId = id("g")
        val framed = GraphLink.Task(store.state().frames.single().items.single().taskId)
        val daily = store.state().queue.single { it.title == "每日练习" }.id
        val today = GraphLink.Occurrence(daily, dates.toEpochDay())
        val tomorrow = today.copy(originalDate = today.originalDate + 1)
        suspend fun page(offset: Int) = store.api.observe(PlannerQuery.LinkableEvents(graphId, "分页同名",
            LinkFilter.QUEUE, dates, offset)).first() as LinkablePage
        val first = page(0)
        assertEquals(51, first.total)
        assertEquals(50, first.items.size)
        val duplicate = first.items[0].link
        val left = first.items[1].link
        val right = page(50).items.single().link
        store.apply(GraphPatch(graphId, listOf(GraphEdit.LinkExisting(duplicate))))
        val before = graph()
        mountEditor()

        fun picker() {
            tag("graph-add").performClick(); tag("graph-existing").performClick()
            val handle = tag("planner-sheet-handle")
            if (handle.fetchSemanticsNode().config.contains(SemanticsActions.Expand)) handle.performSemanticsAction(SemanticsActions.Expand)
        }
        fun top() { tag("graph-candidates").performScrollToIndex(0) }
        fun filter(choice: LinkFilter) {
            top(); tag("graph-filter").performClick(); tag("graph-filter:$choice").performClick()
        }
        fun search(value: String) { top(); tag("graph-link-search").performTextReplacement(value) }
        fun option(link: GraphLink): SemanticsNodeInteraction {
            val item = "graph-option:${link.storageKey()}"
            val list = tag("graph-candidates")
            val key = link.storageKey()
            ui.waitUntil(10_000) {
                list.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.IndexForKey](key) >= 0
            }
            list.performScrollToKey(key)
            return tag(item).performScrollTo()
        }
        picker()
        search("分页同名"); filter(LinkFilter.QUEUE)
        option(duplicate).assertIsOn().assertIsNotEnabled()
        option(left).assertIsOff().performClick()
        top(); tag("graph-page-next").performClick()
        option(right).performClick()
        top(); tag("graph-page-prev").performClick()
        option(left).assertIsOn()
        filter(LinkFilter.FRAME); option(framed).performClick()
        search("无此事件"); await(hasText("没有匹配的事件，请尝试其他搜索词或来源。"))
        tag("graph-link-add").assertTextContains("添加 3 项")
        filter(LinkFilter.DAY); search("每日练习")
        option(today).performClick()
        top(); ui.chooseDate("graph-link-date", dates.plusDays(1))
        option(tomorrow).assertIsOff().performClick()
        captureGraph("picker")
        tag("graph-link-add").assertTextContains("添加 5 项").performClick()
        val linked = expectGraph("Add all selected identities in one patch") { it.nodes.size == before.nodes.size + 5 }
        assertEquals(before.revision + 1, linked.revision)
        val added = linked.nodes.filter { node -> before.nodes.none { it.id == node.id } }
        assertEquals(setOf(left, right, framed, today, tomorrow), added.map { it.link }.toSet())
        assertEquals(5, added.distinctBy { it.x to it.y }.size)
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("planner-sheet-handle").fetchSemanticsNodes().isEmpty() }
        picker()
        search("每日练习"); option(today).assertIsNotEnabled()
        top(); ui.chooseDate("graph-link-date", dates.plusDays(2))
        option(today.copy(originalDate = today.originalDate + 2)).performClick()
        tag("graph-link-add").assertIsEnabled()
        tag("planner-sheet-handle").performSemanticsAction(SemanticsActions.Dismiss)
        assertEquals(linked, graph())
    }

    @Test fun linkedGoalOpensOriginalSourceAndOnlyTemplatesGenerateNewQueueItems() = runBlocking {
        seed()
        val source = GraphLink.Task(store.state().queue.single().id)
        val graphId = id("g")
        val patch = store.proposal(GraphPatch(graphId, listOf(GraphEdit.LinkExisting(source, ref = "linked"),
            GraphEdit.AddEdge("@linked", id("a")))))
        assertTrue(store.api.commit(patch.proposalId) is CommitResult.Applied)
        val linked = patch.refs.getValue("linked")
        val opened = mutableListOf<GraphLink>()
        val received = ConcurrentLinkedQueue<List<PlannerCommand>>()
        mountEditor(object : PlannerApi by store.api {
            override suspend fun preview(commands: List<PlannerCommand>): PreviewResult {
                received.add(commands)
                return store.api.preview(commands)
            }
        }, opened::add)
        tag("graph-toggle-view").performClick()
        fun pick(node: String) {
            tag("graph-list").performScrollToNode(hasTestTag("list-node:$node"))
            tag("list-node:$node").performClick()
        }
        pick(linked); tag("graph-node-actions").performClick(); tag("graph-open-source").performClick()
        ui.runOnIdle { assertEquals(listOf(source), opened) }
        tag("graph-goal").performClick()
        bringIntoReview(hasTestTag("graph-append")).assertIsNotEnabled()
        bringIntoReview(hasTestTag("graph-generate")).assertIsEnabled()
        bringIntoReview(hasTestTag("goal-open:$linked")).performClick()
        ui.runOnIdle { assertEquals(listOf(source, source), opened) }

        pick(id("a")); tag("graph-goal").performClick()
        bringIntoReview(hasText("保留在队列原位")).assertIsDisplayed()
        captureGraph("goal")
        val prior = store.state()
        bringIntoReview(hasTestTag("graph-append") and isEnabled()).assertTextContains("将 1 个新事件按此顺序加入队列").performClick()
        await(hasText("已将 1 个新事件按此顺序加入队尾。"))
        assertEquals(listOf(listOf(GraphQueue(graphId, id("a"), prior.revision))), received.toList())
        val after = store.state()
        assertEquals(prior.queue, after.queue.dropLast(1))
        assertEquals("A", after.queue.last().title)
        assertEquals(prior.revision + 1, after.revision)
        goalBack().performClick()

        store.apply(TaskRemove(source.taskId))
        expectGraph("Keep the missing reference") { it.nodes.single { n -> n.id == linked }.linkState?.missing == true }
        pick(id("a")); tag("graph-goal").performClick()
        bringIntoReview(hasText("「眼前的工作」的来源已移除。请先解除此节点的关联。")).assertIsDisplayed()
        bringIntoReview(hasTestTag("graph-append")).assertIsNotEnabled()
        bringIntoReview(hasTestTag("graph-generate")).assertIsNotEnabled()
        assertEquals(1, received.size)
    }

    @Test fun systemOccurrenceAllowsCompletionAndUnlinkButNotEditingOrDeletingTheSource() = runBlocking {
        seed()
        val day = LocalDate.of(2032, 2, 29)
        val zone = ZoneId.systemDefault()
        val link = GraphLink.Occurrence(RoutineKind.WAKE.scheduleId, day.toEpochDay())
        store.apply(SetSchedule(link.scheduleId, SchedulePattern.Daily(listOf(DailySegment(link.originalDate, 7 * 60, null, 0)))))
        val proposal = store.proposal(GraphPatch(id("g"), listOf(GraphEdit.LinkExisting(link, ref = "wake"))))
        assertTrue(store.api.commit(proposal.proposalId) is CommitResult.Applied)
        val nodeId = proposal.refs.getValue("wake")
        val opened = mutableListOf<GraphLink>()
        val frames = ConcurrentLinkedQueue<String>()
        mountEditor(onSource = { opened.add(it) }, onFrame = { frames.add(it) })
        tag("graph-toggle-view").performClick()
        tag("graph-list").performScrollToNode(hasTestTag("list-node:$nodeId"))
        tag("list-node:$nodeId").performClick()
        tag("graph-node-actions").performClick()
        await(hasText("编辑节点")).assertIsNotEnabled()
        tag("graph-open-source").performClick()
        ui.runOnIdle { assertEquals(listOf(link), opened) }
        tag("graph-node-actions").performClick(); tag("graph-source-completion").performClick()
        expectGraph("Complete this leap-day occurrence only") { it.nodes.single { n -> n.id == nodeId }.linkState?.completed == true }
        suspend fun completed(n: Int) = (store.api.observe(PlannerQuery.Occurrence(
            link.copy(originalDate = link.originalDate + n).key(), zone)).first() as OccurrenceView).occurrence!!.completed
        assertTrue(completed(0)); assertFalse(completed(1))
        tag("graph-goal").performClick()
        bringIntoReview(hasTestTag("graph-append")).assertIsNotEnabled()
        bringIntoReview(hasTestTag("graph-generate") and isEnabled()).performClick()
        ui.waitUntil(10_000) { frames.isNotEmpty() }
        val snapshot = store.state().frames.single { it.id == frames.single() }
        assertEquals(0, snapshot.remaining)
        assertEquals(link, snapshot.items.single().link)
        assertFalse(snapshot.items.single().restorable)
        tag("graph-node-actions").performClick(); await(hasText("解除事件关联")).performClick()
        tag("graph-save").performClick()
        expectGraph("Unlink without deleting the schedule") { it.nodes.none { n -> n.id == nodeId } }
        assertTrue(completed(0)); assertFalse(completed(1))
    }
}
