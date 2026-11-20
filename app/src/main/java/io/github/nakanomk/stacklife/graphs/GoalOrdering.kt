package io.github.nakanomk.stacklife.graphs

import io.github.nakanomk.stacklife.planner.GraphEdge
import io.github.nakanomk.stacklife.planner.GraphNode
import org.jgrapht.graph.AsSubgraph
import org.jgrapht.graph.DirectedAcyclicGraph
import org.jgrapht.graph.GraphCycleProhibitedException
import org.jgrapht.traverse.TopologicalOrderIterator

internal fun goalOrder(nodes: List<GraphNode>, edges: List<GraphEdge>, goalId: String): List<GraphNode> {
    val byId = buildMap(nodes.size) {
        for (node in nodes) {
            require(node.id.isNotBlank()) { "节点「${node.title}」的 ID 不能为空。" }
            require(put(node.id, node) == null) { "节点「${node.title}」的 ID「${node.id}」重复。" }
        }
    }
    return GoalDag(byId, edges).order(goalId)
}

/** IDs, not editable node fields, are vertices. Query and edit paths use the same edge rules. */
internal class GoalDag(private val nodes: Map<String, GraphNode>, edges: Collection<GraphEdge>) {
    private val graph = DirectedAcyclicGraph<String, GraphEdge>(null, null, false)

    init {
        for (id in nodes.keys) graph.addVertex(id)
        for (edge in edges) addEdge(edge)
    }

    fun addNode(id: String) { graph.addVertex(id) }
    fun removeNode(id: String) { graph.removeVertex(id) }
    fun incidentEdges(id: String): Set<GraphEdge> = graph.edgesOf(id)

    fun addEdge(edge: GraphEdge) {
        checkEndpoints(edge)
        require(edge.from != edge.to) { "节点「${nodes.getValue(edge.from).title}」不能依赖自身。" }
        val added = try {
            graph.addEdge(edge.from, edge.to, edge)
        } catch (_: GraphCycleProhibitedException) {
            throw IllegalArgumentException("不能添加 ${label(edge)}：这条依赖会成环。")
        }
        require(added) { "依赖 ${label(edge)} 已存在，不能重复添加。" }
    }

    fun removeEdge(edge: GraphEdge) {
        checkEndpoints(edge)
        require(graph.removeEdge(edge)) { "依赖 ${label(edge)} 不存在，无法移除。" }
    }

    private fun label(edge: GraphEdge) = "「${nodes[edge.from]?.title ?: edge.from}」→「${nodes[edge.to]?.title ?: edge.to}」"

    private fun checkEndpoints(edge: GraphEdge) {
        require(edge.from in nodes && edge.to in nodes) { "依赖 ${label(edge)} 的端点不在当前流程图中。" }
    }

    fun order(goalId: String): List<GraphNode> {
        require(goalId.isNotBlank()) { "请选择一个目标节点。" }
        require(goalId in nodes) { "目标节点「$goalId」不在当前流程图中。" }
        val closure = graph.getAncestors(goalId)
        closure.add(goalId)
        val subgraph = AsSubgraph(graph, closure)
        val sorted = TopologicalOrderIterator(subgraph, Comparator<String> { a, b ->
            val byOrder = nodes.getValue(a).creationOrder.compareTo(nodes.getValue(b).creationOrder)
            if (byOrder != 0) byOrder else a.compareTo(b)
        })
        return buildList(closure.size) { while (sorted.hasNext()) add(nodes.getValue(sorted.next())) }
    }
}
