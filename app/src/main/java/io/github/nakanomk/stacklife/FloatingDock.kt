package io.github.nakanomk.stacklife

import androidx.compose.ui.res.stringResource

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.assistant.AssistantComposer
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.assistant.LocalAssistant
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.planner.NavStyle

private val LocalShortDock = compositionLocalOf { false }

@Composable
internal fun DockOverlay(
    navigationBar: (@Composable () -> Unit)? = null,
    assistantPage: AssistantPage? = null,
    content: @Composable (bottomClearance: PaddingValues) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // IME animation changes the safe height every frame, but only crossing a
        // layout breakpoint changes the composition of the dock.
        val landscape = maxWidth > maxHeight
        DockScaffold(
            short = maxWidth >= 480.dp && landscape && maxHeight < 220.dp,
            wideRow = maxWidth >= 600.dp && landscape,
            navigationBar, assistantPage, content,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DockScaffold(short: Boolean, wideRow: Boolean, navigationBar: (@Composable () -> Unit)?,
    assistantPage: AssistantPage?, content: @Composable (PaddingValues) -> Unit) {
    val appearance = LocalAppearance.current
    val docked = appearance.navStyle == NavStyle.DOCKED
    val composer = assistantPage?.takeIf { LocalAssistant.current != null }
    var composerFocused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val target = WindowInsets.imeAnimationTarget
    val hasNav = navigationBar != null
    val showNav by remember(hasNav, target, density) { derivedStateOf {
        hasNav && (!composerFocused || target.getBottom(density) == 0)
    } }
    val source = rememberPlannerBackdrop((navigationBar != null && appearance.navGlass) || (composer != null && appearance.assistantGlass))
    val ms = if (LocalMotionEnabled.current) 150 else 0
    val shareRow = (short || wideRow) && composer != null && showNav
    val navSlot: @Composable () -> Unit = {
        if (navigationBar != null) AnimatedVisibility(showNav,
            enter = expandVertically(tween(ms), expandFrom = Alignment.Bottom),
            exit = shrinkVertically(tween(ms), shrinkTowards = Alignment.Bottom)) {
            Box(if (docked) Modifier.fillMaxWidth() else chromeRail()) {
                CompositionLocalProvider(LocalShortDock provides short) { navigationBar() }
            }
        }
    }
    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0), bottomBar = {
        CompositionLocalProvider(LocalCapsuleBackdrop provides source) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (composer != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(if (short && shareRow) 2f else 1f), contentAlignment = Alignment.Center) {
                        Box(chromeRail(top = 0.dp).onFocusChanged { composerFocused = it.hasFocus }.focusGroup()) {
                            AssistantComposer(composer, persistentRoot = navigationBar != null || LocalFeatureBack.current != null)
                        }
                    }
                    if (shareRow) Box(Modifier.weight(if (short) 3f else 1f), contentAlignment = Alignment.Center) { navSlot() }
                }
                if (!shareRow) navSlot()
            }
        }
    }) { insets ->
        // Both controls share a live GPU tile of the page. Their own labels,
        // input and icons are never in the recording. Keep the content call site
        // stable across an appearance change or a power-saving fallback.
        val page = if (source != null) rememberGraphicsLayer() else null
        Box(Modifier.fillMaxSize()) {
            val capture = if (page == null) Modifier else Modifier.graphicsLayer().drawWithContent {
                page.record { this@drawWithContent.drawContent() }
                drawLayer(page)
            }
            Box(Modifier.fillMaxSize().then(capture)) { content(insets) }
            if (source != null && page != null) {
                SideEffect { source.layer.compositingStrategy = CompositingStrategy.Offscreen }
                Spacer(Modifier.align(Alignment.BottomCenter).fillMaxWidth().layout { child, constraints ->
                    // Include the lens reach, Gaussian blur apron and elastic travel.
                    val height = (insets.calculateBottomPadding() + 64.dp).roundToPx().coerceIn(0, constraints.maxHeight)
                    val band = child.measure(constraints.copy(minHeight = height, maxHeight = height))
                    layout(band.width, height) { band.place(0, 0) }
                }.plannerBackdrop(source, drawInPlace = false).drawBehind {
                    translate(top = size.height - page.size.height) { drawLayer(page) }
                })
            }
        }
    }
}

