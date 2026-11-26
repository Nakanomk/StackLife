package io.github.nakanomk.stacklife.planner

import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

data class GraphSummary(val id: String, val title: String)

@Serializable sealed interface GraphLink {
    @Serializable @SerialName("task") data class Task(val taskId: String) : GraphLink
    @Serializable @SerialName("occurrence") data class Occurrence(val scheduleId: String, val originalDate: Long) : GraphLink {
        fun key() = OccurrenceKey.Schedule(scheduleId, originalDate)
    }
}
internal fun GraphLink.storageKey(): String = Json.encodeToString(this)

@Serializable data class LinkState(val owner: Owner?, val completed: Boolean, val scheduled: Boolean,
    val missing: Boolean = false, val system: Boolean = false)

data class LinkedSource(val link: GraphLink, val title: String, val notes: String, val minutes: Int,
    val owner: Owner?, val taskId: String?, val schedule: ScheduleSummary?, val occurrence: AgendaOccurrence?,
    val frameId: String? = null, val queuePosition: Int? = null, val icon: TaskIcon = TaskIcon.AUTO) {
    val state: LinkState get() = LinkState(owner, occurrence?.completed ?: (owner == Owner.DONE),
        schedule?.enabled == true, system = schedule?.role != null)
}
data class SourceView(val revision: Long, val source: LinkedSource?) : QueryResult
enum class LinkFilter { ALL, QUEUE, FRAME, DONE, DAY }
data class LinkCandidate(val link: GraphLink, val title: String, val state: LinkState,
    val linked: Boolean, val startMillis: Long? = null)
data class LinkablePage(val revision: Long, val total: Int, val items: List<LinkCandidate>) : QueryResult

@Serializable
data class GraphNode(val id: String, val title: String, val notes: String, val minutes: Int,
    val creationOrder: Long, val x: Float, val y: Float, val link: GraphLink? = null,
    val linkState: LinkState? = null, val icon: TaskIcon = TaskIcon.AUTO)
@Serializable
data class GraphEdge(val from: String, val to: String)

data class GraphListItem(val id: String, val title: String, val nodeCount: Int, val edgeCount: Int)
data class GraphList(val graphs: List<GraphListItem>) : QueryResult
data class GraphDetail(val revision: Long, val graph: GraphSummary?,
    val nodes: List<GraphNode>, val edges: List<GraphEdge>) : QueryResult
data class GoalOrder(val revision: Long, val nodes: List<GraphNode>, val error: String? = null) : QueryResult

sealed interface GraphEdit {
    data class Rename(val title: String) : GraphEdit
    data class AddNode(val title: String, val notes: String = "", val minutes: Int = 30,
        val x: Float = 0f, val y: Float = 0f, val ref: String? = null, val icon: TaskIcon = TaskIcon.TASK) : GraphEdit
    data class LinkExisting(val link: GraphLink, val x: Float = 0f, val y: Float = 0f, val ref: String? = null) : GraphEdit
    data class SetLinkedCompletion(val nodeId: String, val completed: Boolean) : GraphEdit
    data class EditNode(val nodeId: String, val title: String? = null, val notes: String? = null, val minutes: Int? = null,
        val icon: TaskIcon? = null) : GraphEdit
    data class MoveNode(val nodeId: String, val x: Float, val y: Float) : GraphEdit
    data class RemoveNode(val nodeId: String) : GraphEdit
    data class AddEdge(val from: String, val to: String) : GraphEdit
    data class RemoveEdge(val from: String, val to: String) : GraphEdit
}
