package io.github.nakanomk.stacklife.transitions

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
import io.github.nakanomk.stacklife.appearance.wallpaperBackground
import io.github.nakanomk.stacklife.workspace.SharedModifier

// Entity bounds and title layout use the same Nav3 timeline as ordinary containers.
// Deterministic BiliPai shared container: the actual source tile flies inside that aperture.
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

/** A covered list can observe a Room commit after the open detail already lost its source. */
@Composable internal fun DetailSourceAvailable(available: Boolean) {
    val owner = LocalRouteOwner.current ?: return
    val link = LocalRouteOrigins.current?.links?.get(owner) ?: return
    SideEffect { link.entityPresent = available }
}

private class CardWindow(val painted: PaintedContainer) : Shape {
    val rect = painted.rect
    val edge = painted.takeIf { it.source == null }
    private val outline = Outline.Rounded(RoundRect(rect, CornerRadius(painted.radius.coerceIn(0f, rect.minDimension / 2))))
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
    val origins = checkNotNull(LocalRouteOrigins.current)
    val link = origins.links[key]
    val progress = rememberContainerProgress()
    val sampled = progress.open
    val moving = progress.moving
    val fill = MaterialTheme.colorScheme.surface
    if (link == null) {
        RoutePage(origins, null, null, progress, tag, content)
        return
    }
    val row = origins.source(link) as? RowAnchor
    val typography = MaterialTheme.typography
    val compactScale = with(LocalDensity.current) { typography.bodyLarge.fontSize.toPx() / typography.titleLarge.fontSize.toPx() }

    // Chrome stays in its final slot while the title and aperture share the upstream driver.
    val placement = placedContainer(origins, link, row, progress, fixedPlane = true)
    val window by remember(placement) { derivedStateOf { placement.value?.let(::CardWindow) } }
    val lastBody = remember(link, moving) { BodyPose() }
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
        alpha = if (moving && frame == null) 0f else 1f
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
    ContainerMaterials(origins, row, placement, progress) { paper ->
        Box(aperture.sourceCover(origins, row, placement, progress, paper, fixedPlane = true)
            .wallpaperBackground(fill, paper)) { CompositionLocalProvider(LocalDetailBody provides body, content = content) }
    }
}
