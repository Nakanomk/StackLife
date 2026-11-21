package io.github.nakanomk.stacklife.graphs

import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import io.github.nakanomk.stacklife.appearance.GlassBacking
import io.github.nakanomk.stacklife.workspace.ActionPill
import io.github.nakanomk.stacklife.workspace.ControlGroup
import io.github.kotlinflow.components.KotlinFlow
import io.github.kotlinflow.models.*
import io.github.kotlinflow.types.BackgroundVariant
import io.github.kotlinflow.utils.KotlinFlowInstance
import io.github.nakanomk.stacklife.planner.*

private data class Moving(val id: String, val revision: Long, val position: Offset)
private data class Wire(val source: String, val revision: Long, val start: Offset, val tip: Offset)

// KotlinFlow keys its edge geometry by list identity/equality. Give it a new immutable view for
// a drag frame without copying the list or projecting all its unchanged nodes again.
private class DragFrame<T>(private val base: List<T>, private val index: Int, private val moved: T) : AbstractList<T>(), RandomAccess {
    override val size get() = base.size
    override fun get(index: Int): T = if (index == this.index) moved else base[index]
}

private fun LayoutCoordinates?.containsDrop(view: LayoutCoordinates, point: Offset): Boolean {
    if (this == null || !isAttached) return false
    val local = localPositionOf(view, point)
    return local.x in 0f..size.width.toFloat() && local.y in 0f..size.height.toFloat()
}

