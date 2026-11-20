package io.github.nakanomk.stacklife.graphs

import io.github.nakanomk.stacklife.planner.GraphEdge
import io.github.nakanomk.stacklife.planner.GraphNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GoalOrderTest {
    private fun node(id: String, order: Long) = GraphNode(id, "节点$id", "备注$id", 30, order, 0f, 0f)
    private val nodes = listOf(node("A", 0), node("B", 1), node("C", 2), node("D", 3), node("X", 4))
    private val diamond = listOf(GraphEdge("A", "B"), GraphEdge("A", "C"), GraphEdge("B", "D"), GraphEdge("C", "D"))

    @Test fun diamondIncludesTargetAndSharedPredecessorExactlyOnce() {
        assertEquals(nodes.take(4), goalOrder(nodes, diamond, "D"))
        assertEquals(listOf(nodes.last()), goalOrder(nodes, diamond, "X"))
        assertEquals(listOf(nodes[0], nodes[1]), goalOrder(nodes, diamond, "B"))
    }

    @Test fun readyNodesUseCreationOrderThenIdRegardlessOfInputOrderOrPosition() {
        val vertices = listOf(node("Z", 4), node("B", 3), node("A", 3), node("C", 1))
        val edges = listOf(GraphEdge("B", "Z"), GraphEdge("C", "Z"), GraphEdge("A", "Z"))
        val expected = listOf("C", "A", "B", "Z")
        assertEquals(expected, goalOrder(vertices, edges, "Z").map { it.id })
        val moved = vertices.reversed().mapIndexed { index, n -> n.copy(x = -100f * index, y = 37f * index) }
        assertEquals(expected, goalOrder(moved, edges.reversed(), "Z").map { it.id })
    }

    @Test fun newlyReadyEarlierNodePrecedesAnotherReadyBranch() {
        val vertices = listOf(node("A", 4), node("B", 0), node("C", 8), node("D", 2))
        val edges = listOf(GraphEdge("A", "B"), GraphEdge("C", "D"), GraphEdge("B", "D"))
        assertEquals(listOf("A", "B", "C", "D"), goalOrder(vertices, edges, "D").map { it.id })
    }

    @Test fun cycleIsRejectedAtInsertionWithoutChangingExistingOrder() {
        val dag = GoalDag(nodes.associateBy { it.id }, diamond)
        rejected("节点D", "节点A", "环") { dag.addEdge(GraphEdge("D", "A")) }
        assertEquals(nodes.take(4), dag.order("D"))
        dag.removeEdge(GraphEdge("A", "C"))
        assertEquals(listOf(nodes[2]), dag.order("C"))
        dag.addEdge(GraphEdge("C", "A"))
        assertEquals(listOf(nodes[2], nodes[0], nodes[1], nodes[3]), dag.order("D"))
    }

    @Test fun malformedEdgesAndMissingGoalsHaveSpecificDiagnostics() {
        rejected("节点B", "自身") { goalOrder(nodes, diamond + GraphEdge("B", "B"), "D") }
        rejected("节点A", "节点C", "重复") { goalOrder(nodes, diamond + GraphEdge("A", "C"), "D") }
        rejected("节点A", "other", "当前流程图") { goalOrder(nodes, listOf(GraphEdge("A", "other")), "D") }
        rejected("missing", "目标") { goalOrder(nodes, diamond, "missing") }
        rejected("重复", "ID") { goalOrder(nodes + node("B", 9), diamond, "D") }
    }

    private fun rejected(vararg details: String, action: () -> Unit) {
        try { action(); fail("expected invalid graph") }
        catch (e: IllegalArgumentException) {
            val message = e.message.orEmpty()
            for (detail in details) assertTrue("Expected $detail in $message", detail in message)
        }
    }
}
