// SPDX-License-Identifier: GPL-3.0-only
// Adapted from jay3-yy/BiliPai c7f835b34d8323358be57be298f86f29fecb53c8:
// feature/settings/SettingsContainerSource.kt, navigation3/{SettingsContainerTransition,
// SettingsContainerMotionPolicy,SettingsSharedContainerTransition}.kt. StackLife binds a live
// queue row, excludes list chrome, and substitutes an opaque parent-edge exit for a lost source.
package io.github.nakanomk.stacklife.transitions

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.appearance.LocalWallpaperRecording
import io.github.nakanomk.stacklife.appearance.PlannerBackdrop
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavRole
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope
import kotlin.math.roundToInt
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.LocalPlannerGlassAllowed
import io.github.nakanomk.stacklife.appearance.glassUnavailableReason

internal class MotionCardSources {
    var host: LayoutCoordinates? = null
        set(value) {
            field = value
            active?.row?.locate(value)
        }

    private val rows = mutableListOf<CardRow>()
    private var active by mutableStateOf<CardFlight?>(null)

    @Composable
    fun card(key: String): Modifier {
        val list = LocalCardList.current ?: return Modifier
        if (!LocalMotionEnabled.current || !key.startsWith("task:")) return Modifier
        val layer = rememberGraphicsLayer()
        val density = LocalDensity.current
        val shape = MaterialTheme.shapes.large
        val color = rememberUpdatedState(MaterialTheme.colorScheme.surfaceContainerLow)
        val wallpaper = rememberUpdatedState(LocalWallpaperRecording.current)
        val row = remember(list, key, density, shape, layer) {
            CardRow(key, list, layer, shape, density, color, wallpaper)
        }
        DisposableEffect(this, row) {
            rows.add(row)
            onDispose {
                rows.remove(row)
                row.coordinates = null
                row.bounds = null
                if (active?.row === row) active = null
            }
        }
        return remember(this, row) {
            // The retained page's display list changes only when ownership changes or its actual
            // content changes. It does not re-record the row or the list for each navigation tick.
            val hidden by derivedStateOf(structuralEqualityPolicy()) {
                active?.let { it.row === row && it.hidesRow() } == true
            }
            Modifier.onGloballyPositioned {
                row.coordinates = it
                if (active?.row === row) row.locate(host)
            }.drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                if (row.recordedSize != layer.size) row.recordedSize = layer.size
                if (!hidden) drawLayer(layer)
            }
        }
    }

    /** The loaded detail can observe a committed ownership change before the covered queue. */
    fun invalidate(key: String) {
        if (active?.row?.key == key) active = null
    }

    fun capture(key: String, pageColor: () -> Color): NavTransition? {
        val plane = host
        val row = if (plane == null || !plane.isAttached) null else rows.singleOrNull {
            it.key == key && it.locate(plane) != null && it.complete()
        }
        active = if (row == null || plane == null) null else CardFlight(row, plane, pageColor)
        return active
    }

    private inner class CardFlight(
        val row: CardRow,
        private val owner: LayoutCoordinates,
        private val pageColor: () -> Color,
    ) : NavTransition {
        private val capturedHostSize = owner.size
        private val capturedRowSize = row.recordedSize
        private val radius = row.radius()
        private val window = MotionCardWindow()
        private var opened by mutableStateOf(false)
        private var foreground by mutableStateOf<State<NavTransitionScope>?>(null)

        override val handlesOwnClipping = true
        override val preparesEnterFrame = true
        override val retainsSpringOvershoot = true

        // BiliPai's modal depth keeps exactly the parent STARTED and drawable, but not
        // interactive. Its current rows must be ready before a back gesture reveals them.
        override val opaqueDepth get() = 2f
        override val motion get() = if (opened) ReturnMotion else EnterMotion
        override fun scrimFraction(scope: NavTransitionScope) = 0f

        private fun live(scope: NavTransitionScope): Rect? {
            if (active !== this || host !== owner || !owner.isAttached || owner.size != capturedHostSize ||
                scope.layoutSize != capturedHostSize || scope.density != row.density ||
                row.recordedSize != capturedRowSize || !row.complete()) return null
            return row.bounds
        }

        fun hidesRow(): Boolean {
            val scope = foreground?.value ?: return false
            val d = scope.relativeDepth
            return d < 0f && d != -1f && !window.detached && live(scope) != null
        }

        override fun Modifier.transformEntry(scope: NavTransitionScope): Modifier = composed {
            // Parent and child receive the same boundary transition. Subscribe composition to this
            // threshold only, including the driver's explicit covered role during spring overshoot.
            val covered by remember(scope) { derivedStateOf(structuralEqualityPolicy()) {
                scope.role == NavRole.Covered || scope.relativeDepth > 0f
            } }
            if (covered || !LocalMotionEnabled.current) {
                val appearance = LocalAppearance.current
                if (!covered || !LocalPlannerGlassAllowed.current || !appearance.glass || glassUnavailableReason(appearance) != null)
                    return@composed Modifier.graphicsLayer()
                // Cull only pixels known to be covered by the opaque task page. Otherwise HWUI
                // runs both pages' glass passes during the near-full-screen part of the flight.
                // Keep the curved corners and a physical pixel along every edge live.
                return@composed remember(this@CardFlight) { Modifier.graphicsLayer().drawWithContent {
                    val top = foreground?.value
                    if (top == null || top.relativeDepth == -1f) { drawContent(); return@drawWithContent }
                    val w = sample(top)
                    val inset = w.corner + 1f
                    if (w.width <= 2f * inset || w.height <= 2f) { drawContent(); return@drawWithContent }
                    val canvas = drawContext.canvas.nativeCanvas
                    val restore = canvas.save()
                    try { if (canvas.clipOutRect(w.left + inset, w.top + 1f, w.left + w.width - inset, w.top + w.height - 1f)) drawContent() }
                    finally { canvas.restoreToCount(restore) }
                } }
            }
            val current = rememberUpdatedState(scope)
            DisposableEffect(this@CardFlight) {
                foreground = current
                onDispose {
                    if (foreground === current) foreground = null
                    if (current.value.isRemoving && active === this@CardFlight) active = null
                }
            }
            remember(this@CardFlight, current) {
                val drawing = CardDrawing()
                Modifier.graphicsLayer {
                    val s = current.value
                    val w = sample(s)
                    if (size.width > 0f && size.height > 0f && w.width > 0f && w.height > 0f) {
                        transformOrigin = TopLeft
                        translationX = w.left
                        translationY = w.top
                        scaleX = w.width / size.width
                        scaleY = w.height / size.height
                        clip = true
                    }
                }.graphicsLayer {
                    val w = sample(current.value)
                    if (w.width > 0f && w.height > 0f) {
                        transformOrigin = TopLeft
                        scaleX = size.width / w.width
                        scaleY = size.height / w.height
                    }
                }.drawWithContent {
                    val s = current.value
                    val w = sample(s)
                    if (s.relativeDepth == 0f) {
                        drawContent()
                    } else if (!w.grounded && w.width > 0f && w.height > 0f) {
                        with(drawing) { drawWindow(w, row, host, pageColor().copy(alpha = 1f)) }
                    }
                }.graphicsLayer() // The child is laid out once at its final page size; ink stays live.
            }
        }

        private fun sample(scope: NavTransitionScope): MotionCardWindow {
            val depth = scope.relativeDepth
            if (depth == 0f && !scope.isRemoving) opened = true
            window.place(scope.layoutSize, scope.layoutDirection, depth, live(scope), radius, opened)
            return window
        }
    }
}

