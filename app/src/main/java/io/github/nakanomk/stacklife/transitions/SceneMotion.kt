package io.github.nakanomk.stacklife.transitions

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.SharedContentConfig
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
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

private val cardBounds = BoundsTransform { _, _ -> tween(320) }
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

private class RowAnchor {
    var x by mutableIntStateOf(AlignmentLine.Unspecified)
    var y by mutableIntStateOf(AlignmentLine.Unspecified)
    var height by mutableIntStateOf(0)
}

private val LocalAnchors = staticCompositionLocalOf<MutableMap<String, RowAnchor>?> { null }

@Composable
internal fun EntityMotionScope(content: @Composable () -> Unit) {
    val rows = remember { mutableStateMapOf<String, RowAnchor>() }
    CompositionLocalProvider(LocalAnchors provides rows, content = content)
}

@Composable
internal fun SharedTransitionScope.cardBoundsModifier(key: String): Modifier {
    val list = LocalCardList.current
    if (key.startsWith(TitlePart)) {
        val owner = if (list == null) "detail" else "row"
        return Modifier.testTag("$owner-$key").layout { measurable, constraints ->
            val title = measurable.measure(constraints)
            layout(title.width, title.height, alignmentLines = titleLines) { title.place(0, 0) }
        }
    }
    if (list == null) return Modifier
    val config = remember(list, key) {
        val id = key.substringAfter(':')
        object : SharedContentConfig {
            override val SharedTransitionScope.SharedContentState.isEnabled: Boolean
                get() {
                    val viewport = list.layoutInfo
                    val row = viewport.visibleItemsInfo.firstOrNull { it.key == id } ?: return false
                    return row.offset >= viewport.viewportStartOffset + viewport.beforeContentPadding &&
                        row.offset + row.size <= viewport.viewportEndOffset - viewport.afterContentPadding
                }
        }
    }
    val state = rememberSharedContentState(key, config)
    val rows = LocalAnchors.current
    val anchor = remember(key) { RowAnchor() }
    DisposableEffect(key, rows) {
        rows?.put(key, anchor)
        onDispose { rows?.remove(key, anchor) }
    }
    return Modifier.sharedBounds(state, LocalNavAnimatedContentScope.current, boundsTransform = cardBounds,
        enter = EnterTransition.None, exit = ExitTransition.None, renderInOverlayDuringTransition = false)
        .layout { measurable, constraints ->
            val row = measurable.measure(constraints)
            val lookahead = isLookingAhead
            if (lookahead) {
                anchor.x = row[TitleX]
                anchor.y = row[TitleY]
                anchor.height = row.height
            }
            layout(row.width, row.height) {
                // The detail owns the one painted plane in either direction. Keep the
                // item's lazy-list space, not its now-invisible title, menu or hit targets.
                if (lookahead || !state.isMatchFound || !isTransitionActive) row.place(0, 0)
            }
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
    // Enlist the real title zoom on Nav3's transition immediately. If both scenes have
    // no animation yet when a push starts, a lazy row can be disposed before the match.
    val titleZoom = visibility.transition.animateFloat({ tween(320) }, label = "entity title scale") {
        if (it == EnterExitState.Visible) 1f else compactScale
    }
    val expanded = remember(key) { mutableFloatStateOf(1f) }
    val appliedScale = remember(key) { mutableFloatStateOf(1f) }
    val windowInOverlay = remember(key) { mutableStateOf(Rect.Zero) }
    val fixedChrome: @Composable () -> Modifier = {
        var origin by remember { mutableStateOf(Offset.Zero) }
        Modifier.layout { measurable, constraints ->
            val chrome = measurable.measure(constraints)
            val lookahead = isLookingAhead
            layout(chrome.width, chrome.height) {
                val c = coordinates
                if (lookahead || c == null || !state.isMatchFound || !isTransitionActive) chrome.place(0, 0)
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
        }.renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f) { state.isMatchFound && isTransitionActive }
            .drawWithContent {
                if (!state.isMatchFound || !isTransitionActive) drawContent()
                else {
                    // Escape the moving page's safe-area clip, but not the card's own reveal.
                    val bounds = windowInOverlay.value
                    clipRect(bounds.left - origin.x, bounds.top - origin.y,
                        bounds.right - origin.x, bounds.bottom - origin.y) { this@drawWithContent.drawContent() }
                }
            }
    }
    val tint = MaterialTheme.colorScheme.surfaceTint
    Box(Modifier.sharedBounds(state, visibility, boundsTransform = cardBounds,
        enter = EnterTransition.None, exit = ExitTransition.None,
        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        clipInOverlayDuringTransition = OverlayClip(RectangleShape))
        .fillMaxSize().then(tag).clipToBounds().background(MaterialTheme.colorScheme.surface)
        .drawWithContent {
            drawContent()
            if (state.isMatchFound && isTransitionActive) {
                val t = expanded.floatValue
                // A brief Material tonal elevation distinguishes this paper from the
                // next list row; the background scene itself is never dimmed or faded.
                drawRect(tint, alpha = .08f * 4f * t * (1f - t))
            }
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
                    expanded.floatValue = 1f
                    appliedScale.floatValue = 1f
                    page.place(0, 0)
                } else {
                    val t = ((h - row.height).toFloat() / (page.height - row.height)).coerceIn(0f, 1f)
                    val scale = titleZoom.value
                    expanded.floatValue = t
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
