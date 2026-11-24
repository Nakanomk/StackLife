package io.github.nakanomk.stacklife.transitions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.LocalWallpaperRecording
import io.github.nakanomk.stacklife.appearance.glassUnavailableReason
import io.github.nakanomk.stacklife.appearance.wallpaperBackground
import kotlin.math.roundToInt

/** Both endpoints are in the NavDisplay plane. Place text on physical pixels just as a
 * placed layout would, but keep the page at its final constraints even on a held Back. */
private class RouteWindow(val rect: Rect, page: Rect, radius: Float, private val source: Rect?, dx: Float = 0f) : Shape {
    val width = rect.width
    val height = rect.height
    val x = rect.left - page.left + dx
    val y = rect.top - page.top
    private val corner = CornerRadius(radius)
    val anchored get() = source != null
    fun sourceTile() = source?.translate(-rect.left, -rect.top)

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(Rect(0f, 0f, width, height), corner))

}
private fun edgePixel(start: Float, end: Float, open: Float) = lerp(start, end, open).roundToInt().toFloat()

/** The outer layer gives hit-testing and semantics the moving window. Undo only its scale
 * in the child: ordinary pages travel with the window's origin, unlike fixed card chrome.
 * The child's native round-rect outline is therefore in real pixels, including its corners. */
@Composable
internal fun RoutePage(origins: RouteOrigins, link: ContainerLink?, source: ContainerAnchor?, open: State<Float>, returning: Boolean,
    moving: Boolean, tag: Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val departure = remember(origins, moving, returning) { EdgeDeparture() }
    // Every EntryWindow fills the actual NavDisplay plane. Reparenting the
    // covered source must not temporarily erase a retained page's end bounds.
    val hostSize = origins.size
    val page = remember(hostSize) {
        if (hostSize.width > 0 && hostSize.height > 0) Rect(0f, 0f, hostSize.width.toFloat(), hostSize.height.toFloat()) else null
    }
    val window by remember(link, source, open, moving, density, departure, direction, page) {
        derivedStateOf {
            val end = page ?: return@derivedStateOf null
            if (!moving) return@derivedStateOf null
            val t = open.value
            val start = source?.bounds?.takeIf { !departure.detached && source.density == density &&
                source.coordinates?.isAttached == true && source.rowVisible?.invoke() != false &&
                (link == null || link.hostSize == hostSize && link.density == density) }
            if (start != null && source != null) {
                val rect = Rect(edgePixel(start.left, end.left, t), edgePixel(start.top, end.top, t),
                    edgePixel(start.right, end.right, t), edgePixel(start.bottom, end.bottom, t))
                val corner = source.radius(start) * (1f - t)
                departure.placed(rect, corner, t)
                RouteWindow(rect, end, corner, start)
            } else {
                val edge = departure.depart(end, origins.size.width.toFloat(), direction, t)
                RouteWindow(edge.rect, end, edge.radius, null, edge.dx)
            }
        }
    }
    // The content moves rigidly behind the aperture. Keep its glass in a live
    // layer inside the clip instead of flushing its filtered children to the GPU
    // for each new outline. Leave screen-anchored photos and stationary scrolling
    // on the normal rendering path.
    val appearance = LocalAppearance.current
    val glassPage = if (link?.keepSource == true && appearance.glass && glassUnavailableReason(appearance) == null &&
        LocalWallpaperRecording.current == null) Modifier.graphicsLayer {
        compositingStrategy = if (moving) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    } else Modifier
    Box(Modifier.fillMaxSize(), propagateMinConstraints = true) {
        Box(Modifier.graphicsLayer {
            val frame = window
            if (frame != null) {
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = frame.x
                translationY = frame.y
                scaleX = frame.width / size.width
                scaleY = frame.height / size.height
                clip = true
            }
            // No placed destination yet: there is no window to paint. Once placed,
            // all coverage is geometric; only the live launcher tile can hand off ink.
            alpha = if (moving && (frame == null || open.value <= 0f)) 0f else 1f
        }.then(tag).graphicsLayer {
            val frame = window
            if (frame != null) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = size.width / frame.width
                scaleY = size.height / frame.height
                shape = frame
                clip = true
            }
        }.sourceHandoff({ if (window?.anchored == true) containerInk(open.value, returning) else 1f }) { window?.sourceTile() }
            .then(glassPage).wallpaperBackground(MaterialTheme.colorScheme.surface),
            propagateMinConstraints = true) { content() }
    }
}
