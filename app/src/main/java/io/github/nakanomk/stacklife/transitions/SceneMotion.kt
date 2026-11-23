package io.github.nakanomk.stacklife.transitions

import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import io.github.nakanomk.stacklife.appearance.wallpaperBackground
import io.github.nakanomk.stacklife.workspace.SharedModifier

// Entity bounds and title layout use the same Nav3 timeline as ordinary containers.
// BiliPai SettingsSharedContainerTransition, 5893c2c80739e3759ac64ae5f232cf03a94da7f8 (GPL-3.0).
internal val noCardBounds: SharedModifier = { Modifier }
internal val LocalCardList = compositionLocalOf<LazyListState?> { null }
private val LocalDetailBody = compositionLocalOf<Modifier> { Modifier }

@Composable
internal fun Modifier.detailBodyMotion(): Modifier = then(LocalDetailBody.current)

// These are layout anchors, not separate animated or painted copies of the title.
private val TitleX = VerticalAlignmentLine(::minOf)
private val TitleY = HorizontalAlignmentLine(::minOf)
private val titleLines = mapOf(TitleX to 0, TitleY to 0)
private const val TitlePart = "title/"

@Composable
internal fun SharedModifier.title(entityKey: String) = this("$TitlePart$entityKey")

private class CardWindow(val rect: Rect, radius: Float, val edge: EdgeFrame? = null) : Shape {
    private val outline = Outline.Rounded(RoundRect(rect, CornerRadius(radius.coerceIn(0f, rect.minDimension / 2))))
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = outline
}

/** Chrome stays fixed inside a matched card. Its body alone scales around the title;
 * when the row disappears keep the last drawn body transform, not its old row bounds. */
private class BodyPose {
    var scale = 1f
    var x = 0f
    var y = 0f
}

private class RowAnchor(site: OriginSite, shape: Shape, density: Density, val visible: () -> Boolean) :
    ContainerAnchor(site, shape, density, visible) {
    var x by mutableIntStateOf(AlignmentLine.Unspecified)
    var y by mutableIntStateOf(AlignmentLine.Unspecified)
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
    val registry = LocalRouteOrigins.current ?: return Modifier
    val owner = LocalRouteOwner.current
    val density = LocalDensity.current
    val shape = MaterialTheme.shapes.large
    val anchor = remember(list, key, owner, density, shape) {
        val id = key.substringAfter(':')
        RowAnchor(OriginSite(owner, key, 0L), shape, density) {
            val viewport = list.layoutInfo
            val item = viewport.visibleItemsInfo.firstOrNull { it.key == id }
            item != null && item.offset >= viewport.viewportStartOffset + viewport.beforeContentPadding &&
                item.offset + item.size <= viewport.viewportEndOffset - viewport.afterContentPadding
        }
    }
    return Modifier.containerSource(registry, anchor).layout { measurable, constraints ->
        val row = measurable.measure(constraints)
        if (!isLookingAhead) {
            anchor.x = row[TitleX]
            anchor.y = row[TitleY]
        }
        layout(row.width, row.height) { row.place(0, 0) }
    }
}