private val TopLeft = TransformOrigin(0f, 0f)
private val Landing = NavSettleSpec.Spring(dampingRatio = .82f, stiffness = 500f,
    clampOvershoot = false, visibilityThreshold = .0001f)
private val Cancel = NavSettleSpec.Spring(dampingRatio = 1f, stiffness = 700f)
// M1Standard (0.4,0,0.2,1) is applied by the driver, not reapplied to its sampled depth. This
// keeps a partial push interrupted by a spring or a predictive gesture on the same geometry.
private val EnterMotion = NavMotion(programmatic = NavSettleSpec.Tween(300, FastOutSlowInEasing),
    commit = Landing, cancel = Cancel)
private val ReturnMotion = NavMotion(programmatic = Landing, commit = Landing, cancel = Cancel)

private class CardRow(val key: String, list: LazyListState, val layer: GraphicsLayer,
    private val shape: CornerBasedShape, val density: Density, val color: State<Color>,
    val wallpaper: State<PlannerBackdrop?>) {
    var coordinates: LayoutCoordinates? = null
    var bounds by mutableStateOf<Rect?>(null)
    var recordedSize by mutableStateOf(IntSize.Zero)
    private val id = key.substringAfter(':')
    private val visible by derivedStateOf(structuralEqualityPolicy()) {
        val layout = list.layoutInfo
        val items = layout.visibleItemsInfo
        var whole = false
        for (i in items.indices) {
            val item = items[i]
            if (item.key == id) {
                whole = item.offset >= layout.viewportStartOffset + layout.beforeContentPadding &&
                    item.offset + item.size <= layout.viewportEndOffset - layout.afterContentPadding
                break
            }
        }
        whole
    }

    fun locate(host: LayoutCoordinates?): Rect? = coordinates?.fullBoundsIn(host).also { bounds = it }
    fun complete(): Boolean {
        val node = coordinates ?: return false
        val rect = bounds ?: return false
        return node.isAttached && !layer.isReleased && wallpaper.value?.layer?.isReleased != true &&
            recordedSize.width > 1 && recordedSize.height > 1 &&
            recordedSize == node.size && recordedSize == layer.size &&
            kotlin.math.abs(rect.width - recordedSize.width) <= 1f &&
            kotlin.math.abs(rect.height - recordedSize.height) <= 1f && visible
    }
    fun radius(): Float = shape.topStart.toPx(recordedSize.toSize(), density).coerceAtMost(minOf(recordedSize.width, recordedSize.height) / 2f)
}

