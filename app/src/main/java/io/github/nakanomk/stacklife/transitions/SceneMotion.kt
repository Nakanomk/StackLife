package io.github.nakanomk.stacklife.transitions

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.SharedContentConfig
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import io.github.nakanomk.stacklife.appearance.wallpaperBackground
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.util.lerp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import io.github.nakanomk.stacklife.workspace.SharedModifier

// Use the very same Nav3 progress as the clip and title. At the compact end
// geometry is already settled while the original row takes back the foreground.
private val growCard = Easing { containerExpansion(FastOutSlowInEasing.transform(it)) }
private val landCard = Easing { 1f - containerExpansion(1f - FastOutSlowInEasing.transform(it)) }
private val cardBounds = BoundsTransform { from, to ->
    tween(ContainerDuration, easing = if (to.height < from.height) landCard else growCard)
}
internal val noCardBounds: SharedModifier = { Modifier }
internal val LocalCardList = compositionLocalOf<LazyListState?> { null }
internal val LocalDetailChrome = staticCompositionLocalOf<@Composable () -> Modifier> { { Modifier } }

// These are layout anchors, not separate animated or painted copies of the title.
private val TitleX = VerticalAlignmentLine(::minOf)
private val TitleY = HorizontalAlignmentLine(::minOf)
private val titleLines = mapOf(TitleX to 0, TitleY to 0)
private const val TitlePart = "title/"

@Composable
internal fun SharedModifier.title(entityKey: String) = this("$TitlePart$entityKey")

private class RowAnchor(private val inViewport: () -> Boolean) {
    fun visible() = inViewport()
    var x by mutableIntStateOf(AlignmentLine.Unspecified)
    var y by mutableIntStateOf(AlignmentLine.Unspecified)
    var width by mutableIntStateOf(0)
    var height by mutableIntStateOf(0)
    var radius by mutableFloatStateOf(0f)
}

private val LocalAnchors = staticCompositionLocalOf<MutableMap<String, RowAnchor>?> { null }
private val LocalCardScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

@Composable
internal fun SharedTransitionScope.EntityMotionScope(enabled: Boolean, content: @Composable () -> Unit) {
    val rows = remember { mutableStateMapOf<String, RowAnchor>() }
    CompositionLocalProvider(LocalAnchors provides rows, LocalCardScope provides this.takeIf { enabled }, content = content)
}

@Composable
internal fun cardBoundsModifier(key: String): Modifier {
    val list = LocalCardList.current
    if (key.startsWith(TitlePart)) {
        val owner = if (list == null) "detail" else "row"
        return Modifier.testTag("$owner-$key").layout { measurable, constraints ->
            val title = measurable.measure(constraints)
            layout(title.width, title.height, alignmentLines = titleLines) { title.place(0, 0) }
        }
    }
    if (list == null) return Modifier
    val anchor = remember(list, key) {
        val id = key.substringAfter(':')
        RowAnchor {
            val viewport = list.layoutInfo
            val item = viewport.visibleItemsInfo.firstOrNull { it.key == id }
            item != null && item.offset >= viewport.viewportStartOffset + viewport.beforeContentPadding &&
                item.offset + item.size <= viewport.viewportEndOffset - viewport.afterContentPadding
        }
    }
    val rows = LocalAnchors.current
    DisposableEffect(key, rows) {
        rows?.put(key, anchor)
        onDispose { rows?.remove(key, anchor) }
    }
    return Modifier.layout { measurable, constraints ->
        val row = measurable.measure(constraints)
        if (isLookingAhead) {
            anchor.x = row[TitleX]
            anchor.y = row[TitleY]
            anchor.width = row.width
            anchor.height = row.height
        }
        layout(row.width, row.height) { row.place(0, 0) }
    }
}

/** An unpainted sibling, measured *after* the real card. Shared bounds can never resize,
 * move or suppress the row whose pixels and list position receive the returning detail. */
@Composable
internal fun CardBoundsAnchor(entityKey: String, modifier: Modifier, shape: Shape = RectangleShape) {
    val scope = LocalCardScope.current ?: return
    if (!LocalRouteOriginsEnabled.current) return
    val rows = LocalAnchors.current ?: return
    val row = rows[entityKey]
    if (row != null) {
        val radius = (shape as? CornerBasedShape)?.topStart?.toPx(
            Size(row.width.toFloat(), row.height.toFloat()), LocalDensity.current) ?: 0f
        SideEffect { row.radius = radius }
    }
    with(scope) {
        val config = remember(rows, entityKey) {
            object : SharedContentConfig {
                override val SharedTransitionScope.SharedContentState.isEnabled get() = rows[entityKey]?.visible() == true
                override val shouldKeepEnabledForOngoingAnimation get() = false
            }
        }
        val state = rememberSharedContentState(entityKey, config)
        Spacer(modifier.sharedBounds(state, LocalNavAnimatedContentScope.current, boundsTransform = cardBounds,
            enter = EnterTransition.None, exit = ExitTransition.None, renderInOverlayDuringTransition = false))
    }
}

