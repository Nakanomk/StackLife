// SPDX-License-Identifier: GPL-3.0-only
// BiliPai c7f835b34d8323358be57be298f86f29fecb53c8, navigation3/SettingsContainerMotionPolicy.kt:
// resolveSettingsContainerFrame, resolveSettingsContainerContentAlpha. The driver already applied
// its easing. Scalars avoid per-frame Rect/shape allocation; lost-source carry follows StackLife's
// EdgeDeparture, in the current parent viewport rather than in a remembered launch coordinate root.
package io.github.nakanomk.stacklife.transitions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

/** Private to a single entry capture. Only [detached] is observable by the grounded row. */
internal class MotionCardWindow {
    var left = 0f; private set
    var top = 0f; private set
    var width = 0f; private set
    var height = 0f; private set
    var corner = 0f; private set
    var ink = 1f; private set
    var grounded = false; private set
    var detached by mutableStateOf(false); private set

    private var heldLeft = 0f
    private var heldTop = 0f
    private var heldWidth = 0f
    private var heldHeight = 0f
    private var heldCorner = 0f
    private var heldOpen = 1f

    // Draw and both graphics layers independently subscribe to the driver. Compute the aperture
    // once for that sample, irrespective of which consumer the renderer invalidates first.
    private var lastDepth = Float.NaN
    private var lastSize = IntSize.Zero
    private var lastSource: Rect? = null
    private var lastDirection = LayoutDirection.Ltr
    private var lastReturning = false

    fun place(size: IntSize, direction: LayoutDirection, depth: Float, source: Rect?, radius: Float, returning: Boolean) {
        if (size == lastSize && direction == lastDirection && depth == lastDepth &&
            lastSource === source && returning == lastReturning) return
        if (size != lastSize) heldOpen = 1f // A held rect from the previous window is never a landing.
        lastSize = size
        lastDirection = direction
        lastDepth = depth
        lastSource = source
        lastReturning = returning
        val pageW = size.width.toFloat()
        val pageH = size.height.toFloat()
        val raw = 1f + depth
        val open = raw.coerceIn(0f, 1f)
        grounded = false
        if (open == 1f) {
            // A cancelled gesture has returned to the intact page. The next gesture may use
            // the original owner again, but only if its live coordinates and pixels validate.
            detached = false
            heldOpen = 1f
            left = 0f; top = 0f; width = pageW; height = pageH; corner = 0f; ink = 1f
        } else if (source != null && !detached) {
            val landingScale = if (returning && raw < 0f) (1f + raw).coerceAtLeast(.97f) else 1f
            val w = lerp(source.width, pageW, open)
            val h = lerp(source.height, pageH, open)
            width = w * landingScale
            height = h * landingScale
            left = (1f - open) * source.left + (w - width) / 2f
            top = (1f - open) * source.top + (h - height) / 2f
            corner = radius * (1f - open)
            val u = if (returning) ((open - .45f) / .30f).coerceIn(0f, 1f)
                else ((open - .22f) / .28f).coerceIn(0f, 1f)
            ink = u * u * (3f - 2f * u)
            grounded = depth == -1f
            heldLeft = left; heldTop = top; heldWidth = width; heldHeight = height
            heldCorner = corner; heldOpen = open
        } else {
            // Do not retarget a moving frame to a recycled or reappearing row. Only the most
            // recently painted window is carried; it no longer draws any source ink.
            if (open > 0f || heldOpen < 1f) detached = true
            val reopen = if (heldOpen >= 1f) 1f else ((open - heldOpen) / (1f - heldOpen)).coerceIn(0f, 1f)
            left = lerp(heldLeft, 0f, reopen)
            top = lerp(heldTop, 0f, reopen)
            width = lerp(heldWidth, pageW, reopen)
            height = lerp(heldHeight, pageH, reopen)
            corner = heldCorner * (1f - reopen)
            val travel = if (heldOpen <= 0f) { if (open == 0f) 1f else 0f }
                else (1f - open / heldOpen).coerceIn(0f, 1f)
            val distance = if (direction == LayoutDirection.Ltr) pageW - left else -left - width
            left += (distance * travel).roundToInt()
            ink = 1f
        }
    }
}
