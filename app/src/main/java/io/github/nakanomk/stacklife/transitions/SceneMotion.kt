package io.github.nakanomk.stacklife.transitions

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
    var width by mutableIntStateOf(0)
    var height by mutableIntStateOf(0)
    var radius by mutableFloatStateOf(0f)
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
    val source = remember(key, owner, density, shape) { ContainerAnchor(OriginSite(owner, key, 0L), shape, density, hosted = false) }
    DisposableEffect(key, rows, source) {
        rows?.put(key, anchor)
        registry?.attach(source)
        onDispose { rows?.remove(key, anchor); registry?.detach(source) }
    }
    return Modifier.onGloballyPositioned { source.place(it, registry?.host) }.layout { measurable, constraints ->
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
    val scope = LocalContainerScope.current ?: return
    if (!LocalRouteOriginsEnabled.current) return
    val rows = LocalAnchors.current ?: return
    val row = rows[entityKey]
    if (row != null) {
        val radius = (shape as? CornerBasedShape)?.topStart?.toPx(
            Size(row.width.toFloat(), row.height.toFloat()), LocalDensity.current) ?: 0f
        SideEffect { row.radius = radius }
    }
    val origins = LocalRouteOrigins.current ?: return
    val link = origins.links[entityKey] ?: return
    val sourceSide = link.driver?.row ?: return
    with(scope) {
        val state = gatedContent(link) { origins.live(link) &&
            row?.visible() == true && row.x != AlignmentLine.Unspecified && row.y != AlignmentLine.Unspecified }
        Spacer(modifier.onGloballyPositioned { link.sourcePlaced = true }.sharedBounds(state, sourceSide, boundsTransform = containerBounds,
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
    val origins = LocalRouteOrigins.current
    val link = origins?.links?.get(key)
    var titlePlaced by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val front = LocalFrontEntry.current
    val state = gatedContent(link ?: key) { front && link != null && origins.size == link.hostSize && link.density == density &&
        titlePlaced }
    val typography = MaterialTheme.typography
    val compactScale = with(LocalDensity.current) {
        typography.bodyLarge.fontSize.toPx() / typography.titleLarge.fontSize.toPx()
    }
    val visibility = LocalNavAnimatedContentScope.current
    // Enlist the landing on Nav3's timeline immediately. Otherwise a lazy origin can
    // be disposed on the first push frame, before the shared match has been established.
    val openness = visibility.routeOpen(front)
    val returning = visibility.transition.currentState == EnterExitState.Visible
    val moving = front && visibility.transition.currentState != visibility.transition.targetState
    val driver = if (origins == null) null else pairVisibility(origins, link, openness, moving, returning)
    val sampled = driver?.open ?: openness
    val depth = LocalEntryDepth.current
    val radius = { (rows?.get(key)?.radius ?: 0f) * (1f - sampled.value) }
    val paired = { moving && link != null && link.drawingMatch(origins, state) }
    val ink = { if (paired()) containerInk(sampled.value, returning) else 1f }
    if (link != null) {
        SideEffect { link.open = sampled }
        DisposableEffect(link) { onDispose { link.destination = null; link.open = null } }
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
            .then(if (!moving || link == null) Modifier else Modifier.sourceHandoff(ink) {
                origins.sourceBounds(link)?.translate(-origin)
            })
    }
    val sharedFrame = if (link?.sourcePlaced != true || driver == null) Modifier else
        Modifier.sharedBounds(state, driver.page, boundsTransform = containerBounds,
        enter = EnterTransition.None, exit = ExitTransition.None,
        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        renderInOverlayDuringTransition = false).placedPage(link)
    Box(sharedFrame.fillMaxSize().then(tag).graphicsLayer {
            // With no live partner, fade in place; never aim at a newly recycled row.
            alpha = when {
                !moving -> 1f
                paired() -> if (sampled.value > 0f) 1f else 0f
                link != null && origins.live(link) && !returning -> sampled.value
                else -> openness.value
            }
            if (link != null && paired()) shape = link.shape
            clip = true
        }.containerPaper(origins, link, moving, paired, ink)
        .approachLayout(isMeasurementApproachInProgress = { paired() },
            isPlacementApproachInProgress = { paired() }) { measurable, window ->
            // Register the bounds in normal composition; lookahead already supplies the
            // final page constraints, so there is no need to subcompose during measure.
            val page = measurable.measure(lookaheadConstraints)
            val w = window.constrainWidth(page.width)
            val h = window.constrainHeight(page.height)
            layout(w, h) {
                if (paired()) coordinates?.let {
                    val position = lookaheadScopeCoordinates.localPositionOf(it, Offset.Zero)
                    windowInOverlay.value = Rect(position.x, position.y, position.x + w, position.y + h)
                }
                val row = if (paired() && h != page.height) rows?.get(key) else null
                val tx = if (row != null) page[TitleX] else AlignmentLine.Unspecified
                val ty = if (row != null) page[TitleY] else AlignmentLine.Unspecified
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
        }.layout { measurable, constraints ->
            val page = measurable.measure(constraints)
            titlePlaced = page[TitleX] != AlignmentLine.Unspecified && page[TitleY] != AlignmentLine.Unspecified
            layout(page.width, page.height) { page.place(0, 0) }
        }) {
        CompositionLocalProvider(LocalDetailChrome provides fixedChrome, content = content)
    }
}
