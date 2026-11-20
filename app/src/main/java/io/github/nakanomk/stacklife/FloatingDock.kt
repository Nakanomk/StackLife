package io.github.nakanomk.stacklife

import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.assistant.AssistantComposer
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.assistant.LocalAssistant
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.planner.NavStyle

@Composable
internal fun DockOverlay(
    navigationBar: (@Composable () -> Unit)? = null,
    assistantPage: AssistantPage? = null,
    content: @Composable (bottomClearance: Dp) -> Unit,
) {
    val density = LocalDensity.current
    val appearance = LocalAppearance.current
    val docked = appearance.navStyle == NavStyle.DOCKED
    val composer = assistantPage?.takeIf { LocalAssistant.current != null }
    val showNav = navigationBar != null && WindowInsets.ime.getBottom(density) == 0
    val glass = LocalPlannerGlassAllowed.current && appearance.glass &&
        ((!docked && showNav && appearance.navGlass) || (composer != null && appearance.assistantGlass)) &&
        glassUnavailableReason(appearance) == null
    val source = if (glass) rememberHazeState() else null
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val shareRow = maxWidth >= 600.dp && maxWidth > maxHeight && composer != null && showNav
        val navSlot: @Composable () -> Unit = {
            if (showNav) Box(if (docked) Modifier.fillMaxWidth() else Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                navigationBar?.invoke()
            }
        }
        Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0), bottomBar = {
            CompositionLocalProvider(LocalCapsuleBackdrop provides source) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (composer != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Box(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                AssistantComposer(composer)
                            }
                        }
                        if (shareRow) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { navSlot() }
                    }
                    if (!shareRow) navSlot()
                }
            }
        }) { insets ->
            Box(if (source == null) Modifier.fillMaxSize() else Modifier.fillMaxSize().hazeSource(source)) {
                content(insets.calculateBottomPadding())
            }
        }
    }
}

@Composable
internal fun rememberDestinationCapsule(active: String, onSelect: (String) -> Unit): @Composable () -> Unit {
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
    val appearanceGlass = LocalCapsuleBackdrop.current != null && LocalAppearance.current.navGlass
    StackLifeSurface(CapsuleScene.NAVIGATION, Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("navigation-capsule"),
        solid = colors.surfaceContainerLow, elevation = 2.dp) {
        Row(Modifier.selectableGroup().padding(horizontal = 4.dp, vertical = 4.dp).drawBehind {
            val width = size.width / tabs.size
            val position = if (layoutDirection == LayoutDirection.Ltr) slot.value else tabs.lastIndex - slot.value
            drawRoundRect(colors.secondaryContainer.copy(alpha = if (appearanceGlass) .62f else 1f), Offset(position * width, 0f), Size(width, size.height),
                CornerRadius(size.height / 2))
        }) {
            for (tab in tabs) key(tab.key) {
                val id = tab.key
                val selected = active == id
                val ink = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                Column(Modifier.weight(1f).clip(CircleShape)
                    .testTag("nav:$id").selectable(selected, role = Role.Tab) { onSelect(id) }
                    .heightIn(min = 48.dp).padding(vertical = 2.dp),
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
    val icon = when (id) {
        "today" -> R.drawable.ic_schedule
        "workspace" -> R.drawable.ic_view_agenda
        "graphs" -> R.drawable.ic_account_tree
        else -> R.drawable.ic_calendar_view_week
    }
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
}
