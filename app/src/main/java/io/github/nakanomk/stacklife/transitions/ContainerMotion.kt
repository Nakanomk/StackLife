// SPDX-License-Identifier: GPL-3.0-only
package io.github.nakanomk.stacklife.transitions

// BiliPai 3f719310d674d474b904a4389f8cc99ea76fd2aa: NavigationSharedContainerSource
// and the deterministic SettingsSharedContainerTransition. Only the launcher lends a layer.
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.util.fastRoundToInt
import top.yukonga.miuix.kmp.blur.Backdrop

/** A Popup/Dialog has a different coordinate root. Clipped or detached nodes have no landing. */
internal fun LayoutCoordinates.fullBoundsIn(host: LayoutCoordinates?): Rect? {
    if (host == null || !host.isAttached || !isAttached || findRootCoordinates() !== host.findRootCoordinates()) return null
    val whole = host.localBoundingBoxOf(this, clipBounds = false)
    if (whole.width <= 1f || whole.height <= 1f || whole.left < -1f || whole.top < -1f ||
        whole.right > host.size.width + 1f || whole.bottom > host.size.height + 1f) return null
    if (whole.width >= host.size.width - 1f && whole.height >= host.size.height - 1f) return null
    val visible = boundsInWindow()
    val uncut = boundsInWindow(clipBounds = false)
    return whole.takeIf { visible.width + 1f >= uncut.width && visible.height + 1f >= uncut.height }
}

internal data class OriginSite(val owner: String?, val target: String, val site: Long)
internal open class ContainerAnchor(val site: OriginSite, val shape: Shape, val density: Density, val rowVisible: (() -> Boolean)? = null) {
    var flight by mutableStateOf<SourceTile?>(null)
    var coordinates by mutableStateOf<LayoutCoordinates?>(null, referentialEqualityPolicy())
    var bounds by mutableStateOf<Rect?>(null)
        private set
    fun place(node: LayoutCoordinates, host: LayoutCoordinates?) { coordinates = node; locate(host) }
    fun locate(host: LayoutCoordinates?): Rect? = coordinates?.fullBoundsIn(host).also { bounds = it }
    fun radius(box: Rect) = (shape as? CornerBasedShape)?.topStart?.toPx(box.size, density)?.coerceIn(0f, box.minDimension / 2) ?: 0f
}

/** Remembers identity and host metrics, never a rectangle to animate back to. */
internal class ContainerLink(val site: OriginSite, val hostSize: IntSize, val density: Density) {
    var entityPresent by mutableStateOf(true)
}

internal open class ContainerOrigins {
    private val anchors = mutableStateMapOf<OriginSite, ContainerAnchor>()
    val links = mutableStateMapOf<String, ContainerLink>()
    var host: LayoutCoordinates? = null
    var size by mutableStateOf(IntSize.Zero)
    var viewportSize by mutableStateOf(IntSize.Zero)
    fun attach(anchor: ContainerAnchor) { anchors[anchor.site] = anchor }
    fun detach(anchor: ContainerAnchor) { anchors.remove(anchor.site, anchor) }
    fun source(site: OriginSite) = anchors[site]
    protected open fun tracks(site: OriginSite) = links[site.target]?.site == site
    fun place(anchor: ContainerAnchor, node: LayoutCoordinates) {
        // Only the captured launcher needs live clipped geometry. Keep the other
        // buttons' coordinates so a later click can resolve them at that instant.
        anchor.place(node, if (tracks(anchor.site)) host else null)
    }
    fun capture(target: String, owner: String?, chosen: OriginSite? = null) {
        val a = if (chosen != null) anchors[chosen]?.takeIf { chosen.owner == owner && chosen.target == target && it.locate(host) != null }
            else anchors.values.singleOrNull { it.site.owner == owner && it.site.target == target && it.locate(host) != null }
        val bounds = a?.bounds
        if (bounds == null || a.rowVisible?.invoke() == false) links.remove(target)
        else links[target] = ContainerLink(a.site, size, a.density)
    }
    fun source(link: ContainerLink) = anchors[link.site]
}

@Composable
internal fun Modifier.containerSource(origins: ContainerOrigins, anchor: ContainerAnchor): Modifier {
    DisposableEffect(origins, anchor) { origins.attach(anchor); onDispose { origins.detach(anchor) } }
    val tile = anchor.flight
    if (tile != null) {
        val paper = LocalEntryPaper.current ?: containerPaper(origins)
        val underlay = LocalContainerUnderlay.current
        SideEffect { tile.paper = paper; tile.underlay = underlay }
    }
    return onGloballyPositioned { origins.place(anchor, it) }.graphicsLayer().drawWithContent {
        if (tile == null || anchor.bounds == null || size.width <= 1f ||
            size.height <= 1f || tile.ink.isReleased) {
            drawContent()
        } else {
            tile.ink.record { this@drawWithContent.drawContent() }
            // Keep the real row's display list on the live draw path. The destination covers
            // it during flight and borrows this same list without mutating its opacity.
            drawLayer(tile.ink)
            tile.recorded = tile.ink.size
        }
    }
}

