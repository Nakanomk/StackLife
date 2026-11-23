package io.github.nakanomk.stacklife

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.github.nakanomk.stacklife.appearance.wallpaperBackground
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveBlur
import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.transitions.LocalRouteOriginsEnabled
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.transitions.routeOrigin

internal val LocalFeatureBack = compositionLocalOf<(() -> Unit)?> { null }
internal val LocalRootBottom = compositionLocalOf { PaddingValues(0.dp) }
internal val chromeGutter = 16.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PagerPane(selected: Boolean, visible: Boolean, modifier: Modifier = Modifier,
    retainedViewport: State<Boolean>? = null, content: @Composable () -> Unit) {
    val semantics = if (selected) Modifier else Modifier.clearAndSetSemantics {}
    val insets = LocalRootBottom.current
    // Retained off-screen pages have no pixels or touch targets to move. Keep
    // their last measured inset; restore live geometry before the first visible placement.
    val pageInsets = if (retainedViewport == null && visible) insets else {
        val bottom = if (visible) insets.calculateBottomPadding() else remember(insets) { insets.calculateBottomPadding() }
        remember(bottom) { PaddingValues(bottom = bottom) }
    }
    // Retained roots keep their real material tree. Gate placement at layout time so the
    // first exposed pixel is not waiting on a recomposition of the visibility flag.
    val showing = retainedViewport ?: rememberUpdatedState(visible)
    val chosen = rememberUpdatedState(selected)
    val viewport = remember(showing, chosen) {
        Modifier.layout { child, constraints ->
            // The pager owns the size of this slot. Moving Home between Nav3 scenes
            // or opening the IME must not measure two invisible business pages.
            // Measure the chosen destination ahead of its first exposed pixel.
            if (!showing.value && !chosen.value && constraints.hasFixedWidth && constraints.hasFixedHeight) {
                layout(constraints.maxWidth, constraints.maxHeight) {}
            } else {
                val measured = child.measure(constraints)
                layout(measured.width, measured.height) {
                    if (showing.value) measured.place(0, 0)
                }
            }
        }
    }
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    if (!selected) SideEffect { if (focused) focus.clearFocus(force = true) }
    Box(semantics.fillMaxSize().then(viewport).then(modifier).onFocusChanged { focused = it.hasFocus }
        .focusProperties { onEnter = { if (!selected) cancelFocusChange() } }.focusGroup()) {
        CompositionLocalProvider(LocalRootBottom provides pageInsets,
            LocalPlannerGlassAllowed provides ((retainedViewport != null || visible) && LocalPlannerGlassAllowed.current),
            LocalRouteOriginsEnabled provides (selected && LocalRouteOriginsEnabled.current), content = content)
    }
}

@Composable
internal fun RootPane(header: @Composable () -> Unit,
    overlay: @Composable BoxScope.(top: Dp, bottom: Dp) -> Unit = { _, _ -> },
    contentDrawsTopEdge: Boolean = false,
    content: @Composable (top: Dp, bottom: Dp) -> Unit) {
    val source = rememberPlannerBackdrop()
    val dockInsets = LocalRootBottom.current
    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0), topBar = {
        Box(Modifier.fillMaxWidth().then(if (source == null) Modifier.wallpaperBackground(MaterialTheme.colorScheme.surface) else Modifier)) {
            if (source != null && !contentDrawsTopEdge) ScrollEdge(source, Modifier.matchParentSize())
            CompositionLocalProvider(LocalCapsuleBackdrop provides source) { header() }
        }
    }) { insets ->
        val top = insets.calculateTopPadding()
        Box(Modifier.fillMaxSize()) {
            val plane = Modifier.fillMaxSize()
            Box(plane.plannerBackdrop(source).wallpaperBackground(MaterialTheme.colorScheme.surface)) {
                content(top, dockInsets.calculateBottomPadding())
            }
            CompositionLocalProvider(LocalCapsuleBackdrop provides source) { overlay(top, dockInsets.calculateBottomPadding()) }
        }
    }
}

@Composable
internal fun ScrollEdge(source: PlannerBackdrop, modifier: Modifier, fadeAbove: Dp = 0.dp) {
    val tint = MaterialTheme.colorScheme.surface.copy(alpha = .40f)
    val reader = rememberGlassReader(source)
    BoxWithConstraints(modifier) {
        val gradient = remember(maxHeight, fadeAbove) {
            if (fadeAbove == 0.dp || maxHeight == 0.dp) ProgressiveBlur.Top else
                ProgressiveBlur.Top.copy(startFraction = ((maxHeight - fadeAbove) / maxHeight).coerceIn(0f, .99f))
        }
        Spacer(Modifier.fillMaxSize()
            .drawWithCache {
                val wash = Brush.verticalGradient(listOf(tint, Color.Transparent),
                    startY = size.height * gradient.startFraction, endY = size.height)
                onDrawWithContent { drawContent(); drawRect(wash) }
            }.drawBackdrop(reader.backdrop, shape = { RectangleShape }, progressiveGradient = gradient,
                effects = { progressiveBlur(16.dp.toPx(), gradient = gradient) }).then(reader.placement))
    }
}

@Composable
internal fun RootToolbar(page: Destination, onSettings: () -> Unit, leading: (@Composable () -> Unit)? = null,
    controls: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = chromeGutter, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val back = LocalFeatureBack.current
        if (back != null) GlassIcon(R.drawable.ic_arrow_back, stringResource(R.string.ui_back), back)
        if (leading != null || back == null) Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) { leading?.invoke() }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            StackLifeSurface(CapsuleScene.CHROME, Modifier.testTag("root-toolbar"), MaterialTheme.colorScheme.surfaceContainer, elevation = 1.dp) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center, content = controls)
            }
        }
        // The same gear may move between Nav3 scene slots. Its tab, not its
        // transient composition position, identifies the real return target.
        GlassIcon(R.drawable.ic_settings, stringResource(R.string.ui_settings), onSettings,
            Modifier.routeOrigin("settings", CircleShape, page.ordinal.toLong()))
    }
}

@Composable
internal fun GlassIcon(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    StackLifeSurface(CapsuleScene.ACTION, modifier.size(48.dp), MaterialTheme.colorScheme.surfaceContainer,
        elevation = 1.dp, interactionSource = interaction.takeIf { enabled }) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize(), interactionSource = interaction) {
            Icon(painterResource(icon), label)
        }
    }
}
