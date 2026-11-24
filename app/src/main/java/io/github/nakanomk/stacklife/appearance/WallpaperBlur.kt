package io.github.nakanomk.stacklife.appearance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Three filtered mip levels, built once per decoded image on a worker (also on API 26).
 * The slider interpolates these with the original; it never decodes, filters or stores a frame. */
internal class WallpaperBlur(val quarter: ImageBitmap, val half: ImageBitmap, val full: ImageBitmap)

internal suspend fun wallpaperBlur(source: Bitmap, base: Int): WallpaperBlur {
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    var mip = source
    try {
        // Halve progressively to integrate source pixels rather than alias a fine pattern into the preview.
        while (maxOf(mip.width, mip.height) > 384) {
            coroutineContext.ensureActive()
            val smaller = Bitmap.createScaledBitmap(mip, (mip.width + 1) / 2, (mip.height + 1) / 2, true)
            if (mip !== source) mip.recycle()
            mip = smaller
        }
        val scale = minOf(1f, 192f / maxOf(mip.width, mip.height))
        val w = (mip.width * scale + .5f).toInt().coerceAtLeast(1)
        val h = (mip.height * scale + .5f).toInt().coerceAtLeast(1)
        val input = IntArray(w * h)
        val scratch = IntArray(w * h)
        suspend fun level(from: Bitmap, width: Int, height: Int): Bitmap {
            coroutineContext.ensureActive()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(base)
            canvas.drawBitmap(from, null, Rect(0, 0, width, height), paint)
            bitmap.getPixels(input, 0, width, 0, 0, width, height)
            for (y in 0 until height) for (x in 0 until width) {
                val row = y * width
                scratch[row + x] = smooth(input[row + (x-2).coerceAtLeast(0)], input[row + (x-1).coerceAtLeast(0)],
                    input[row+x], input[row + (x+1).coerceAtMost(width-1)], input[row + (x+2).coerceAtMost(width-1)])
            }
            for (y in 0 until height) {
                val a = (y-2).coerceAtLeast(0) * width; val b = (y-1).coerceAtLeast(0) * width
                val c = y * width; val d = (y+1).coerceAtMost(height-1) * width; val e = (y+2).coerceAtMost(height-1) * width
                for (x in 0 until width) input[c+x] = smooth(scratch[a+x], scratch[b+x], scratch[c+x], scratch[d+x], scratch[e+x])
            }
            bitmap.setPixels(input, 0, width, 0, 0, width, height)
            return bitmap
        }
        val quarter = level(mip, w, h)
        val half = level(quarter, (w+1)/2, (h+1)/2)
        val full = level(half, (w+3)/4, (h+3)/4)
        return WallpaperBlur(quarter.asImageBitmap(), half.asImageBitmap(), full.asImageBitmap())
    } finally { if (mip !== source) mip.recycle() }
}

// Separable [1 4 6 4 1]/16 binomial approximation of a Gaussian. The small levels
// are opaque over the same base as the original, including for transparent private PNGs.
private fun smooth(a: Int, b: Int, c: Int, d: Int, e: Int): Int {
    fun lanes(a: Int, b: Int, c: Int, d: Int, e: Int, mask: Int) =
        (((a and mask) + 4 * ((b and mask) + (d and mask)) + 6 * (c and mask) + (e and mask) + (0x00080008 and mask)) ushr 4) and mask
    return (0xff shl 24) or lanes(a, b, c, d, e, 0x00ff00ff) or
        (lanes(a ushr 8, b ushr 8, c ushr 8, d ushr 8, e ushr 8, 0xff) shl 8)
}
