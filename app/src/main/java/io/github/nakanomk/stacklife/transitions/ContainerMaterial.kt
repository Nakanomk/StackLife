// SPDX-License-Identifier: GPL-3.0-only
// BiliPai 3f719310d674d474b904a4389f8cc99ea76fd2aa, SettingsSharedContainerTransition.kt:
// interpolate the container material independently of the directional source/content ink.
package io.github.nakanomk.stacklife.transitions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.util.fastRoundToInt
import io.github.nakanomk.stacklife.appearance.*
import top.yukonga.miuix.kmp.blur.Backdrop

/** Only the enclosing surface's finished material: no siblings, controls or page content. */
internal val LocalContainerUnderlay = compositionLocalOf<Backdrop?> { null }
// A route/pane owns its environment. A capsule nested inside its content may have
// another optical donor of its own; that donor is not the background of the pane.
internal val LocalEntryPaper = compositionLocalOf<ContainerPaper?> { null }

private class PaperFlight {
    var paper: ContainerPaper? = null
    var matched = false
}

internal data class ContainerPaper(val donor: Backdrop?, val fallback: Color) {
    val paint = donor ?: UniformPagePaper(fallback)
}

@Composable
internal fun containerPaper(origins: ContainerOrigins): ContainerPaper {
    val sheet = origins is PaneOrigins && LocalPanelBack.current != null
    val colors = MaterialTheme.colorScheme
    val fallback = if (sheet) colors.surfaceContainerHigh else colors.surface
    val donor = if (sheet) LocalCapsuleBackdrop.current else LocalWallpaperRecording.current
    return remember(donor, fallback) { ContainerPaper(donor, fallback) }
}

/** A different paper is mixed once in stationary host coordinates. Both the live page and
 * its chrome can replay it. It contains only the two material donors, never either screen.
 * The source tile (and any material that belongs to the tile itself) travels separately. */
@Composable
internal fun ContainerMaterials(origins: ContainerOrigins, source: ContainerAnchor?, placement: State<PaintedContainer?>,
    progress: ContainerProgress?, content: @Composable (Backdrop?) -> Unit) {
    val detail = containerPaper(origins)
    val flight = if (progress?.moving == true) remember(origins, progress) { PaperFlight() } else null
    val current = if (flight != null) source?.flight?.paper else null
    if (current != null && flight != null) SideEffect { flight.paper = current }
    val ground = current ?: flight?.paper
    val layer = if (ground != null && ground != detail) rememberGraphicsLayer() else null
    val mixed = remember(layer, detail) { layer?.let { PlannerBackdrop(it, detail.fallback, null, null) } }
    Box(Modifier.fillMaxSize(), propagateMinConstraints = true) {
        if (mixed != null && ground != null && progress != null && flight != null) {
            Spacer(Modifier.matchParentSize().plannerBackdrop(mixed, drawInPlace = false).drawBehind {
                // An orphan starts as the opaque detail. If a valid launcher disappears
                // during a seek, keep only its *page paper*: never retain its material,
                // glyphs or bounds. Reversing restores the detail along the same blend.
                if (placement.value?.source != null) flight.matched = true
                val live = (ground.donor as? PlannerBackdrop)?.layer?.isReleased != true
                val open = if (flight.matched && live) progress.open.value else 1f
                if (open < 1f) with(ground.paint) { drawBackdrop(this@drawBehind, origins.host, null, 1f) }
                if (open > 0f) {
                    val canvas = drawContext.canvas.nativeCanvas
                    val save = if (open < 1f) canvas.saveLayerAlpha(0f, 0f, size.width, size.height,
                        (open * 255f).fastRoundToInt()) else -1
                    try { with(detail.paint) { drawBackdrop(this@drawBehind, origins.host, null, 1f) } }
                    finally { if (save >= 0) canvas.restoreToCount(save) }
                }
            })
        }
        val wallpaper = LocalWallpaperRecording.current
        val capsule = LocalCapsuleBackdrop.current
        CompositionLocalProvider(LocalWallpaperRecording provides (mixed ?: wallpaper),
            LocalCapsuleBackdrop provides (mixed ?: capsule), LocalEntryPaper provides detail) { content(mixed ?: detail.donor) }
    }
}
