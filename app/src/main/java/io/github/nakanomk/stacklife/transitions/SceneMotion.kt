package io.github.nakanomk.stacklife.transitions

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import io.github.nakanomk.stacklife.workspace.SharedModifier
import kotlin.math.roundToInt

// Entity bounds and title layout use the same Nav3 timeline as ordinary containers.
// BiliPai SettingsSharedContainerTransition, 5893c2c80739e3759ac64ae5f232cf03a94da7f8 (GPL-3.0).
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
    var height by mutableIntStateOf(0)
}

private val LocalAnchors = staticCompositionLocalOf<MutableMap<String, RowAnchor>?> { null }

@Composable
internal fun SharedTransitionScope.EntityMotionScope(enabled: Boolean, content: @Composable () -> Unit) {
    val rows = remember { mutableStateMapOf<String, RowAnchor>() }
    CompositionLocalProvider(LocalAnchors provides rows, LocalContainerScope provides this.takeIf { enabled }, content = content)
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
    val registry = LocalRouteOrigins.current
    val owner = LocalRouteOwner.current
    val density = LocalDensity.current
    val shape = MaterialTheme.shapes.large
    val source = remember(key, owner, density, shape, anchor) { ContainerAnchor(OriginSite(owner, key, 0L), shape, density, anchor::visible) }
    DisposableEffect(anchor, rows, registry, source) {
        rows?.put(key, anchor)
        registry?.attach(source)
        onDispose { rows?.remove(key, anchor); registry?.detach(source) }
    }
    return Modifier.onGloballyPositioned { registry?.place(source, it) }.layout { measurable, constraints ->
        val row = measurable.measure(constraints)
        if (isLookingAhead) {
            anchor.x = row[TitleX]
            anchor.y = row[TitleY]
            anchor.height = row.height
        }
        layout(row.width, row.height) { row.place(0, 0) }
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
    val origins = LocalRouteOrigins.current
    val link = origins?.links?.get(key)
    val density = LocalDensity.current
    val front = LocalFrontEntry.current
    val source = remember(key, origins, rows, link, density) {
        derivedStateOf {
            val row = rows?.get(key)
            if (row == null || link == null || link.density != density || !row.visible()) null
            else origins.sourceBounds(link)
        }
    }
    val typography = MaterialTheme.typography
    val compactScale = with(LocalDensity.current) {
        typography.bodyLarge.fontSize.toPx() / typography.titleLarge.fontSize.toPx()
    }
    val visibility = LocalNavAnimatedContentScope.current
    val sampled = visibility.routeOpen(front)
    val returning = visibility.transition.currentState == EnterExitState.Visible
    val moving = front && visibility.transition.currentState != visibility.transition.targetState
    val depth = LocalEntryDepth.current
    val radius = { (link?.radius ?: 0f) * (1f - sampled.value) }
    // LayoutCoordinates.isAttached is not snapshot state. In particular the existing
    // list can detach and reattach at the *same* rect when Nav3 moves its entry.
    // The source rectangle is placed before the asynchronous detail title. Only its
    // typography needs both title alignment lines; the moving window never has to wait.
    val placedSource = { if (moving && link != null && origins.live(link)) source.value else null }
    val paired = { placedSource() != null }
    val ink = { containerInk(sampled.value, returning) }
    if (link != null) {
        SideEffect { link.open = sampled }
        DisposableEffect(link) { onDispose { link.open = null } }
    }
    val appliedScale = remember(key) { mutableFloatStateOf(1f) }
    val windowInOverlay = remember(key) { mutableStateOf(Rect.Zero) }
    val fixedChrome: @Composable () -> Modifier = {
        var origin by remember { mutableStateOf(Offset.Zero) }
        Modifier.layout { measurable, constraints ->
            val chrome = measurable.measure(constraints)
            val lookahead = isLookingAhead
            layout(chrome.width, chrome.height) {
                val c = coordinates
                if (lookahead || c == null || !paired()) chrome.place(0, 0)
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
        }.renderInSharedTransitionScopeOverlay(zIndexInOverlay = depth + .5f, renderInOverlay = paired)
            .containerClip({ windowInOverlay.value.translate(-origin) }, radius, paired)
            .then(if (!moving || link == null) Modifier else Modifier.sourceHandoff({ if (paired()) ink() else 1f }) {
                origins.sourceBounds(link)?.translate(-origin)
            })
    }
    // Source and detail are now in the same Nav3 scene on push, but in different
    // scenes during pop. Sample the placed row in the host plane rather than waiting
    // for a second SharedBounds transition to acquire the moving source composition.
    val frame = Modifier.fillMaxSize().approachLayout(isMeasurementApproachInProgress = { moving },
        isPlacementApproachInProgress = { moving }) { measurable, window ->
        val w = window.maxWidth
        val h = window.maxHeight
        // Subscribe for the whole trip, even when the row is moving between scene
        // slots or the detail is still loading. A temporarily missing partner is not
        // completion of the navigation transition.
        val t = if (moving) sampled.value else 1f
        val start = placedSource()
        val width = if (start == null) w else lerp(start.width, w.toFloat(), t).roundToInt()
        val height = if (start == null) h else lerp(start.height, h.toFloat(), t).roundToInt()
        val page = measurable.measure(Constraints.fixed(width, height))
        layout(w, h) {
            val x = if (start == null) 0 else (start.left * (1f - t)).roundToInt()
            val y = if (start == null) 0 else (start.top * (1f - t)).roundToInt()
            page.place(x, y)
        }
    }
    Box(frame.then(tag).graphicsLayer {
            // With no live partner, fade in place; never aim at a newly recycled row.
            val matched = paired()
            alpha = when {
                !front -> 1f
                matched -> if (sampled.value > 0f) 1f else 0f
                else -> sampled.value
            }
            shape = if (link != null && matched) link.shape else RectangleShape
            clip = matched
        }.containerPaper(origins, link, moving, paired, ink)
        .layout { measurable, window ->
            val w = window.maxWidth
            val h = window.maxHeight
            val full = link?.hostSize
            val page = measurable.measure(if (moving && full != null && full == origins.size)
                Constraints.fixed(full.width, full.height) else window)
            val tx = page[TitleX]
            val ty = page[TitleY]
            val lookahead = isLookingAhead
            layout(w, h) {
                val matched = !lookahead && paired()
                if (matched) coordinates?.let {
                    val position = lookaheadScopeCoordinates.localPositionOf(it, Offset.Zero)
                    windowInOverlay.value = Rect(position.x, position.y, position.x + w, position.y + h)
                }
                val row = if (matched && h != page.height) rows?.get(key) else null
                // Both titles are measured at their resting constraints.
                if (row == null || row.height !in 1 until page.height ||
                    tx == AlignmentLine.Unspecified || ty == AlignmentLine.Unspecified ||
                    row.x == AlignmentLine.Unspecified || row.y == AlignmentLine.Unspecified) {
                    appliedScale.floatValue = 1f
                    page.place(0, 0)
                } else {
                    val t = sampled.value
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
