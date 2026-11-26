package io.github.nakanomk.stacklife.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kotlinflow.components.KotlinFlow
import io.github.kotlinflow.models.KotlinFlowTheme
import io.github.kotlinflow.models.Edge
import io.github.kotlinflow.types.FitViewOptions
import io.github.nakanomk.stacklife.graphs.canvasNode
import io.github.nakanomk.stacklife.graphs.DependencyEdges
import io.github.nakanomk.stacklife.graphs.graphNodeWidth
import io.github.nakanomk.stacklife.graphs.graphNodeHeight
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.today.timeFraction
import io.github.nakanomk.stacklife.transitions.CardMetadata
import java.time.ZoneId

@Composable internal fun ScheduleChangeDiagram(value: ScheduleSnapshot, other: ScheduleSnapshot?) {
    val zone = remember(value.zone) { ZoneId.of(value.zone) }
    val span = remember(value, other) {
        var first = value.startMillis; var last = value.endMillis
        fun include(v: ScheduleSnapshot) {
            first = minOf(first, v.startMillis); last = maxOf(last, v.endMillis)
            for (o in v.overlaps) { first = minOf(first, o.startMillis); last = maxOf(last, o.endMillis) }
        }
        include(value); other?.let(::include); first to last
    }
    val colors = MaterialTheme.colorScheme
    PreviewTitle(value.title)
    CardMetadata { Text(snapshotTime(value.startMillis, zone)); Text(snapshotTime(value.endMillis, zone)); Text(zone.id) }
    TimeStrip(value.startMillis, value.endMillis, span, colors.primary)
    if (value.startMillis == value.endMillis) Text(uiText("Point in time", "单点事件"))
    for (o in value.overlaps) {
        Text(uiText("Overlaps ${o.title}", "与「${o.title}」重叠"), style = MaterialTheme.typography.bodyMedium)
        CardMetadata { Text(snapshotTime(o.startMillis, zone)); Text(snapshotTime(o.endMillis, zone)) }
        TimeStrip(o.startMillis, o.endMillis, span, colors.tertiary)
    }
}

@Composable private fun TimeStrip(start: Long, end: Long, span: Pair<Long, Long>, color: Color) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val from = timeFraction(span.first, span.second, start).toFloat()
    val to = timeFraction(span.first, span.second, end).toFloat()
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val r = 3.dp.toPx(); val width = (size.width - r * 2).coerceAtLeast(0f)
        fun x(f: Float) = r + width * f
        drawLine(track, Offset(r, center.y), Offset(size.width - r, center.y), 1.dp.toPx())
        if (start == end) drawCircle(color, r, Offset(x(from), center.y))
        else drawLine(color, Offset(x(from), center.y), Offset(x(to), center.y), r * 2, StrokeCap.Round)
    }
}

