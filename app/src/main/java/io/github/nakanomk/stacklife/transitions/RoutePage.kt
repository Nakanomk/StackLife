package io.github.nakanomk.stacklife.transitions

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.nakanomk.stacklife.appearance.LocalPanelBack
import io.github.nakanomk.stacklife.appearance.wallpaperBackground

/** Clip in native pixels after undoing only the outer bounds scale. */
private class RouteOutline(frame: PaintedContainer) : Shape {
    private val outline = Outline.Rounded(RoundRect(Rect(0f, 0f, frame.rect.width, frame.rect.height), CornerRadius(frame.radius)))
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) = outline
}

/** The outer layer gives hit-testing and semantics the moving window. Undo only its scale
 * in the child: ordinary pages travel with the window's origin, unlike fixed card chrome.
 * The child's native round-rect outline is therefore in real pixels, including its corners. */
@Composable
internal fun RoutePage(origins: ContainerOrigins, link: ContainerLink?, source: ContainerAnchor?, progress: ContainerProgress?,
    tag: Modifier, content: @Composable () -> Unit) {
    val placement = placedContainer(origins, link, source, progress, fixedPlane = false)
    val window by placement
    val outline by remember(placement) { derivedStateOf { placement.value?.let(::RouteOutline) } }
    val pane = origins is PaneOrigins
    val sheet = pane && LocalPanelBack.current != null
    val colors = MaterialTheme.colorScheme
    val paper = if (sheet) colors.surfaceContainerHigh else colors.surface
    ContainerMaterials(origins, source, placement, progress) { material ->
        val background = if (pane && progress?.moving != true) Modifier else Modifier.wallpaperBackground(paper, material)
        Box(Modifier.graphicsLayer {
            val w = window
            val rect = w?.rect
            if (rect != null && !rect.isEmpty && size.width > 0f && size.height > 0f) {
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = rect.left + w.dx; translationY = rect.top
                scaleX = rect.width / size.width; scaleY = rect.height / size.height
                clip = true
            }
        }.then(tag).graphicsLayer {
            val rect = window?.rect
            if (rect != null && !rect.isEmpty) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = size.width / rect.width; scaleY = size.height / rect.height
                outline?.let { shape = it }
                clip = true
            }
        }.sourceCover(origins, source, placement, progress, material, fixedPlane = false)
            .then(background), propagateMinConstraints = true) { content() }
    }
}
