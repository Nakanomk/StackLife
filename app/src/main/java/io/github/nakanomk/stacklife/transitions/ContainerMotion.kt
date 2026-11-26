package io.github.nakanomk.stacklife.transitions

// BiliPai SettingsContainerSource and SettingsSharedContainerTransition: hand off the actual
// launcher's ink in place. No screenshot or copied page is stored.
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.shape.CornerBasedShape
import kotlin.math.ceil
import kotlin.math.floor

/** A Popup/Dialog has a different coordinate root. Clipped or detached nodes have no landing. */
internal fun LayoutCoordinates.fullBoundsIn(host: LayoutCoordinates?): Rect? {
    if (host == null || !host.isAttached || !isAttached || findRootCoordinates() !== host.findRootCoordinates()) return null
    val whole = host.localBoundingBoxOf(this, clipBounds = false)
    if (whole.width <= 1f || whole.height <= 1f || whole.left < -1f || whole.top < -1f ||
        whole.right > host.size.width + 1f || whole.bottom > host.size.height + 1f) return null
    val visible = boundsInWindow()
    val uncut = boundsInWindow(clipBounds = false)
    return whole.takeIf { visible.width + 1f >= uncut.width && visible.height + 1f >= uncut.height }
}

internal data class OriginSite(val owner: String?, val target: String, val site: Long)
internal open class ContainerAnchor(val site: OriginSite, val shape: Shape, val density: Density, val rowVisible: (() -> Boolean)? = null) {
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
    return onGloballyPositioned { origins.place(anchor, it) }.graphicsLayer()
}

/** The last layer holds live drawing commands, not a bitmap of the page. Keep group
 * opacity (including overlapping ink and glass) but isolate only the launcher's tile. */
internal fun Modifier.sourceHandoff(coverage: () -> Float, slot: () -> Rect?): Modifier = drawWithContent {
    val a = coverage()
    val overlap = if (a < 1f) slot()?.intersect(Rect(Offset.Zero, size)) else null
    when {
        a >= 1f -> drawContent()
        a <= 0f -> if (overlap == null || overlap.isEmpty) drawContent() else
            clipRect(overlap.left, overlap.top, overlap.right, overlap.bottom, ClipOp.Difference) { this@drawWithContent.drawContent() }
        // A fractional group previously had an implicit clip at the layer's own edges.
        overlap == null || overlap.isEmpty -> clipRect { this@drawWithContent.drawContent() }
        else -> clipRect {
            // Split on pixel edges; the original DstOut still supplies the fractional
            // edge inside this tile. Both clips replay the same live child display list.
            val l = floor(overlap.left)
            val t = floor(overlap.top)
            val r = ceil(overlap.right)
            val b = ceil(overlap.bottom)
            if (l > 0f || t > 0f || r < size.width || b < size.height)
                clipRect(l, t, r, b, ClipOp.Difference) { this@drawWithContent.drawContent() }
            clipRect(l, t, r, b) {
                val canvas = drawContext.canvas.nativeCanvas
                val save = canvas.saveLayer(l, t, r, b, null)
                try {
                    this@drawWithContent.drawContent()
                    drawRect(Color.White.copy(alpha = 1f - a), overlap.topLeft, overlap.size, blendMode = BlendMode.DstOut)
                } finally {
                    canvas.restoreToCount(save)
                }
            }
        }
    }
}.graphicsLayer()