@Composable
internal fun SharedTransitionScope.DetailRoute(key: String, enabled: Boolean, content: @Composable () -> Unit) {
    val tag = Modifier.testTag("detail:$key")
    if (!enabled) {
        Box(Modifier.fillMaxSize().then(tag)) { content() }
        return
    }
    val rows = LocalAnchors.current
    val state = rememberSharedContentState(key)
    val typography = MaterialTheme.typography
    val compactScale = with(LocalDensity.current) {
        typography.bodyLarge.fontSize.toPx() / typography.titleLarge.fontSize.toPx()
    }
    val visibility = LocalNavAnimatedContentScope.current
    // Enlist the landing on Nav3's timeline immediately. Otherwise a lazy origin can
    // be disposed on the first push frame, before the shared match has been established.
    val front = LocalFrontEntry.current
    val openness = visibility.transition.animateFloat({ if (front) tween(ContainerDuration) else snap() }, label = "entity expansion") {
        if (it == EnterExitState.Visible) 1f else 0f
    }
    val radius = { (rows?.get(key)?.radius ?: 0f) * (1f - containerExpansion(openness.value)) }
    val appliedScale = remember(key) { mutableFloatStateOf(1f) }
    val windowInOverlay = remember(key) { mutableStateOf(Rect.Zero) }
    val fixedChrome: @Composable () -> Modifier = {
        var origin by remember { mutableStateOf(Offset.Zero) }
        val clipPath = remember { Path() }
        Modifier.layout { measurable, constraints ->
            val chrome = measurable.measure(constraints)
            val lookahead = isLookingAhead
            layout(chrome.width, chrome.height) {
                val c = coordinates
                if (lookahead || c == null || !front || !state.isMatchFound || !isTransitionActive) chrome.place(0, 0)
                else {
                    val root = lookaheadScopeCoordinates
                    val target = root.localLookaheadPositionOf(c)
                    origin = target
                    val offset = c.localPositionOf(root, target)
                    val inverse = 1f / appliedScale.floatValue
                    chrome.placeWithLayer(0, 0) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = inverse
                        scaleY = inverse
                        translationX = offset.x
                        translationY = offset.y
                    }
                }
            }
        }.renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f) { front && state.isMatchFound && isTransitionActive }
            .drawWithCache {
                onDrawWithContent {
                    if (!front || !state.isMatchFound || !isTransitionActive) drawContent() else {
                        // Fixed controls use the very same rounded window, in overlay coordinates.
                        drawContainerFrame(windowInOverlay.value.translate(-origin), radius(), openness.value, clipPath)
                    }
                }
            }
    }
    val card = MaterialTheme.colorScheme.surfaceContainerLow
    val paper = MaterialTheme.colorScheme.surface
    val clipPath = remember { Path() }
    Box(Modifier.sharedBounds(state, visibility, boundsTransform = cardBounds,
        enter = EnterTransition.None, exit = ExitTransition.None,
        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        clipInOverlayDuringTransition = OverlayClip(RectangleShape))
        .fillMaxSize().then(tag).graphicsLayer {
            // With no live partner, fade in place; never aim at a newly recycled row.
            alpha = if (front && !state.isMatchFound) openness.value else 1f
            clip = true
        }.drawWithCache {
            val bounds = Rect(Offset.Zero, size)
            onDrawWithContent {
                if (front && state.isMatchFound && openness.value < 1f) {
                    drawContainerFrame(bounds, radius(), openness.value, clipPath)
                } else drawContent()
            }
        }.wallpaperBackground(paper).drawBehind {
            val depth = if (front && state.isMatchFound) containerExpansion(openness.value) else 1f
            if (depth < 1f) drawRect(card.copy(alpha = 1f - depth))
        }
        .approachLayout(isMeasurementApproachInProgress = { state.isMatchFound && isTransitionActive },
            isPlacementApproachInProgress = { state.isMatchFound && isTransitionActive }) { measurable, window ->
            // Register the bounds in normal composition; lookahead already supplies the
            // final page constraints, so there is no need to subcompose during measure.
            val page = measurable.measure(lookaheadConstraints)
            val w = window.constrainWidth(page.width)
            val h = window.constrainHeight(page.height)
            layout(w, h) {
                if (state.isMatchFound && isTransitionActive) coordinates?.let {
                    val position = lookaheadScopeCoordinates.localPositionOf(it, Offset.Zero)
                    windowInOverlay.value = Rect(position.x, position.y, position.x + w, position.y + h)
                }
                val row = if (state.isMatchFound && h != page.height) rows?.get(key) else null
                val tx = if (row != null) page[TitleX] else AlignmentLine.Unspecified
                val ty = if (row != null) page[TitleY] else AlignmentLine.Unspecified
                if (row == null || row.height !in 1 until page.height ||
                    tx == AlignmentLine.Unspecified || ty == AlignmentLine.Unspecified ||
                    row.x == AlignmentLine.Unspecified || row.y == AlignmentLine.Unspecified) {
                    appliedScale.floatValue = 1f
                    page.place(0, 0)
                } else {
                    val t = ((h - row.height).toFloat() / (page.height - row.height)).coerceIn(0f, 1f)
                    val scale = lerp(compactScale, 1f, t)
                    appliedScale.floatValue = scale
                    // Pan the fully laid-out page. No nested Scaffold, LazyColumn or
                    // backdrop can cut across the title on its way out of the row.
                    page.placeWithLayer(0, 0) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                        translationX = lerp(row.x.toFloat(), tx.toFloat(), t) - tx * scale
                        translationY = lerp(row.y.toFloat(), ty.toFloat(), t) - ty * scale
                    }
                }
            }
        }) {
        CompositionLocalProvider(LocalDetailChrome provides fixedChrome, content = content)
    }
}