@Composable
internal fun DetailRoute(key: String, enabled: Boolean, content: @Composable () -> Unit) {
    val tag = Modifier.testTag("detail:$key")
    if (!enabled) {
        Box(Modifier.fillMaxSize().then(tag)) { content() }
        return
    }
    val origins = LocalRouteOrigins.current
    val link = origins?.links?.get(key)
    val density = LocalDensity.current
    val front = LocalFrontEntry.current
    val visibility = LocalNavAnimatedContentScope.current
    val sampled = visibility.routeOpen(front)
    val returning = visibility.transition.currentState == EnterExitState.Visible
    val moving = front && visibility.transition.currentState != visibility.transition.targetState
    val fill = MaterialTheme.colorScheme.surface
    val direction = LocalLayoutDirection.current
    if (link == null) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            if (moving) {
                translationX = ((1f - sampled.value) * size.width) * if (direction == LayoutDirection.Ltr) 1f else -1f
                clip = true
            }
        }.then(tag).wallpaperBackground(fill)) { content() }
        return
    }
    val row by remember(link, origins, density) {
        derivedStateOf {
            if (link.density != density) null
            else (origins.source(link) as? RowAnchor)?.takeIf { it.visible() }
        }
    }
    val typography = MaterialTheme.typography
    val compactScale = with(density) { typography.bodyLarge.fontSize.toPx() / typography.titleLarge.fontSize.toPx() }

    // Both Nav3 scenes use the NavDisplay plane. The backing, header and footer stay
    // at their final coordinates; only the aperture and the list's display list move.
    // A detached/recycled or partly covered row is not a landing, even on a held seek.
    val departure = remember(link, moving, returning) { EdgeDeparture() }
    val lastBody = remember(departure) { BodyPose() }
    val hostSize = origins.size
    val end = remember(hostSize) { Rect(0f, 0f, hostSize.width.toFloat(), hostSize.height.toFloat()) }
    val window by remember(link, origins, moving, sampled, density, departure, direction, end) {
        derivedStateOf {
            if (!moving) null else {
                val t = sampled.value
                if (end.isEmpty) return@derivedStateOf null
                val start = if (!departure.detached && origins.live(link)) row?.bounds else null
                if (start != null) {
                    val rect = androidx.compose.ui.geometry.lerp(start, end, t)
                    val corner = link.radius * (1f - t)
                    departure.placed(rect, corner, t)
                    CardWindow(rect, corner)
                } else {
                    val edge = departure.depart(end, end.width, direction, t)
                    CardWindow(edge.rect, edge.radius, edge)
                }
            }
        }
    }
    var plane by remember(key) { mutableStateOf<LayoutCoordinates?>(null, referentialEqualityPolicy()) }
    var bodyOrigin by remember(key) { mutableStateOf(Offset.Unspecified) }
    var titleOffset by remember(key) { mutableStateOf(Offset.Unspecified) }
    val body = Modifier.layout { measurable, constraints ->
        val child = measurable.measure(constraints)
        val actual = !isLookingAhead
        if (actual) {
            val x = child[TitleX]
            val y = child[TitleY]
            titleOffset = if (x != AlignmentLine.Unspecified && y != AlignmentLine.Unspecified)
                Offset(x.toFloat(), y.toFloat()) else Offset.Unspecified
        }
        layout(child.width, child.height) {
            if (actual) {
                val c = coordinates
                val root = plane
                if (c != null && root?.isAttached == true) bodyOrigin = root.localPositionOf(c, Offset.Zero)
            }
            child.place(0, 0)
        }
    }.graphicsLayer {
        val aperture = window
        val edge = aperture?.edge
        val anchor = if (aperture != null && edge == null) row else null
        val start = anchor?.bounds
        val title = titleOffset
        val origin = bodyOrigin
        if (anchor != null && start != null && title.isSpecified && origin.isSpecified &&
            anchor.x != AlignmentLine.Unspecified && anchor.y != AlignmentLine.Unspecified) {
            val t = sampled.value
            val scale = lerp(compactScale, 1f, t)
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = scale
            scaleY = scale
            translationX = lerp(start.left + anchor.x - origin.x, title.x, t) - title.x * scale
            translationY = lerp(start.top + anchor.y - origin.y, title.y, t) - title.y * scale
            lastBody.scale = scale
            lastBody.x = translationX
            lastBody.y = translationY
        } else if (edge != null && edge.retained > 0f) {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = lerp(1f, lastBody.scale, edge.retained)
            scaleY = scaleX
            translationX = lastBody.x * edge.retained
            translationY = lastBody.y * edge.retained
        }
    }
    // The outer layer is the actual hit/semantics window. Its child undoes ONLY
    // this transform, so wallpaper/glass still sample the stationary host plane.
    // Neither layer reads a frame in measure or placement, in either direction.
    val aperture = Modifier.fillMaxSize().graphicsLayer {
        val frame = window?.rect
        if (frame != null) {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = frame.width / size.width
            scaleY = frame.height / size.height
            translationX = frame.left + (window?.edge?.dx ?: 0f)
            translationY = frame.top
            clip = true
        }
        alpha = if (moving && (frame == null || sampled.value <= 0f)) 0f else 1f
    }.then(tag).graphicsLayer {
        val aperture = window
        if (aperture != null) {
            val frame = aperture.rect
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = size.width / frame.width
            scaleY = size.height / frame.height
            translationX = -frame.left * scaleX
            translationY = -frame.top * scaleY
            shape = aperture
            clip = true
        }
    }
        .onPlaced { plane = it }
        .sourceHandoff({ if (window?.let { it.edge == null } == true) containerInk(sampled.value, returning) else 1f }) { row?.bounds }
        .wallpaperBackground(fill)
    Box(aperture) { CompositionLocalProvider(LocalDetailBody provides body, content = content) }
}
