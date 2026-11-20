package io.github.nakanomk.stacklife.transitions

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Rect
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutBoundsHolder
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layoutBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.roundToIntRect
import androidx.lifecycle.Lifecycle

internal class KitListSource(
    val app: KitApplication, val activity: Activity, val lifecycle: Lifecycle,
    val scroll: LazyListState, val configuration: Configuration,
) : DetailListSource {
    val viewport = LayoutBoundsHolder()
    override val modifier = Modifier.layoutBounds(viewport)
    var clearancePx = 0
    var revision = -1L
    var opening: Any? = null

    @Composable
    override fun card(target: DetailTarget, shape: Shape, fallback: () -> Unit): DetailCardLink {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val source = remember(this, target, shape, density, direction) {
            KitCardSource(this, SourceKey(activity.taskId, target), shape, density, direction)
        }
        DisposableEffect(source) {
            app.sources[source.key] = source
            onDispose { app.sources.remove(source.key, source) }
        }
        return remember(source, fallback) { DetailCardLink(source.modifier) { app.open(source, fallback) } }
    }
}

internal data class SourceKey(val androidTask: Int, val target: DetailTarget)
internal data class CardGeometry(val window: Rect, val screen: Rect, val radius: Float, val revision: Long)

internal class KitCardSource(
    val list: KitListSource, val key: SourceKey,
    private val shape: Shape, private val density: Density, private val direction: LayoutDirection,
) {
    private val holder = LayoutBoundsHolder()
    private var coordinates: LayoutCoordinates? = null
    val modifier = Modifier.layoutBounds(holder).onGloballyPositioned { coordinates = it }

    /** Re-read the node, never a cached click position. A stopped source activity is expected on BACK. */
    fun geometry(): CardGeometry? {
        val activity = list.activity
        if (activity.isDestroyed || activity.isFinishing || activity.isChangingConfigurations ||
            activity.window.decorView.isLayoutRequested || list.scroll.isScrollInProgress ||
            list.scroll.layoutInfo.visibleItemsInfo.none { it.key == key.target.id }) return null
        val coords = coordinates?.takeIf { it.isAttached } ?: return null
        val card = holder.bounds ?: return null
        val viewport = list.viewport.bounds?.boundsInWindow ?: return null
        val bounds = card.boundsInWindow
        if (bounds.width <= 0 || bounds.height <= 0 || bounds.width != coords.size.width || bounds.height != coords.size.height ||
            bounds != coords.boundsInWindow().roundToIntRect() || card.fractionVisibleInWindow() < 1f ||
            bounds.left < viewport.left || bounds.right > viewport.right || bounds.top < viewport.top ||
            bounds.bottom > viewport.bottom - list.clearancePx || card.calculateOcclusions().isNotEmpty()) return null
        val size = Size(bounds.width.toFloat(), bounds.height.toFloat())
        val radius = when (val outline = shape.createOutline(size, direction, density)) {
            is Outline.Rectangle -> 0f
            is Outline.Rounded -> {
                val rect = outline.roundRect
                val r = rect.topLeftCornerRadius
                if (r.x != r.y || r != rect.topRightCornerRadius || r != rect.bottomLeftCornerRadius ||
                    r != rect.bottomRightCornerRadius) return null
                r.x
            }
            is Outline.Generic -> return null // The bitmap API only describes a single circular radius.
        }
        return CardGeometry(bounds.androidRect(), card.boundsInScreen.androidRect(), radius, list.revision)
    }
}

private fun IntRect.androidRect() = Rect(left, top, right, bottom)