/** Graph coordinates are logical dp. KotlinFlow positions/edges and its viewport use physical px. */
@Composable
internal fun GraphCanvas(detail: GraphDetail, selected: String?, onSelect: (String) -> Unit,
    onLinkFrom: (String) -> Unit, onEdit: (GraphEdit, Long) -> Unit, onArrange: (List<GraphEdit.MoveNode>, Long) -> Boolean,
    modifier: Modifier = Modifier, headerInset: Dp = 0.dp) {
    val scale = LocalDensity.current.density
    val latest by rememberUpdatedState(detail)
    val latestSelection by rememberUpdatedState(selected)
    val edit by rememberUpdatedState(onEdit)
    val colors = MaterialTheme.colorScheme
    val instance = remember { KotlinFlowInstance() }
    val boxes = remember { mutableMapOf<String, LayoutCoordinates>() }
    var canvas by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var moving by remember { mutableStateOf<Moving?>(null) }
    var wire by remember { mutableStateOf<Wire?>(null) }
    // Keep stored graph coordinates independent of the toolbar: only the presentation origin moves.
    val originY = with(LocalDensity.current) { headerInset.toPx() }
    val fixed = remember(detail.nodes, selected, scale, originY) { detail.nodes.map { node ->
        Node(node.id, XYPosition(node.x * scale, node.y * scale + originY), node, selected = node.id == selected)
    } }
    val drag = moving
    val index = remember(fixed, drag?.id) { if (drag == null) -1 else fixed.indexOfFirst { it.id == drag.id } }
    val nodes = remember(fixed, drag, index) {
        if (drag == null || index < 0) fixed
        else DragFrame(fixed, index, fixed[index].copy(position = XYPosition(drag.position.x * scale, drag.position.y * scale + originY)))
    }
    val edges = remember(detail.edges, scale) { detail.edges.map {
        Edge<Unit>(id = "${it.from}:${it.to}", source = it.from, target = it.to,
            markerEnd = EdgeMarker(MarkerType.ARROW_CLOSED, 9f * scale, 9f * scale))
    } }
    val theme = remember(colors, scale) { KotlinFlowTheme(canvasBackgroundColor = colors.surfaceContainerLow,
        edgeColor = colors.onSurfaceVariant, edgeWidth = 2f * scale, nodeSelectedBorderColor = colors.primary,
        gridColor = colors.outlineVariant, gridSpacing = 22f) }
    fun fit() {
        val sizes = boxes.filterValues { it.isAttached }.mapValues { (_, box) ->
            androidx.compose.ui.geometry.Size(box.size.width.toFloat(), box.size.height.toFloat())
        }
        val view = canvas?.takeIf { it.isAttached } ?: return
        val bounds = instance.getNodesBounds(nodes, sizes)
        val margin = 12f * scale
        val left = margin
        val right = view.size.width - margin
        val top = originY + margin
        val bottom = view.size.height - 58f * scale - margin
        if (bottom <= top || right <= left) return
        val zoom = minOf((right - left) / bounds.width.coerceAtLeast(1f), (bottom - top) / bounds.height.coerceAtLeast(1f), 1f)
            .coerceAtLeast(Viewport.MinZoom)
        instance.setViewport(Viewport((left + right) / 2 - bounds.center.x * zoom,
            (top + bottom) / 2 - bounds.center.y * zoom, zoom), false)
    }
    var arranged by remember { mutableStateOf<Pair<Long, List<GraphEdit.MoveNode>>?>(null) }
    LaunchedEffect(detail.revision) {
        val pending = arranged ?: return@LaunchedEffect
        if (detail.revision > pending.first) {
            arranged = null
            val moves = pending.second.iterator()
            var move = moves.next()
            for (node in detail.nodes) if (node.id == move.nodeId) {
                if (node.x != move.x || node.y != move.y) break
                if (!moves.hasNext()) { fit(); break }
                move = moves.next()
            }
        }
    }

    GlassBacking(modifier.clip(MaterialTheme.shapes.large).onGloballyPositioned { canvas = it }.testTag("graph-canvas"), background = {
        // 1.0.1 renders directed edges and handles pan/pinch. Its built-in node drag conflates
        // cancellation with release, and its onConnect isn't dispatched. Own just those gestures.
        KotlinFlow(nodes, edges, theme = theme, kotlinFlowInstance = instance, backgroundVariant = BackgroundVariant.DOTS,
            nodesDraggable = false, elementsSelectable = false, onNodeClick = { onSelect(it.id) }, overlay = {
                wire?.let { draft -> Canvas(Modifier.fillMaxSize()) {
                    drawLine(colors.primary, draft.start, draft.tip, 2.dp.toPx(), StrokeCap.Round)
                } }
            }) { rendered ->
            val node = rendered.data
            DisposableEffect(node.id) { onDispose { boxes.remove(node.id) } }
            Surface(color = if (rendered.selected) colors.primaryContainer else colors.surfaceContainerHighest,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.width(150.dp).onGloballyPositioned { boxes[node.id] = it }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).heightIn(min = 56.dp).testTag("canvas-node:${node.id}")
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.ui_select_node)) { onSelect(node.id) }
                        .pointerInput(node.id, scale) {
                            try {
                                detectDragGestures(onDragStart = {
                                    val current = latest.nodes.find { it.id == node.id }
                                    moving = current?.let { Moving(it.id, latest.revision, Offset(it.x, it.y)) }
                                }, onDrag = { change, delta ->
                                    change.consume()
                                    // Input is already in the transformed node's local pixels; don't divide by zoom again.
                                    moving?.let { moving = it.copy(position = it.position + delta / scale) }
                                }, onDragCancel = { moving = null }, onDragEnd = {
                                    val drop = moving
                                    moving = null
                                    if (drop != null) edit(GraphEdit.MoveNode(drop.id, drop.position.x, drop.position.y), drop.revision)
                                })
                            } finally { if (moving?.id == node.id) moving = null }
                        }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.Center) {
                        Text(node.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.ui_minutes_value, node.minutes), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    var handle by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    val linkDescription = stringResource(R.string.ui_link_to_successor, node.title)
                    Box(Modifier.size(48.dp).testTag("canvas-link:${node.id}").onGloballyPositioned { handle = it }
                        .semantics { contentDescription = linkDescription }
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.ui_select_successor)) { onLinkFrom(node.id) }
                        .pointerInput(node.id) {
                            fun inCanvas(local: Offset): Offset? {
                                val origin = handle?.takeIf { it.isAttached } ?: return null
                                return canvas?.takeIf { it.isAttached }?.localPositionOf(origin, local)
                            }
                            try {
                                detectDragGestures(onDragStart = { touch ->
                                    inCanvas(touch)?.let { wire = Wire(node.id, latest.revision, it, it) }
                                }, onDrag = { change, _ ->
                                    change.consume()
                                    inCanvas(change.position)?.let { pos -> wire = wire?.copy(tip = pos) }
                                }, onDragCancel = { wire = null }, onDragEnd = {
                                    val drop = wire
                                    wire = null
                                    val view = canvas
                                    if (drop != null && view?.isAttached == true && drop.tip.x in 0f..view.size.width.toFloat() &&
                                        drop.tip.y in 0f..view.size.height.toFloat()) {
                                        // KotlinFlow raises the selected node above every sibling. At equal
                                        // z-index the last drawn node wins, just like a physical tap.
                                        var target = latestSelection?.takeIf { boxes[it].containsDrop(view, drop.tip) }
                                        if (target == null) {
                                            val candidates = latest.nodes
                                            for (i in candidates.lastIndex downTo 0) {
                                                val id = candidates[i].id
                                                if (boxes[id].containsDrop(view, drop.tip)) { target = id; break }
                                            }
                                        }
                                        if (target != null) edit(GraphEdit.AddEdge(drop.source, target), drop.revision)
                                    }
                                })
                            } finally { if (wire?.source == node.id) wire = null }
                        }, contentAlignment = Alignment.Center) { Text("→", color = colors.primary, style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
        if (nodes.isEmpty()) Text(stringResource(R.string.ui_add_first_node), Modifier.align(Alignment.Center), color = colors.onSurfaceVariant)
    }, foreground = {
        if (nodes.isNotEmpty()) ControlGroup(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
            ActionPill(stringResource(R.string.ui_auto_arrange), {
                val height = boxes.values.maxOfOrNull { if (it.isAttached) it.size.height / scale else 0f } ?: 0f
                val moves = arrangeGraph(detail, maxOf(56f, height) + 32f)
                if (moves.isEmpty()) fit()
                else if (onArrange(moves, detail.revision)) arranged = detail.revision to moves
            }, Modifier.testTag("graph-arrange"))
            ActionPill(stringResource(R.string.ui_fit_graph), ::fit)
        }
    })
}
