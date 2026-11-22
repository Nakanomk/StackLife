package io.github.nakanomk.stacklife.appearance

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntSize
import io.github.nakanomk.stacklife.planner.WallpaperSpec
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Adapted from BiliPai 5893c2c (GPL-3.0): WallpaperTransformPolicy.resolveWallpaperCropGeometry,
// WallpaperReadabilityPolicy.resolveWallpaperProtectionAlpha and WallpaperPaletteStore.extractVisiblePalette.
// Keep the five local luminance bands and their 10/90 percentiles; no decorative palette or video.
internal fun wallpaperCrop(viewport: IntSize, source: IntSize, spec: WallpaperSpec): Rect {
    val factor = max(viewport.width.toFloat() / source.width, viewport.height.toFloat() / source.height) * spec.scale
    val w = max(viewport.width.toFloat(), source.width * factor)
    val h = max(viewport.height.toFloat(), source.height * factor)
    val left = -(w - viewport.width) * ((spec.offsetX + 1f) / 2f)
    val top = -(h - viewport.height) * ((spec.offsetY + 1f) / 2f)
    return Rect(left, top, left + w, top + h)
}

internal fun wallpaperProtection(worstLuminance: Float, surface: Color, foreground: Color): Float {
    val l = foreground.luminance()
    val gray = if (worstLuminance <= .0031308f) worstLuminance * 12.92f else 1.055f * worstLuminance.pow(1f / 2.4f) - .055f
    val bg = Color(gray, gray, gray)
    fun readable(alpha: Float): Boolean {
        val painted = surface.copy(alpha = alpha).compositeOver(bg).luminance()
        return (max(l, painted) + .05f) / (min(l, painted) + .05f) >= 4.5f
    }
    var low = .16f // Direct (unblurred) painting; never count on glass to make text legible.
    if (readable(low)) return low
    var high = 1f
    repeat(12) {
        val mid = (low + high) * .5f
        if (readable(mid)) high = mid else low = mid
    }
    return high
}

/** Sample only visible source pixels after crop; IO/worker caller, never a per-frame draw callback. */
internal fun wallpaperBands(bitmap: Bitmap, viewport: IntSize, crop: Rect, base: Color, foreground: Color): FloatArray {
    val sx = bitmap.width / crop.width
    val sy = bitmap.height / crop.height
    val x0 = floor(-crop.left * sx).toInt().coerceIn(0, bitmap.width - 1)
    val x1 = ceil((viewport.width - crop.left) * sx).toInt().coerceIn(x0 + 1, bitmap.width)
    val dx = ((x1 - x0 + 19) / 20).coerceAtLeast(1)
    val samples = FloatArray(400)
    val darkText = foreground.luminance() < .5f
    return FloatArray(5) { band ->
        val y0 = floor((viewport.height * (band / 5f) - crop.top) * sy).toInt().coerceIn(0, bitmap.height - 1)
        val y1 = ceil((viewport.height * ((band + 1) / 5f) - crop.top) * sy).toInt().coerceIn(y0 + 1, bitmap.height)
        val dy = ((y1 - y0 + 19) / 20).coerceAtLeast(1)
        var n = 0
        for (y in y0 until y1 step dy) for (x in x0 until x1 step dx) {
            samples[n++] = Color(bitmap.getPixel(x, y)).compositeOver(base).luminance()
        }
        java.util.Arrays.sort(samples, 0, n)
        wallpaperProtection(samples[((n - 1) * if (darkText) .1f else .9f).toInt()], base, foreground)
    }
}