internal class SourceTile(val ink: GraphicsLayer) {
    var paper by mutableStateOf<ContainerPaper?>(null)
    var underlay by mutableStateOf<Backdrop?>(null)
    var recorded by mutableStateOf(IntSize.Zero)
}

/** Only the matching pair owns a cover. The measured page stays live and opaque; source ink
 * scales uniformly to the aperture width rather than stretching its text to the page height. */
@Composable
internal fun Modifier.sourceCover(origins: ContainerOrigins, source: ContainerAnchor?, placement: State<PaintedContainer?>,
    progress: ContainerProgress?, paper: Backdrop?, fixedPlane: Boolean): Modifier {
    if (source == null || progress == null) return this
    // Bind to the source/destination pair, not to an individual motion. A retained
    // list need not draw again when predictive Back starts; discarding the successful
    // opening recording at rest left the return with geometry but no source pixels.
    val ink = rememberGraphicsLayer()
    val tile = remember(source, ink) { SourceTile(ink) }
    DisposableEffect(source, tile) {
        source.flight = tile
        onDispose { if (source.flight === tile) source.flight = null }
    }
    if (!progress.moving) return this
    return drawWithContent {
        val frame = placement.value
        if (frame?.source == null) {
            drawContent()
            return@drawWithContent
        }
        val open = progress.open.value
        // Prepared first/last frame: only the real launcher draws. A first click may
        // precede its first recording; never cover it with an empty tile or stale pixels.
        val ground = tile.paper
        val ready = source.flight === tile && tile.recorded.width > 1 &&
            tile.recorded == source.coordinates?.size && !ink.isReleased
        if (open <= .001f) return@drawWithContent
        val box = frame.rect
        val left = if (fixedPlane) box.left else 0f
        val top = if (fixedPlane) box.top else 0f
        if (!ready || ground == null) {
            // No source display list yet. Leave a shrinking hole over the real launcher
            // rather than revealing an empty shell, fading a whole page or blocking navigation.
            val until = if (progress.returning) .08f else .14f
            if (open < until) {
                val grounded = frame.source
                val remaining = 1f - open / until
                val cx = left + grounded.center.x - box.left
                val cy = top + grounded.center.y - box.top
                val rx = grounded.width * .5f * remaining
                val ry = grounded.height * .5f * remaining
                clipRect(cx - rx, cy - ry, cx + rx, cy + ry, ClipOp.Difference) { this@drawWithContent.drawContent() }
                return@drawWithContent
            }
            drawContent()
            return@drawWithContent
        }
        if (frame.ink >= 1f) {
            drawContent()
            return@drawWithContent
        }
        // The environment follows the whole gesture. Only the tile's own material and
        // glyphs use the shorter directional ink handoff inside the moving aperture.
        val canvas = drawContext.canvas.nativeCanvas
        val save = if (frame.ink > 0f) {
            drawContent()
            canvas.saveLayerAlpha(left, top, left + box.width, top + box.height,
                ((1f - frame.ink) * 255f).fastRoundToInt())
        } else -1
        try {
            clipRect(left, top, left + box.width, top + box.height) {
                // Near landing, reveal the actual retained page through the surplus
                // area around the tile. A shrinking slab of bare wallpaper otherwise
                // covers a parent form/card until disposal. Only the environment fades;
                // the launcher below is composed once, opaque, from its own underlay.
                val t = ((open - .001f) / (.12f - .001f)).coerceIn(0f, 1f)
                val alpha = (255f * t * t * (3f - 2f * t)).fastRoundToInt()
                if (alpha > 0) {
                    val base = if (alpha < 255) canvas.saveLayerAlpha(left, top, left + box.width,
                        top + box.height, alpha) else -1
                    try {
                        withTransform({ translate(left - box.left, top - box.top) }) {
                            with(paper ?: ground.paint) { drawBackdrop(this@drawWithContent, origins.host, null, 1f) }
                        }
                    } finally { if (base >= 0) canvas.restoreToCount(base) }
                }
                val zoom = box.width / tile.recorded.width
                withTransform({ translate(left, top); scale(zoom, zoom, Offset.Zero) }) {
                    // A transparent settings/pane row borrows the finished material of its
                    // actual parent card, sampled at the row's current (possibly scrolled)
                    // position. Never replay that card's labels, neighbouring rows or border.
                    clipRect(0f, 0f, tile.recorded.width.toFloat(), tile.recorded.height.toFloat()) {
                        with(tile.underlay ?: ground.paint) {
                            drawBackdrop(this@drawWithContent, source.coordinates, null, 1f)
                        }
                        drawLayer(ink)
                    }
                }
            }
        } finally { if (save >= 0) canvas.restoreToCount(save) }
    }.graphicsLayer()
}
