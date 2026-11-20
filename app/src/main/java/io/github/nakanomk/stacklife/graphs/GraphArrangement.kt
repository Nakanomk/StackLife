package io.github.nakanomk.stacklife.graphs

import io.github.nakanomk.stacklife.planner.GraphDetail
import io.github.nakanomk.stacklife.planner.GraphEdit
import kotlin.math.ceil
import kotlin.math.sqrt

/** Layers follow the longest prerequisite path, not the first path that reaches a node. */
internal fun arrangeGraph(graph: GraphDetail, rowStep: Float): List<GraphEdit.MoveNode> {
    val nodes = graph.nodes
    if (nodes.isEmpty()) return emptyList()
    val n = nodes.size
    val index = HashMap<String, Int>(n)
    for (i in nodes.indices) index[nodes[i].id] = i
    val degree = IntArray(n)
    val heads = IntArray(n) { -1 }
    val next = IntArray(graph.edges.size)
    val targets = IntArray(graph.edges.size)
    for ((i, edge) in graph.edges.withIndex()) {
        val a = index.getValue(edge.from); val b = index.getValue(edge.to)
        targets[i] = b; next[i] = heads[a]; heads[a] = i; degree[b]++
    }
    val rank = IntArray(n)
    val work = IntArray(n)
    var front = 0; var tail = 0; var isolates = 0
    for (i in nodes.indices) if (degree[i] == 0) {
        work[tail++] = i
        if (heads[i] == -1) { rank[i] = -1; isolates++ }
    }
    while (front < tail) {
        val a = work[front++]
        var e = heads[a]
        while (e != -1) {
            val b = targets[e]
            rank[b] = maxOf(rank[b], rank[a] + 1)
            if (--degree[b] == 0) work[tail++] = b
            e = next[e]
        }
    }
    val levels = (rank.maxOrNull() ?: -1) + 1
    val count = IntArray(levels)
    for (r in rank) if (r >= 0) count[r]++
    val tallest = count.maxOrNull() ?: 0
    val row = IntArray(levels)
    val columns = maxOf(1, levels, ceil(sqrt(isolates.toDouble())).toInt())
    var isolated = 0
    return buildList {
        for (i in nodes.indices) {
            val r = rank[i]
            val x: Float; val y: Float
            if (r < 0) {
                x = 16f + (isolated % columns) * 210f
                y = 16f + (tallest + isolated / columns) * rowStep
                isolated++
            } else {
                x = 16f + r * 210f
                y = 16f + ((tallest - count[r]) * .5f + row[r]++) * rowStep
            }
            val node = nodes[i]
            if (node.x != x || node.y != y) add(GraphEdit.MoveNode(node.id, x, y))
        }
    }
}