private class CardDrawing {
    private val path = Path()
    private val paint = Paint()

    fun ContentDrawScope.drawWindow(w: MotionCardWindow, row: CardRow, host: LayoutCoordinates?, pageColor: Color) {
        val canvas = drawContext.canvas.nativeCanvas
        val prior = canvas.save()
        try {
            // A gallery pane can be narrower than its window. Keep an orphan's travelling
            // pixels inside the original host, even when its parent doesn't clip children.
            if (w.detached && !canvas.clipRect(-w.left, -w.top, size.width - w.left, size.height - w.top)) return
            if (w.corner > 0f) {
                path.rewind()
                path.addRoundRect(0f, 0f, w.width, w.height, w.corner, w.corner, Path.Direction.CW)
                canvas.clipPath(path)
            }
            if (w.ink == 1f) {
                drawRect(pageColor)
                drawContent()
                return
            }
            val donor = row.wallpaper.value
            if (donor == null) drawRect(row.color.value) else translate(-w.left, -w.top) {
                with(donor) { drawBackdrop(this@drawWindow, host, null, 1f) }
            }
            val factor = w.width / row.layer.size.width
            scale(factor, Offset.Zero) { drawLayer(row.layer) }
            if (w.ink > 0f) {
                // Both endpoints are opaque here. Blend the destination group over the source
                // once, within the real aperture (never save a fullscreen translucent page).
                paint.alpha = (w.ink * 255f).roundToInt()
                val layer = canvas.saveLayer(0f, 0f, w.width, w.height, paint)
                drawRect(pageColor)
                drawContent()
                canvas.restoreToCount(layer)
            }
        } finally {
            canvas.restoreToCount(prior)
        }
    }
}
