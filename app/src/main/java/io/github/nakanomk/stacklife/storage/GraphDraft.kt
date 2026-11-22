package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.graphs.GoalDag
import io.github.nakanomk.stacklife.planner.GraphEdge
import io.github.nakanomk.stacklife.planner.GraphEdit
import io.github.nakanomk.stacklife.planner.GraphLink
import io.github.nakanomk.stacklife.planner.LinkedSource
import io.github.nakanomk.stacklife.planner.LinkState
import io.github.nakanomk.stacklife.planner.GraphNode
import io.github.nakanomk.stacklife.planner.GraphSnapshot
import io.github.nakanomk.stacklife.planner.GraphSummary
import io.github.nakanomk.stacklife.planner.PlannerChange
import io.github.nakanomk.stacklife.planner.SchedulePattern
import io.github.nakanomk.stacklife.planner.storageKey

internal class GraphWrites(
    private val id: String, private val remove: Boolean, private val graph: GraphRow?,
    private val nodes: List<GraphNodeRow> = emptyList(), private val deletedNodes: List<String> = emptyList(),
    private val edges: List<GraphEdgeRow> = emptyList(), private val deletedEdges: List<GraphEdgeRow> = emptyList(),
) {
    fun applyTo(dao: GraphDao) {
        if (remove) { dao.deleteGraph(id); return }
        graph?.let(dao::putGraph)
        if (deletedEdges.isNotEmpty()) dao.deleteEdges(deletedEdges)
        if (deletedNodes.size in 1..900) dao.deleteNodes(id, deletedNodes)
        else for (start in deletedNodes.indices step 900) {
            dao.deleteNodes(id, deletedNodes.subList(start, minOf(start + 900, deletedNodes.size)))
        }
        if (nodes.isNotEmpty()) dao.putNodes(nodes)
        if (edges.isNotEmpty()) dao.putEdges(edges)
    }
}