@Composable internal fun GraphChangeDiagram(value: GraphSnapshot, other: GraphSnapshot?, before: Boolean) {
    val nodes = remember(value.nodes) { value.nodes.associateBy { it.id } }
    val opposite = remember(other) { other?.nodes?.associateBy { it.id }.orEmpty() }
    val otherEdges = remember(other) { other?.edges?.toHashSet().orEmpty() }
    val delta = if (before) uiText("Removed", "移除") else uiText("Added", "新增")
    val colors = MaterialTheme.colorScheme
    val sideColor = if (before) colors.error else colors.primary
    fun nodeColor(n: GraphNode) = when (opposite[n.id]) { null -> sideColor; n -> colors.outline; else -> colors.tertiary }
    fun status(n: GraphNode) = when (opposite[n.id]) { null -> delta; n -> null; else -> uiText("Changed", "修改") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    PreviewTitle(value.title)
    val count = uiText("${nodes.size} nodes, ${value.edges.size} dependencies", "${nodes.size} 个节点，${value.edges.size} 条依赖")
    Text(count, style = MaterialTheme.typography.labelMedium)
    if (value.nodes.isEmpty()) return
    if (expanded) {
        val scale = LocalDensity.current.density
        val projected = remember(value.nodes, scale) { value.nodes.map { it.canvasNode(scale) } }
        val theme = KotlinFlowTheme(canvasBackgroundColor = colors.surfaceContainer, edgeColor = colors.onSurfaceVariant)
        KotlinFlow(projected, emptyList<Edge<Unit>>(), Modifier.fillMaxWidth().height(248.dp).clip(MaterialTheme.shapes.medium),
            nodesDraggable = false, nodesConnectable = false, elementsSelectable = false, fitView = true,
            fitViewOptions = FitViewOptions(padding = 8f * scale, durationMillis = 0),
            theme = theme, overlay = {
                DependencyEdges(projected, value.edges, theme.edgeWidth) { if (it in otherEdges) theme.edgeColor else sideColor }
            }) { n ->
            Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerHighest) {
                Column(Modifier.width(graphNodeWidth).heightIn(min = graphNodeHeight).padding(8.dp)) {
                    Text(n.data.title); status(n.data)?.let { Text(it, color = nodeColor(n.data), style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    } else {
        // KotlinFlow 1.0.1 MiniMap: fit logical node bounds in the available rectangle. Use the
        // union of both snapshots so a moved node does not appear to stand still after re-fitting.
        Spacer(Modifier.fillMaxWidth().height(96.dp).clip(MaterialTheme.shapes.small).background(colors.surfaceContainer)
            .semantics { contentDescription = count }.drawWithCache {
                val all = value.nodes.asSequence() + opposite.values.asSequence()
                val x0 = all.minOf { it.x.toDouble() }; val y0 = all.minOf { it.y.toDouble() }
                val w = all.maxOf { it.x.toDouble() } - x0 + graphNodeWidth.value
                val h = all.maxOf { it.y.toDouble() } - y0 + graphNodeHeight.value
                val scale = minOf((size.width - 8.dp.toPx()).coerceAtLeast(1f) / w, (size.height - 8.dp.toPx()).coerceAtLeast(1f) / h)
                val nw = (graphNodeWidth.value * scale).toFloat(); val nh = (graphNodeHeight.value * scale).toFloat()
                val extent = Size(nw.coerceAtLeast(1f), nh.coerceAtLeast(1f)); val radius = CornerRadius(2.dp.toPx())
                val x = (size.width - w * scale) / 2; val y = (size.height - h * scale) / 2
                val origin = nodes.mapValues { (_, n) -> Offset((x + (n.x.toDouble() - x0) * scale).toFloat(), (y + (n.y.toDouble() - y0) * scale).toFloat()) }
                val nodeColors = nodes.mapValues { nodeColor(it.value) }
                val center = Offset(extent.width / 2, extent.height / 2)
                val stroke = 1.5.dp.toPx()
                onDrawBehind {
                    for (edge in value.edges) {
                        val a = origin.getValue(edge.from) + center; val b = origin.getValue(edge.to) + center
                        val vec = b - a; val distance = vec.getDistance()
                        if (distance == 0f) continue
                        val direction = vec / distance
                        val bound = minOf(if (vec.x == 0f) Float.POSITIVE_INFINITY else center.x / kotlin.math.abs(direction.x),
                            if (vec.y == 0f) Float.POSITIVE_INFINITY else center.y / kotlin.math.abs(direction.y))
                        val tip = b - direction * bound
                        val color = if (edge in otherEdges) colors.outline else sideColor
                        drawLine(color, a, tip, stroke)
                        val wing = Offset(-direction.y, direction.x) * (stroke * 1.5f)
                        val tail = tip - direction * (stroke * 3)
                        drawLine(color, tail + wing, tip, stroke); drawLine(color, tail - wing, tip, stroke)
                    }
                    for ((id, p) in origin) drawRoundRect(nodeColors.getValue(id), p, extent, radius)
                }
            })
    }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) uiText("Collapse diagram", "收起全图") else uiText("Open diagram and ordered list", "展开全图与有序清单")) }
    if (expanded) {
        for ((index, n) in value.nodes.withIndex()) {
            Text("${index + 1}. ${n.title}")
            CardMetadata { status(n)?.let { Text(it) }; Text(uiText("${n.minutes} min", "${n.minutes} 分钟")); Text("(${n.x}, ${n.y})") }
            if (n.notes.isNotEmpty() || !opposite[n.id]?.notes.isNullOrEmpty()) PreviewNote(n.notes)
        }
        for (edge in value.edges) {
            val from = nodes.getValue(edge.from).title; val to = nodes.getValue(edge.to).title
            Text(uiText("$from is required by $to", "「$from」是「$to」的前置"))
            if (edge !in otherEdges) Text(delta, style = MaterialTheme.typography.labelMedium, color = sideColor)
        }
    }
}
