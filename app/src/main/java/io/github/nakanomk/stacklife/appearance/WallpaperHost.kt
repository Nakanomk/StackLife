package io.github.nakanomk.stacklife.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalContext
import io.github.nakanomk.stacklife.StackLifeApplication
import io.github.nakanomk.stacklife.planner.WallpaperSpec
import top.yukonga.miuix.kmp.blur.Backdrop

internal val LocalBackgroundStore = compositionLocalWithComputedDefaultOf {
    (LocalContext.currentValue.applicationContext as StackLifeApplication).backgrounds
}

// Adapted from BiliPai 5893c2c, design-system/.../wallpaper/GlobalWallpaperBackdrop.kt (GPL-3.0).
internal val LocalWallpaperRecording = compositionLocalOf<PlannerBackdrop?> { null }
private val LocalWallpaperSpec = compositionLocalOf<WallpaperSpec?> { null }

@Composable
internal fun WallpaperHost(spec: WallpaperSpec, drawBehindContent: Boolean = true, content: @Composable () -> Unit) {
    val inherited = !drawBehindContent && LocalWallpaperSpec.current == spec
    val visible = !inherited && spec.hasImage && !wallpaperUsesSolidSurface()
    val layer = if (visible) rememberGraphicsLayer() else null
    if (layer != null) {
        // A navigation plane serves the page, chrome and cards. Rasterize its static crop and
        // protection once instead of resampling the original photo in every receiver's shaders.
        // The layer contains wallpaper only; edits invalidate it, and previews stay lightweight.
        val cache = !drawBehindContent && LocalPlannerGlassAllowed.current && glassUnavailableReason(LocalAppearance.current) == null
        SideEffect { layer.compositingStrategy = if (cache) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
    }
    val base = MaterialTheme.colorScheme.surface
    val recording = remember(layer, base) { layer?.let { PlannerBackdrop(it, base, null, null) } }
    val donor = if (inherited) LocalWallpaperRecording.current else recording
    // A preview starts a new wallpaper plane, even when embedded in a page with a glass donor.
    // Transparent parts of a plain preview must not show the enclosing page's photo.
    // A navigation entry only supplies material. Painting this full-window sibling would
    // cover the parent outside the animated destination, before its own clip or alpha runs.
    Box(if (drawBehindContent && recording == null) Modifier.background(base) else Modifier, propagateMinConstraints = true) {
        if (recording != null) WallpaperPixels(spec, base, MaterialTheme.colorScheme.onSurface, card = false,
            modifier = Modifier.matchParentSize(), record = Modifier.plannerBackdrop(recording, drawInPlace = drawBehindContent),
            reportFailure = false)
        // Page chrome can still record scroll content over this donor. Never capture that content here.
        // Changing crop or owner must not move the editor/navigation subtree to another call site.
        CompositionLocalProvider(LocalWallpaperSpec provides spec, LocalWallpaperRecording provides donor,
            LocalCapsuleBackdrop provides donor, content = content)
    }
}

/** A genuinely uniform donor, not a snapshot of content that happens to look empty. */
internal class UniformPagePaper(private val color: Color) : Backdrop {
    override val isCoordinatesDependent = false
    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?, downscaleFactor: Float) = drawRect(color)
}

/** A plain page needs no window-sized recording and has no position to invalidate
 * when a tab moves. A photo still samples the actual window-aligned wallpaper. */
@Composable
internal fun rememberPagePaper(color: Color): Backdrop? {
    val a = LocalAppearance.current
    if (!LocalPlannerGlassAllowed.current || !a.glass || glassUnavailableReason(a) != null) return null
    return LocalWallpaperRecording.current ?: remember(color) { UniformPagePaper(color) }
}

@Composable
internal fun Modifier.wallpaperBackground(fallback: Color, source: Backdrop? = LocalWallpaperRecording.current): Modifier {
    if (source == null) return background(fallback)
    return then(WallpaperReplay(source))
}

private data class WallpaperReplay(val source: Backdrop) : ModifierNodeElement<ReplayNode>() {
    override fun create() = ReplayNode(source)
    override fun update(node: ReplayNode) { node.source = source }
    override fun InspectorInfo.inspectableProperties() { name = "wallpaperBackground" }
}

private class ReplayNode(var source: Backdrop) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var receiver: LayoutCoordinates? = null
    private var screen = Offset.Unspecified
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val position = coordinates.localToScreen(Offset.Zero)
        if (receiver !== coordinates || screen != position) {
            receiver = coordinates; screen = position
            invalidateDraw()
        }
    }
    override fun onDetach() { receiver = null; screen = Offset.Unspecified }
    override fun ContentDrawScope.draw() {
        // Direct drawing works on API 26–32 too; no API-33-only Miuix effect modifier on this path.
        clipRect { with(source) { drawBackdrop(this@draw, receiver, null, 1f) } }
        drawContent()
    }
}
