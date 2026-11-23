package io.github.nakanomk.stacklife.appearance

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.planner.WallpaperPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val WallpaperPreset.drawable get() = when (this) {
    WallpaperPreset.BROWN -> R.drawable.wallpaper_plaid_brown
    WallpaperPreset.PINK -> R.drawable.wallpaper_plaid_pink
    WallpaperPreset.BLUE -> R.drawable.wallpaper_plaid_blue
    WallpaperPreset.ORANGE -> R.drawable.wallpaper_plaid_orange
}

// These drawable-nodpi sources never change across themes, languages or private-photo leases.
// One full 1024 × 2219 image plus four sampled swatches fit in this budget.
private val bitmaps = object : LruCache<Int, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
}
private val decodeLock = Mutex()

internal suspend fun presetBitmap(resources: Resources, preset: WallpaperPreset, edge: Int): Bitmap {
    val key = edge * WallpaperPreset.entries.size + preset.ordinal
    bitmaps.get(key)?.let { return it }
    return withContext(Dispatchers.IO) {
        decodeLock.withLock {
            bitmaps.get(key) ?: run {
                val id = preset.drawable
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
                BitmapFactory.decodeResource(resources, id, options)
                val longest = maxOf(options.outWidth, options.outHeight)
                options.inSampleSize = Integer.highestOneBit((longest / edge).coerceAtLeast(1))
                options.inJustDecodeBounds = false
                checkNotNull(BitmapFactory.decodeResource(resources, id, options)).also { bitmaps.put(key, it) }
            }
        }
    }
}

@Composable
internal fun WallpaperThumbnail(preset: WallpaperPreset, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val image = key(preset, resources) {
        produceState<ImageBitmap?>(null) { value = presetBitmap(resources, preset, 512).asImageBitmap() }
    }.value
    Box(modifier) {
        if (image != null) Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
