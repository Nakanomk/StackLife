package io.github.nakanomk.stacklife

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val tabs = listOf("today" to "今天", "workspace" to "工作台", "courses" to "课表")

/** Page already insets its children from system gestures. Lists scroll under this overlay, but
 * can bring their last item fully above its measured height, including at large font sizes. */
@Composable
internal fun DockOverlay(
    navigationBar: @Composable () -> Unit, onAdd: (() -> Unit)? = null,
    content: @Composable (bottomClearance: Dp) -> Unit,
) {
    val density = LocalDensity.current
    var dockHeight by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        content(if (dockHeight == 0) 80.dp else with(density) { dockHeight.toDp() })
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { dockHeight = it.height }
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { navigationBar() }
            if (onAdd != null) FloatingActionButton(onClick = onAdd, shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(56.dp).testTag("add-task").semantics { contentDescription = "新增任务" }) {
                val ink = LocalContentColor.current
                Canvas(Modifier.size(24.dp)) {
                    drawLine(ink, Offset(3.dp.toPx(), center.y), Offset(size.width - 3.dp.toPx(), center.y), 2.5.dp.toPx(), StrokeCap.Round)
                    drawLine(ink, Offset(center.x, 3.dp.toPx()), Offset(center.x, size.height - 3.dp.toPx()), 2.5.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
internal fun rememberDestinationCapsule(active: String, onSelect: (String) -> Unit): @Composable () -> Unit {
    // Own the sliding indicator above the tab's content; changing pages must not recreate it at rest.
    val slot = animateFloatAsState(tabs.indexOfFirst { it.first == active }.toFloat(), tween(200), label = "navigation slot")
    return { DestinationCapsule(active, slot, onSelect) }
}

@Composable
private fun DestinationCapsule(active: String, slot: State<Float>, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.widthIn(max = 256.dp).fillMaxWidth().testTag("navigation-capsule"), shape = CircleShape,
        color = colors.surfaceContainerHigh, shadowElevation = 6.dp) {
        Row(Modifier.selectableGroup().padding(horizontal = 6.dp, vertical = 5.dp).drawBehind {
            val width = size.width / tabs.size
            val position = if (layoutDirection == LayoutDirection.Ltr) slot.value else tabs.lastIndex - slot.value
            drawRoundRect(colors.secondaryContainer, Offset(position * width, 0f), Size(width, size.height),
                CornerRadius(size.height / 2))
        }) {
            for ((id, label) in tabs) {
                val selected = active == id
                val ink = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                Column(Modifier.weight(1f).clip(CircleShape)
                    .testTag("nav:$id").selectable(selected, role = Role.Tab) { onSelect(id) }
                    .heightIn(min = 48.dp).padding(vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    DockIcon(id, ink)
                    Text(label, color = ink, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun DockIcon(id: String, ink: Color) {
    Canvas(Modifier.size(20.dp)) {
        val unit = size.minDimension / 20f
        val stroke = 1.6f * unit
        fun point(x: Int, y: Int) = Offset(x * unit, y * unit)
        fun line(x: Int, y: Int, endX: Int, endY: Int) = drawLine(ink, point(x, y), point(endX, endY), stroke, StrokeCap.Round)
        when (id) {
            "today" -> {
                drawCircle(ink, 8 * unit, style = Stroke(stroke))
                line(10, 5, 10, 10); line(10, 10, 14, 12)
            }
            "workspace" -> {
                drawRoundRect(ink, point(3, 2), Size(14 * unit, 9 * unit), CornerRadius(2 * unit), style = Stroke(stroke))
                line(4, 14, 16, 14); line(6, 17, 14, 17)
            }
            "courses" -> {
                drawRoundRect(ink, point(2, 3), Size(16 * unit, 14 * unit), CornerRadius(2 * unit), style = Stroke(stroke))
                line(3, 8, 17, 8); line(8, 9, 8, 16)
            }
        }
    }
}
