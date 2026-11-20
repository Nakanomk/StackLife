package io.github.nakanomk.stacklife.planner

data class GraphSummary(val id: String, val title: String)
data class GraphNode(val id: String, val title: String, val notes: String, val minutes: Int,
    val creationOrder: Long, val x: Float, val y: Float)
data class GraphEdge(val from: String, val to: String)

data class GraphList(val graphs: List<GraphSummary>) : QueryResult
data class GraphDetail(val revision: Long, val graph: GraphSummary?,
    val nodes: List<GraphNode>, val edges: List<GraphEdge>) : QueryResult
data class GoalOrder(val revision: Long, val nodes: List<GraphNode>, val error: String? = null) : QueryResult

sealed interface GraphEdit {
    data class Rename(val title: String) : GraphEdit
    data class AddNode(val title: String, val notes: String = "", val minutes: Int = 30,
        val x: Float = 0f, val y: Float = 0f, val ref: String? = null) : GraphEdit
    data class EditNode(val nodeId: String, val title: String? = null, val notes: String? = null, val minutes: Int? = null) : GraphEdit
    data class MoveNode(val nodeId: String, val x: Float, val y: Float) : GraphEdit
    data class RemoveNode(val nodeId: String) : GraphEdit
    data class AddEdge(val from: String, val to: String) : GraphEdit
    data class RemoveEdge(val from: String, val to: String) : GraphEdit
}
