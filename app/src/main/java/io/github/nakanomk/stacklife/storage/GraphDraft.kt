package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.graphs.GoalDag
import io.github.nakanomk.stacklife.planner.GraphEdge
import io.github.nakanomk.stacklife.planner.GraphEdit
import io.github.nakanomk.stacklife.planner.GraphNode
import io.github.nakanomk.stacklife.planner.GraphSummary
import io.github.nakanomk.stacklife.planner.PlannerChange

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
internal class GraphDraft(private val dao: GraphDao, val id: String, private val original: GraphSummary?, title: String) {
    var title: String? = graphTitle(title)
        private set
    private val initialNodes by lazy(LazyThreadSafetyMode.NONE) { if (original == null) emptyList() else dao.nodes(id) }
    private val initialEdges by lazy(LazyThreadSafetyMode.NONE) { if (original == null) emptyList() else dao.edges(id) }
    private val nodes by lazy(LazyThreadSafetyMode.NONE) { initialNodes.associateByTo(LinkedHashMap()) { it.id } }
    private val topology = lazy(LazyThreadSafetyMode.NONE) { GoalDag(nodes, initialEdges) }
    private val before = LinkedHashMap<String, GraphNode?>()
    private val edgeChanges = LinkedHashMap<GraphEdge, Boolean>()
    private var lastOrder: Long? = null

    fun remove() { title = null }

    fun edit(edit: GraphEdit, newNodeId: (String?) -> String, resolveNode: (String) -> String) {
        when (edit) {
            is GraphEdit.Rename -> title = graphTitle(edit.title)
            is GraphEdit.AddNode -> {
                val label = nodeTitle(edit.title, edit.minutes)
                checkPosition(edit.x, edit.y)
                val last = lastOrder ?: (nodes.values.maxOfOrNull { it.creationOrder } ?: -1L)
                if (last == Long.MAX_VALUE) throw InvalidCommand("流程图的节点创建序号已用尽。")
                lastOrder = last + 1
                val node = GraphNode(newNodeId(edit.ref), label, edit.notes, edit.minutes, last + 1, edit.x, edit.y)
                put(node)
                if (topology.isInitialized()) topology.value.addNode(node.id)
            }
            is GraphEdit.EditNode -> {
                val old = node(resolveNode(edit.nodeId))
                put(old.copy(title = nodeTitle(edit.title, edit.minutes), notes = edit.notes, minutes = edit.minutes))
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
                nodes.remove(old.id)
            }
            is GraphEdit.AddEdge -> editEdge(GraphEdge(resolveNode(edit.from), resolveNode(edit.to)), add = true)
            is GraphEdit.RemoveEdge -> editEdge(GraphEdge(resolveNode(edit.from), resolveNode(edit.to)), add = false)
        }
    }

    fun order(goalId: String): List<GraphNode> = checkedTopology { topology.value.order(goalId) }

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

    private fun node(nodeId: String): GraphNode = nodes[nodeId] ?: throw InvalidCommand("节点「$nodeId」不在流程图「$title」中。")
    private fun oldNode(nodeId: String): GraphNode? = if (nodeId in before) before[nodeId] else nodes[nodeId]

    fun finish(changes: MutableList<PlannerChange>): GraphWrites? {
        val next = title
        val oldTitle = original?.title
        if (oldTitle != next) changes.add(PlannerChange(id, "流程图名称", oldTitle, next))
        if (next == null) {
            if (original == null) return null
            for (node in initialNodes) nodeChanges(changes, node, null)
            for (edge in initialEdges) changes.add(PlannerChange(id, "移除依赖", oldLabel(edge), null))
            return GraphWrites(id, remove = true, graph = null)
        }
        val graph = if (oldTitle == next) null else GraphRow(id, next)
        if (graph == null && before.isEmpty() && edgeChanges.isEmpty()) return null
        val writes = ArrayList<GraphNodeRow>()
        val deletes = ArrayList<String>()
        for ((key, old) in before) {
            val node = nodes[key]
            if (node == old) continue
            nodeChanges(changes, old, node)
            if (node == null) deletes.add(key)
            else writes.add(GraphNodeRow(id, key, node.title, node.notes, node.minutes, node.creationOrder, node.x, node.y))
        }
        val addedEdges = ArrayList<GraphEdgeRow>()
        val removedEdges = ArrayList<GraphEdgeRow>()
        for ((edge, added) in edgeChanges) {
            if (added) {
                addedEdges.add(GraphEdgeRow(id, edge.from, edge.to))
                changes.add(PlannerChange(id, "添加依赖", null, "${node(edge.from).title} → ${node(edge.to).title}"))
            } else {
                // Node deletion already cascades to its incident edges.
                if (edge.from in nodes && edge.to in nodes) removedEdges.add(GraphEdgeRow(id, edge.from, edge.to))
                changes.add(PlannerChange(id, "移除依赖", oldLabel(edge), null))
            }
        }
        if (graph == null && writes.isEmpty() && deletes.isEmpty() && edgeChanges.isEmpty()) return null
        return GraphWrites(id, false, graph, writes, deletes, addedEdges, removedEdges)
    }

    private fun oldLabel(edge: GraphEdge) = "${oldNode(edge.from)?.title ?: edge.from} → ${oldNode(edge.to)?.title ?: edge.to}"

    private fun nodeChanges(changes: MutableList<PlannerChange>, old: GraphNode?, next: GraphNode?) {
        val node = next ?: requireNotNull(old)
        fun change(field: String, a: Any?, b: Any?) {
            if (a != b) changes.add(PlannerChange(node.id, "节点「${node.title}」· $field", a?.toString(), b?.toString()))
        }
        change("标题", old?.title, next?.title)
        change("备注", old?.notes, next?.notes)
        change("预计分钟", old?.minutes, next?.minutes)
        if (old?.x != next?.x || old?.y != next?.y) change("画布位置", old?.let { "(${it.x}, ${it.y})" }, next?.let { "(${it.x}, ${it.y})" })
    }

    private fun graphTitle(raw: String) = raw.trim().ifEmpty { throw InvalidCommand("流程图名称不能为空。") }
    private fun nodeTitle(raw: String, minutes: Int): String {
        if (minutes <= 0) throw InvalidCommand("节点「$raw」的预计时长必须大于零。")
        return raw.trim().ifEmpty { throw InvalidCommand("节点标题不能为空。") }
    }
    private fun checkPosition(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) throw InvalidCommand("节点坐标必须是有限数值：($x, $y)。")
    }
    private inline fun <T> checkedTopology(action: () -> T): T = try { action() }
        catch (e: IllegalArgumentException) { throw InvalidCommand(requireNotNull(e.message)) }
}
