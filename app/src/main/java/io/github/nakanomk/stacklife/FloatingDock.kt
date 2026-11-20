package io.github.nakanomk.stacklife

import androidx.compose.animation.core.snap
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.planner.NavStyle

/** Page already insets its children from system gestures. Lists scroll under this overlay, but
 * can bring their last item fully above its measured height, including at large font sizes. */
@Composable
internal fun DockOverlay(
    navigationBar: @Composable () -> Unit,
    content: @Composable (bottomClearance: Dp) -> Unit,
) {
    val density = LocalDensity.current
    val appearance = LocalAppearance.current
    val docked = appearance.navStyle == NavStyle.DOCKED
    val glass = !docked && appearance.glass && appearance.navGlass && glassUnavailableReason(appearance) == null
    val source = if (glass) rememberHazeState() else null
    var dockHeight by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        // The input and its consumer are siblings. This slot contains only the page's planner
        // body (or the appearance editor's local preview), never a window or a WebView.
        Box(if (source == null) Modifier.fillMaxSize() else Modifier.fillMaxSize().hazeSource(source)) {
            content(if (dockHeight == 0) 80.dp else with(density) { dockHeight.toDp() })
        }
        val surround = if (docked) Modifier.fillMaxWidth() else Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
        Box(Modifier.align(Alignment.BottomCenter).onSizeChanged { dockHeight = it.height }.then(surround)) {
            CompositionLocalProvider(LocalNavigationGlass provides source) { navigationBar() }
        }
    }
}

@Composable
internal fun rememberDestinationCapsule(active: String, onSelect: (String) -> Unit): @Composable () -> Unit {
    // Own the sliding indicator above the tab's content; changing pages must not recreate it at rest.
    val appearance = LocalAppearance.current
    val position = appearance.order.indexOfFirst { it.key == active }.coerceAtLeast(0).toFloat()
    val slot = animateFloatAsState(position, if (LocalMotionEnabled.current) tween(200) else snap(), label = "navigation slot")
    return {
        val haptics = LocalHapticFeedback.current
        val select: (String) -> Unit = { key ->
            if (key != active) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onSelect(key)
            }
        }
        if (appearance.navStyle == NavStyle.DOCKED) DockedNavigation(appearance.order, active, select)
        else DestinationCapsule(appearance.order, active, slot, select)
    }
}

@Composable
private fun DockedNavigation(tabs: List<Destination>, active: String, onSelect: (String) -> Unit) {
    NavigationBar(Modifier.fillMaxWidth().testTag("navigation-capsule").semantics { stateDescription = "贴底导航" },
        windowInsets = WindowInsets(0), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        for (tab in tabs) key(tab.key) {
            NavigationBarItem(selected = tab.key == active, onClick = { onSelect(tab.key) },
                icon = { DockIcon(tab.key, LocalContentColor.current) }, label = { Text(tab.label) },
                modifier = Modifier.testTag("nav:${tab.key}"))
        }
    }
}

@Composable
private fun DestinationCapsule(tabs: List<Destination>, active: String, slot: State<Float>, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    StackLifeSurface(CapsuleScene.NAVIGATION, Modifier.widthIn(max = 360.dp).fillMaxWidth().testTag("navigation-capsule"),
        solid = colors.surfaceContainerHigh, elevation = 6.dp) {
        Row(Modifier.selectableGroup().padding(horizontal = 6.dp, vertical = 5.dp).drawBehind {
            val width = size.width / tabs.size
            val position = if (layoutDirection == LayoutDirection.Ltr) slot.value else tabs.lastIndex - slot.value
            drawRoundRect(colors.secondaryContainer, Offset(position * width, 0f), Size(width, size.height),
                CornerRadius(size.height / 2))
        }) {
            for (tab in tabs) key(tab.key) {
                val id = tab.key
                val selected = active == id
                val ink = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                Column(Modifier.weight(1f).clip(CircleShape)
                    .testTag("nav:$id").selectable(selected, role = Role.Tab) { onSelect(id) }
                    .heightIn(min = 48.dp).padding(vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    DockIcon(id, ink)
                    Text(tab.label, color = ink, style = MaterialTheme.typography.labelMedium)
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
            "graphs" -> {
                line(5, 6, 5, 10); line(5, 10, 15, 10); line(15, 10, 15, 14)
                drawCircle(ink, 2.4f * unit, point(5, 4), style = Stroke(stroke))
                drawCircle(ink, 2.4f * unit, point(15, 16), style = Stroke(stroke))
            }
            "courses" -> {
                drawRoundRect(ink, point(2, 3), Size(16 * unit, 14 * unit), CornerRadius(2 * unit), style = Stroke(stroke))
                line(3, 8, 17, 8); line(8, 9, 8, 16)
            }
        }
    }
}
