package io.github.nakanomk.stacklife.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.GraphEdit.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphTransactionTest {
    @get:Rule val store = WorkspaceFixture()

    private suspend fun graph(id: String) = store.api.observe(PlannerQuery.Graph(id)).first() as GraphDetail
    private suspend fun goal(g: String, n: String) = store.api.observe(PlannerQuery.Goal(g, n)).first() as GoalOrder
    private suspend fun accept(p: PreviewResult.Ready) = assertTrue(store.api.commit(p.proposalId) is CommitResult.Applied)

    @Test fun diamondSkipsUnrelatedTemplatesAndNeverRewritesFramesOrQueue() = runBlocking {
        store.apply(TaskAdd(TaskInput("眼前的任务")))
        val queue = store.state().queue
        val plan = store.proposal(GraphCreate("展示准备", "g"), GraphPatch("@g", listOf(
            AddNode("A", "原始备注", 15, ref = "a"), AddNode("B", ref = "b"), AddNode("C", ref = "c"),
            AddNode("D", ref = "d"), AddNode("X", ref = "x"), AddEdge("@a", "@c"), AddEdge("@a", "@b"),
            AddEdge("@c", "@d"), AddEdge("@b", "@d"))))
        val ids = plan.refs
        val g = ids.getValue("g"); val a = ids.getValue("a"); val b = ids.getValue("b")
        val c = ids.getValue("c"); val d = ids.getValue("d"); val x = ids.getValue("x")
        assertNull(graph(g).graph)
        accept(plan)
        val order = goal(g, d)
        assertEquals(listOf(a, b, c, d), order.nodes.map { it.id })
        val stale = store.proposal(GraphFrame(g, d, expectedRevision = order.revision))
        store.apply(GraphPatch(g, listOf(MoveNode(c, -400f, 900f))))
        assertEquals(CommitFailure.STALE_PREVIEW, (store.api.commit(stale.proposalId) as CommitResult.Rejected).code)
        assertTrue(store.api.preview(listOf(GraphFrame(g, d, expectedRevision = order.revision))) is PreviewResult.Rejected)
        assertTrue(store.api.preview(listOf(GraphPatch(g, listOf(MoveNode(c, 0f, 0f)), order.revision))) is PreviewResult.Rejected)
        assertTrue(store.state().frames.isEmpty())
        assertEquals(order.nodes.map { it.id }, goal(g, d).nodes.map { it.id })
        val moved = graph(g).nodes.single { it.id == c }
        assertEquals(-400f, moved.x, 0f)
        assertEquals(900f, moved.y, 0f)
        val ready = store.proposal(GraphFrame(g, d, ref = "f"))
        assertTrue(store.state().frames.isEmpty())
        accept(ready)
        assertEquals(CommitFailure.UNKNOWN_PROPOSAL, (store.api.commit(ready.proposalId) as CommitResult.Rejected).code)
        val frame = store.state().frames.single()
        assertEquals(ready.refs.getValue("f"), frame.id)
        assertEquals(FrameSource.GRAPH, frame.source)
        assertEquals("为目标〈D〉生成", frame.reason)
        assertEquals(listOf("A", "B", "C", "D"), frame.items.map { it.title })
        assertEquals(listOf(0, 1, 2, 3), frame.items.map { it.originalPosition })
        assertEquals("原始备注", frame.items.first().notes)
        assertEquals(15, frame.items.first().minutes)
        assertTrue(frame.items.all { it.restorable && it.taskId !in ids.values })
        store.apply(GraphFrame(g, d, "重做展示"), GraphFrame(g, x, "只做孤点"))
        val again = store.state().frames.single { it.reason == "重做展示" }
        assertTrue(again.items.none { item -> frame.items.any { it.taskId == item.taskId } })
        assertEquals(listOf("X"), store.state().frames.single { it.reason == "只做孤点" }.items.map { it.title })
        val saved = store.state().frames
        store.apply(GraphPatch(g, listOf(EditNode(a, "新标题", "新备注", 90), RemoveNode(b), Rename("新图名"))))
        assertEquals(setOf(GraphEdge(a, c), GraphEdge(c, d)), graph(g).edges.toSet())
        assertEquals(listOf("新标题", "C", "D"), goal(g, d).nodes.map { it.title })
        val removal = store.proposal(GraphPatch(g, listOf(RemoveEdge(c, d))))
        assertEquals("流程图「新图名」", removal.changes.single().entityLabel)
        accept(removal)
        assertEquals(listOf(d), goal(g, d).nodes.map { it.id })
        store.apply(GraphRemove(g))
        store.reopen()
        assertNull(graph(g).graph)
        assertNotNull(goal(g, d).error)
        assertEquals(saved, store.state().frames)
        assertEquals(queue, store.state().queue)
    }

    @Test fun oneBatchCanInstantiateRestoreAndScheduleTheSameTaskByAlias() = runBlocking {
        val time = OffsetDateTime.parse("2026-09-07T11:00:00+08:00")
        val p = store.proposal(GraphCreate("路径", "g"), GraphPatch("@g", listOf(
            AddNode("准备", ref = "start"), AddNode("交付", ref = "goal"), AddEdge("@start", "@goal"))),
            GraphFrame("@g", "@goal", ref = "f", taskRefs = mapOf("@start" to "task")),
            FrameRestore("@f", RestoreMode.SELECTED, listOf("@task")), SlotSet("@task", time, 25))
        val r = p.refs
        assertTrue(store.state().queue.isEmpty())
        accept(p)
        store.reopen()
        val state = store.state(); val task = state.queue.single(); val frame = state.frames.single()
        assertEquals(p.baseRevision + 1, state.revision)
        assertEquals(r.getValue("task"), task.id)
        assertEquals(time.toInstant().toEpochMilli(), task.scheduledStartMillis)
        assertEquals(r.getValue("f"), frame.id)
        assertEquals(task.id, frame.items.first().taskId)
        assertEquals(listOf(false, true), frame.items.map { it.restorable })
        assertNull(frame.items.first().scheduledStartMillis)
        assertEquals(listOf(r.getValue("start"), r.getValue("goal")), goal(r.getValue("g"), r.getValue("goal")).nodes.map { it.id })
    }

    @Test fun rejectedEdgesAndStorageFailuresPreserveGraphAndFrameAtomically() = runBlocking {
        val p = store.proposal(GraphCreate("本图", "g"), GraphCreate("另图", "h"), GraphPatch("@h", listOf(AddNode("外部", ref = "x"))),
            GraphPatch("@g", listOf(AddNode("A", ref = "a"), AddNode("B", ref = "b"), AddEdge("@a", "@b"))))
        accept(p)
        val g = p.refs.getValue("g"); val a = p.refs.getValue("a"); val b = p.refs.getValue("b"); val x = p.refs.getValue("x")
        val before = graph(g); val state = store.state()
        val bad = listOf(GraphPatch(g, listOf(AddEdge(b, a))), GraphPatch(g, listOf(AddEdge(a, b))),
            GraphPatch(g, listOf(AddEdge(a, a))), GraphPatch(g, listOf(AddEdge(a, x))), GraphPatch(g, listOf(AddEdge("missing", b))),
            GraphFrame(g, a, " "), GraphFrame(g, a, taskRefs = mapOf(b to "outside")),
            GraphFrame(g, b, taskRefs = mapOf(a to "same", b to "same")), GraphPatch(g, listOf(MoveNode(a, Float.NaN, 0f))),
            GraphPatch(g, listOf(AddNode("N", ref = "n"), AddEdge("@n", "@undeclared"))))
        for (cmd in bad) {
            val result = store.api.preview(listOf(TaskAdd(TaskInput("不可留下")), cmd))
            assertTrue("$cmd returned $result", result is PreviewResult.Rejected)
        }
        assertEquals(before, graph(g)); assertEquals(state, store.state())
        val fail = store.proposal(GraphPatch(g, listOf(AddNode("C", ref = "c"), AddEdge(b, "@c"))), GraphFrame(g, "@c"))
        store.db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_graph_edge BEFORE INSERT ON graph_edges
            BEGIN SELECT RAISE(ABORT, 'injected edge failure'); END""")
        assertEquals(CommitFailure.STORAGE, (store.api.commit(fail.proposalId) as CommitResult.Rejected).code)
        store.reopen()
        assertEquals(before, graph(g)); assertEquals(state, store.state())
    }
}
