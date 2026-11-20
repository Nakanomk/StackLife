package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
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
    private fun open() {
        runBlocking {
            val ready = store.proposal(TaskAdd(TaskInput("眼前的工作")), GraphCreate("期末交付", ref = "g"), GraphPatch("@g", listOf(
                GraphEdit.AddNode("A", "原备注", 10, 10f, 20f, "a"), GraphEdit.AddNode("B", x = 176f, y = 20f, ref = "b"),
                GraphEdit.AddNode("C", x = 10f, y = 126f, ref = "c"), GraphEdit.AddNode("D", x = 176f, y = 126f, ref = "d"),
                GraphEdit.AddNode("X", x = 10f, y = 234f, ref = "x"), GraphEdit.AddEdge("@b", "@d"), GraphEdit.AddEdge("@c", "@d")) ))
            ids = ready.refs
            assertTrue(store.api.commit(ready.proposalId) is CommitResult.Applied)
        }
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

    @Test fun chooseTargetInlineOrUseSoleSinkAndAppendOneReviewedBatchEvenWithSwap() = runBlocking {
        open()
        val initial = store.state()
        tag("graph-goal").performClick()
        bringIntoReview(hasTestTag("goal-choice:${id("x")}")).performClick()
        tag("goal-item:0")
        bringIntoReview(hasText("1. X")).assertIsDisplayed()
        assertEquals(initial, store.state())
        bringIntoReview(hasContentDescription("返回")).performClick()

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
        await(hasText("已按此顺序加入队尾。"))
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
            bringIntoReview(hasContentDescription("返回")).assertIsNotEnabled()
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
        bringIntoReview(hasContentDescription("返回")).performClick()
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
}
