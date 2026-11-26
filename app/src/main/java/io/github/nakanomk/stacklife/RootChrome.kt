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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveBlur
import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.transitions.LocalRouteOriginsEnabled
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.planner.NavStyle
import io.github.nakanomk.stacklife.transitions.routeOrigin

internal val LocalFeatureBack = compositionLocalOf<(() -> Unit)?> { null }
internal val LocalRootBottom = compositionLocalOf { PaddingValues(0.dp) }
internal val LocalPageChromeInsets = staticCompositionLocalOf { false }
internal val chromeGutter = 16.dp
private val pageMaxWidth = 760.dp

/** Preserve the safe reading/interaction width without shortening the chrome's drawing plane. */
@Composable
internal fun Modifier.pageContentLane(): Modifier {
    if (!LocalPageChromeInsets.current) return this
    val safe = fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
    return if (LocalAppearance.current.navStyle == NavStyle.DOCKED) safe else
        safe.wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = pageMaxWidth).fillMaxWidth()
}

/** Layout breakpoints are about available controls, not the full-bleed optical plane. */
@Composable
internal fun BoxWithConstraintsScope.pageControlSize(): DpSize {
    if (!LocalPageChromeInsets.current) return DpSize(maxWidth, maxHeight)
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val dir = LocalLayoutDirection.current
    val width = (maxWidth - safe.calculateLeftPadding(dir) - safe.calculateRightPadding(dir)).coerceAtLeast(0.dp)
    return DpSize(if (LocalAppearance.current.navStyle == NavStyle.DOCKED) width else minOf(width, pageMaxWidth),
        (maxHeight - safe.calculateTopPadding()).coerceAtLeast(0.dp))
}

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
    overlay: (@Composable BoxScope.(top: Dp, bottom: Dp) -> Unit)? = null,
    contentDrawsTopEdge: Boolean = false,
    content: @Composable (top: Dp, bottom: Dp) -> Unit) {
    val source = rememberPlannerBackdrop(enabled = !contentDrawsTopEdge)
    val headerSource = if (contentDrawsTopEdge) LocalCapsuleBackdrop.current else source
    val bottom = LocalRootBottom.current.calculateBottomPadding()
    // BiliPai SettingsPinnedChromeLayout: measure the pinned region, pass its height to the
    // content's scrolling padding, then place both at y=0. No inset crops the sampling plane.
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val chrome = subcompose("chrome") {
            PinnedChrome(headerSource, drawBackground = !contentDrawsTopEdge, content = header)
        }.single().measure(constraints.copy(minHeight = 0))
        val top = chrome.height.toDp()
        val body = subcompose("content") {
            Box(Modifier.fillMaxSize()) {
                val lane = if (contentDrawsTopEdge) Modifier else Modifier.pageContentLane()
                Box(Modifier.fillMaxSize().plannerBackdrop(source).wallpaperBackground(MaterialTheme.colorScheme.surface).then(lane)) {
                    content(top, bottom)
                }
                if (overlay != null) CompositionLocalProvider(LocalCapsuleBackdrop provides headerSource) {
                    Box(Modifier.fillMaxSize().pageContentLane()) { overlay.invoke(this, top, bottom) }
                }
            }
        }.single().measure(constraints)
        layout(body.width, body.height) {
            body.placeRelative(0, 0)
            chrome.placeRelative(0, 0)
        }
    }
}

/** Fixed forms (including the key editor) sample only Page's wallpaper, never their fields. */
@Composable
internal fun PinnedPaperHeader(content: @Composable () -> Unit) {
    PinnedChrome(rememberPagePaper(MaterialTheme.colorScheme.surface), true, content)
}

@Composable
private fun PinnedChrome(source: Backdrop?, drawBackground: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        if (drawBackground) TopChromeBacking(source, Modifier.matchParentSize())
        val inset = if (LocalPageChromeInsets.current) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)) else Modifier
        Box(inset.fillMaxWidth().pageContentLane()) {
            CompositionLocalProvider(LocalCapsuleBackdrop provides source, content = content)
        }
    }
}

private val topChromeGradient = ProgressiveBlur.Top.copy(startFraction = 0f, endFraction = 1f, curve = 1.25f)

@Composable
internal fun TopChromeBacking(source: Backdrop?, modifier: Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    if (source == null || source is UniformPagePaper) {
        Spacer(modifier.background(surface))
        return
    }
    val foreground = MaterialTheme.colorScheme.onSurface
    // A scrolling donor can contain either tone. Use BiliPai's unknown-band worst case, not
    // the average wallpaper color. The icon owner can rely on this protection across the inset.
    val tint = remember(surface, foreground) {
        val worst = if (foreground.luminance() < .5f) 0f else 1f
        surface.copy(alpha = wallpaperProtection(worst, surface, foreground).coerceAtLeast(.18f))
    }
    val protectedTop = if (LocalPageChromeInsets.current) WindowInsets.safeDrawing else WindowInsets(0)
    val wash = Modifier.drawWithCache {
        val plateau = if (size.height > 0) (protectedTop.getTop(this) / size.height).coerceIn(0f, .85f) else 0f
        val ramp = 1f - plateau
        val brush = Brush.verticalGradient(
            // Clamp before the first stop to preserve the protected inset (also when it is zero).
            plateau to tint,
            (plateau + ramp * .25f) to tint.copy(alpha = tint.alpha * (232f / 255f)),
            (plateau + ramp * .5f) to tint.copy(alpha = tint.alpha * (176f / 255f)),
            (plateau + ramp * .75f) to tint.copy(alpha = tint.alpha * (96f / 255f)),
            1f to tint.copy(alpha = 0f),
        )
        onDrawWithContent { drawContent(); drawRect(brush) }
    }
    ProgressiveEdge(source, modifier.then(wash), topChromeGradient, 10.dp)
}

@Composable
internal fun ScrollEdge(source: PlannerBackdrop, modifier: Modifier) {
    val tint = MaterialTheme.colorScheme.surface.copy(alpha = .40f)
    val wash = Modifier.drawWithCache {
        val brush = Brush.verticalGradient(listOf(tint, Color.Transparent))
        onDrawWithContent { drawContent(); drawRect(brush) }
    }
    ProgressiveEdge(source, modifier.then(wash), ProgressiveBlur.Top, 16.dp)
}

@Composable
private fun ProgressiveEdge(source: Backdrop, modifier: Modifier, gradient: ProgressiveBlur, radius: Dp) {
    val reader = rememberGlassReader(source)
    Spacer(modifier.drawBackdrop(reader.backdrop, shape = { RectangleShape }, progressiveGradient = gradient,
        effects = { progressiveBlur(radius.toPx(), gradient = gradient) }).then(reader.placement))
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