/** One overlay per addressed graph. Renames need no nodes; moves need no edges or DAG. */
internal class GraphDraft(private val dao: GraphDao, val id: String, private val original: GraphSummary?, title: String,
    private val source: (GraphLink) -> LinkedSource?, private val editSource: (GraphLink, GraphEdit.EditNode) -> Unit,
    private val completeSource: (GraphLink, Boolean) -> Unit, private val originalSource: (GraphLink) -> LinkedSource?) {
    var title: String? = graphTitle(title)
        private set
    private val initialNodes by lazy(LazyThreadSafetyMode.NONE) { if (original == null) emptyList() else dao.nodes(id) }
    private val initialEdges by lazy(LazyThreadSafetyMode.NONE) { if (original == null) emptyList() else dao.edges(id) }
    private val nodes by lazy(LazyThreadSafetyMode.NONE) { initialNodes.associateByTo(LinkedHashMap()) { it.id } }
    private val links by lazy(LazyThreadSafetyMode.NONE) { nodes.values.mapNotNullTo(HashSet()) { it.link } }
    private val topology = lazy(LazyThreadSafetyMode.NONE) { GoalDag(nodes, initialEdges) }
    private val before = LinkedHashMap<String, GraphNode?>()
    private val edgeChanges = LinkedHashMap<GraphEdge, Boolean>()
    private var lastOrder: Long? = null

    fun remove() { title = null }

    fun edit(edit: GraphEdit, newNodeId: (String?) -> String, resolveNode: (String) -> String) {
        when (edit) {
            is GraphEdit.Rename -> title = graphTitle(edit.title)
            is GraphEdit.AddNode -> {
                add(newNodeId(edit.ref), nodeTitle(edit.title, edit.minutes), edit.notes, edit.minutes, edit.x, edit.y, null)
            }
            is GraphEdit.LinkExisting -> {
                val selected = requireSource(edit.link)
                validateNewLink(selected)
                if (!links.add(edit.link)) throw InvalidCommand(uiText({ "“${selected.title}” is already linked to this graph." },
                    { "「${selected.title}」已链接到此图。" }))
                add(newNodeId(edit.ref), selected.title, selected.notes, selected.minutes, edit.x, edit.y, edit.link)
            }
            is GraphEdit.SetLinkedCompletion -> {
                val old = node(resolveNode(edit.nodeId))
                val link = old.link ?: throw InvalidCommand(uiText("A template has no source event to complete.", "模板没有可完成的来源事件。"))
                requireSource(link, old.title)
                put(old)
                completeSource(link, edit.completed)
            }
            is GraphEdit.EditNode -> {
                val old = node(resolveNode(edit.nodeId))
                if (edit.title == null && edit.notes == null && edit.minutes == null) throw InvalidCommand(uiText("Select a node field to edit.", "请选择需要修改的节点字段。"))
                if (old.link == null) {
                    val minutes = edit.minutes ?: old.minutes
                    put(old.copy(title = nodeTitle(edit.title ?: old.title, minutes), notes = edit.notes ?: old.notes, minutes = minutes))
                } else {
                    val selected = requireSource(old.link, old.title)
                    if (selected.state.system) throw InvalidCommand(uiText("System routine fields cannot be edited as task fields.", "系统作息不能当作任务修改字段。"))
                    put(old)
                    editSource(old.link, edit)
                }
            }
            is GraphEdit.MoveNode -> {
                val old = node(resolveNode(edit.nodeId))
                checkPosition(edit.x, edit.y)
                put(old.copy(x = edit.x, y = edit.y))
            }
            is GraphEdit.RemoveNode -> {
                val old = node(resolveNode(edit.nodeId))
                val dag = topology.value
                for (edge in dag.incidentEdges(old.id)) recordEdge(edge, added = false)
                dag.removeNode(old.id)
                if (old.id !in before) before[old.id] = old
                old.link?.let { links.remove(it) }
                nodes.remove(old.id)
            }
            is GraphEdit.AddEdge -> editEdge(GraphEdge(resolveNode(edit.from), resolveNode(edit.to)), add = true)
            is GraphEdit.RemoveEdge -> editEdge(GraphEdge(resolveNode(edit.from), resolveNode(edit.to)), add = false)
        }
    }

    fun order(goalId: String): List<GraphNode> = checkedTopology {
        topology.value.order(goalId).map { node -> node.link?.let { node.withSource(requireSource(it, node.title)) } ?: node }
    }

    private fun requireSource(link: GraphLink, label: String? = null): LinkedSource = source(link)
        ?: throw InvalidCommand(uiText({ "The source event for “${label ?: link}” is missing." }, { "「${label ?: link}」的来源事件已移除。" }))

    private fun validateNewLink(selected: LinkedSource) {
        if (selected.link is GraphLink.Task && selected.schedule?.pattern is SchedulePattern.Daily)
            throw InvalidCommand(uiText("Select a specific date of this daily event.", "请选择该每日事件的具体日期。"))
    }

    private fun add(id: String, label: String, notes: String, minutes: Int, x: Float, y: Float, link: GraphLink?) {
        checkPosition(x, y)
        val last = lastOrder ?: (nodes.values.maxOfOrNull { it.creationOrder } ?: -1L)
        if (last == Long.MAX_VALUE) throw InvalidCommand(uiText("The graph has run out of node creation numbers.", "流程图的节点创建序号已用尽。"))
        lastOrder = last + 1
        put(GraphNode(id, label, notes, minutes, last + 1, x, y, link = link))
        if (topology.isInitialized()) topology.value.addNode(id)
    }

    private fun put(node: GraphNode) {
        if (node.id !in before) before[node.id] = nodes[node.id]
        nodes[node.id] = node
    }

    private fun editEdge(edge: GraphEdge, add: Boolean) {
        checkedTopology { if (add) topology.value.addEdge(edge) else topology.value.removeEdge(edge) }
        recordEdge(edge, add)
    }

    private fun recordEdge(edge: GraphEdge, added: Boolean) {
        // Valid adds/removes alternate. Opposite edits cancel instead of producing SQL writes.
        if (edgeChanges.remove(edge) == null) edgeChanges[edge] = added
    }

    private fun node(nodeId: String): GraphNode = nodes[nodeId] ?: throw InvalidCommand(uiText({ "Node “$nodeId” is not in graph “$title”." }, { "节点「$nodeId」不在流程图「$title」中。" }))

    fun finish(changes: MutableList<PlannerChange>): GraphWrites? {
        val next = title
        val oldTitle = original?.title
        if (next == null) {
            if (original == null) return null
            changes.add(PlannerChange(id, GraphSnapshot(original.title, initialNodes.map { it.resolved(originalSource) }, initialEdges), null))
            return GraphWrites(id, remove = true, graph = null)
        }
        val graph = if (oldTitle == next) null else GraphRow(id, next)
        if (graph == null && before.isEmpty() && edgeChanges.isEmpty()) return null
        val writes = ArrayList<GraphNodeRow>()
        val deletes = before.keys.filter { it !in nodes && before[it] != null }
        val finalNodes = nodes.values.map { node ->
            val touched = node.id in before
            val old = before[node.id]
            val live = node.link?.let { link ->
                if (touched && old == null) requireSource(link, node.title).also(::validateNewLink) else source(link)
            }
            val projected = node.project(live)
            if (touched && (old != node || node.title != projected.title || node.notes != projected.notes || node.minutes != projected.minutes)) {
                val taskId = if (live == null && node.link != null && old != null) dao.linkedTaskId(id, node.id) else live?.taskId
                writes.add(GraphNodeRow(id, node.id, projected.title, projected.notes, projected.minutes, node.creationOrder,
                    node.x, node.y, node.link?.storageKey(), taskId))
            }
            projected
        }
        val addedEdges = ArrayList<GraphEdgeRow>()
        val removedEdges = ArrayList<GraphEdgeRow>()
        for ((edge, added) in edgeChanges) {
            if (added) {
                addedEdges.add(GraphEdgeRow(id, edge.from, edge.to))
            } else {
                // Node deletion already cascades to its incident edges.
                if (edge.from in nodes && edge.to in nodes) removedEdges.add(GraphEdgeRow(id, edge.from, edge.to))
            }
        }
        val edges = if (edgeChanges.isEmpty()) initialEdges else buildList {
            for (edge in initialEdges) if (edgeChanges[edge] != false) add(edge)
            for ((edge, added) in edgeChanges) if (added) add(edge)
        }
        val previous = original?.let { GraphSnapshot(it.title, initialNodes.map { n -> n.resolved(originalSource) }, initialEdges) }
        val updated = GraphSnapshot(next, finalNodes, edges)
        if (previous == updated) return null
        changes.add(PlannerChange(id, previous, updated))
        return GraphWrites(id, false, graph, writes, deletes, addedEdges, removedEdges)
    }

    private fun graphTitle(raw: String) = raw.trim().ifEmpty { throw InvalidCommand(uiText("Enter a graph name.", "流程图名称不能为空。")) }
    private fun nodeTitle(raw: String, minutes: Int): String {
        if (minutes < 0) throw InvalidCommand(uiText({ "The duration of node “$raw” cannot be negative." }, { "节点「$raw」的预计时长不能为负。" }))
        return raw.trim().ifEmpty { throw InvalidCommand(uiText("Enter a node title.", "节点标题不能为空。")) }
    }
    private fun checkPosition(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) throw InvalidCommand(uiText({ "Node coordinates must be finite: ($x, $y)." }, { "节点坐标必须是有限数值：($x, $y)。" }))
    }
    private inline fun <T> checkedTopology(action: () -> T): T = try { action() }
        catch (e: IllegalArgumentException) { throw InvalidCommand(requireNotNull(e.message)) }
}

private fun GraphNode.withSource(live: LinkedSource): GraphNode =
    copy(title = live.title, notes = live.notes, minutes = live.minutes, linkState = live.state)

private fun GraphNode.project(live: LinkedSource?): GraphNode = when {
    link == null -> this
    live != null -> withSource(live)
    else -> copy(linkState = LinkState(null, completed = false, scheduled = false, missing = true))
}

internal fun GraphNode.resolved(lookup: (GraphLink) -> LinkedSource?): GraphNode = project(link?.let(lookup))
