package io.github.nakanomk.stacklife

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.unit.dp

import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.assistant.AssistantComposer
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.assistant.LocalAssistant
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.planner.NavStyle

@OptIn(ExperimentalLayoutApi::class)
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
    var composerFocused by remember { mutableStateOf(false) }
    val showNav = navigationBar != null && (!composerFocused || WindowInsets.imeAnimationTarget.getBottom(density) == 0)
    val source = rememberPlannerBackdrop((navigationBar != null && appearance.navGlass) || (composer != null && appearance.assistantGlass))
    val ms = if (LocalMotionEnabled.current) 150 else 0
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val shareRow = maxWidth >= 600.dp && maxWidth > maxHeight && composer != null && showNav
        val navSlot: @Composable () -> Unit = {
            if (navigationBar != null) AnimatedVisibility(showNav,
                enter = expandVertically(tween(ms), expandFrom = Alignment.Bottom),
                exit = shrinkVertically(tween(ms), shrinkTowards = Alignment.Bottom)) {
                Box(if (docked) Modifier.fillMaxWidth() else chromeRail()) { navigationBar() }
            }
        }
        Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0), bottomBar = {
            CompositionLocalProvider(LocalCapsuleBackdrop provides source) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (composer != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Box(chromeRail().onFocusChanged { composerFocused = it.hasFocus }.focusGroup()) {
                                AssistantComposer(composer, persistentRoot = navigationBar != null)
                            }
                        }
                        if (shareRow) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { navSlot() }
                    }
                    if (!shareRow) navSlot()
                }
            }
        }) { insets ->
            Box(Modifier.fillMaxSize().plannerBackdrop(source)) {
                content(insets.calculateBottomPadding())
            }
        }
    }
}

private fun chromeRail() = Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = chromeGutter, vertical = 4.dp)

@Composable
internal fun rememberDestinationCapsule(active: String, pagerPosition: State<Float>? = null, onSelect: (String) -> Unit): @Composable () -> Unit {
    val appearance = LocalAppearance.current
    val position = appearance.order.indexOfFirst { it.key == active }.coerceAtLeast(0).toFloat()
    val slot = pagerPosition ?: animateFloatAsState(position, if (LocalMotionEnabled.current) tween(200) else snap(), label = "navigation slot")
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
    val colors = MaterialTheme.colorScheme
    val glass = LocalCapsuleBackdrop.current != null && LocalAppearance.current.navGlass
    StackLifeSurface(CapsuleScene.NAVIGATION, Modifier.fillMaxWidth(), colors.surfaceContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {
        NavigationBar(Modifier.fillMaxWidth().testTag("navigation-capsule").semantics { stateDescription = "贴底导航" },
            windowInsets = WindowInsets(0), containerColor = if (glass) Color.Transparent else colors.surfaceContainer) {
            for (tab in tabs) key(tab.key) {
                NavigationBarItem(selected = tab.key == active, onClick = { onSelect(tab.key) },
                    icon = { DockIcon(tab.key, LocalContentColor.current) }, label = { Text(tab.label) },
                    modifier = Modifier.testTag("nav:${tab.key}"))
            }
        }
    }
}

@Composable
private fun DestinationCapsule(tabs: List<Destination>, active: String, slot: State<Float>, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    StackLifeSurface(CapsuleScene.NAVIGATION, Modifier.fillMaxWidth().testTag("navigation-capsule"),
        solid = colors.surfaceContainerLow, elevation = 2.dp) {
        SegmentTrack(slot, tabs.size, colors.secondaryContainer, { onSelect(tabs[it].key) },
            Modifier.fillMaxWidth(), navigationInset = 4.dp) { highlighted ->
            Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                for (index in tabs.indices) key(tabs[index].key) {
                    val tab = tabs[index]
                    val ink = if (highlighted == index) colors.primary else colors.onSurfaceVariant
                    Column(Modifier.weight(1f).clip(CircleShape)
                        .testTag("nav:${tab.key}").selectable(active == tab.key, role = Role.Tab) { onSelect(tab.key) }
                        .heightIn(min = 48.dp).padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        DockIcon(tab.key, ink)
                        Text(tab.label, color = ink, style = MaterialTheme.typography.labelMedium)
                    }
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