private fun chromeRail(top: Dp = 4.dp) = Modifier.widthIn(max = 520.dp).fillMaxWidth()
    .padding(start = chromeGutter, end = chromeGutter, top = top, bottom = 4.dp)

@Composable
internal fun rememberDestinationCapsule(active: String, pagerPosition: State<Float>? = null, onSelect: (String) -> Unit): @Composable () -> Unit {
    val appearance = LocalAppearance.current
    val position = appearance.visibleOrder.indexOfFirst { it.key == active }.coerceAtLeast(0).toFloat()
    val slot = pagerPosition ?: animateFloatAsState(position, if (LocalMotionEnabled.current) tween(200) else snap(), label = "navigation slot")
    return {
        val haptics = LocalHapticFeedback.current
        val select: (String) -> Unit = { key ->
            if (key != active) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onSelect(key)
            }
        }
        if (appearance.navStyle == NavStyle.DOCKED) DockedNavigation(appearance.visibleOrder, active, select)
        else DestinationCapsule(appearance.visibleOrder, active, slot, select)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DockedNavigation(tabs: List<Destination>, active: String, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalCapsuleBackdrop.current != null && LocalAppearance.current.navGlass
    StackLifeSurface(CapsuleScene.NAVIGATION, Modifier.fillMaxWidth(), colors.surfaceContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {
        val description = stringResource(R.string.ui_bottom_nav)
        val bar = Modifier.fillMaxWidth().testTag("navigation-capsule").semantics { stateDescription = description }
        if (LocalShortDock.current) BoxWithConstraints(bar) {
            // Material text-only scrollable tabs retain full labels (including long translations)
            // and a 48dp hit row without taking a second row away from the calendar.
            PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOfFirst { it.key == active }.coerceAtLeast(0),
                containerColor = Color.Transparent, edgePadding = 0.dp, minTabWidth = (maxWidth / tabs.size).coerceAtLeast(48.dp),
                divider = {}) {
                for (tab in tabs) key(tab.key) {
                    Tab(selected = tab.key == active, onClick = { onSelect(tab.key) }, modifier = Modifier.heightIn(min = 48.dp).testTag("nav:${tab.key}")) {
                        Text(tab.label, Modifier.padding(horizontal = 4.dp), softWrap = false, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        } else NavigationBar(bar, windowInsets = WindowInsets(0), containerColor = if (glass) Color.Transparent else colors.surfaceContainer) {
            for (tab in tabs) key(tab.key) {
                NavigationBarItem(selected = tab.key == active, onClick = { onSelect(tab.key) },
                    icon = { DockIcon(tab.key, LocalContentColor.current) }, label = { Text(tab.label, textAlign = TextAlign.Center) },
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
            val short = LocalShortDock.current
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
                for (index in tabs.indices) key(tabs[index].key) {
                    val tab = tabs[index]
                    val ink = if (highlighted == index) colors.primary else colors.onSurfaceVariant
                    Column(Modifier.weight(1f).fillMaxHeight()
                        .testTag("nav:${tab.key}").selectable(active == tab.key, role = Role.Tab) { onSelect(tab.key) }
                        .heightIn(min = 48.dp).padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = if (short) Arrangement.Center else Arrangement.Top) {
                        if (!short) DockIcon(tab.key, ink)
                        Text(tab.label, Modifier.fillMaxWidth(), color = ink, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
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
        "courses" -> R.drawable.ic_calendar_view_week
        "countdown" -> R.drawable.ic_today
        else -> error("Unknown destination: $id")
    }
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
}
