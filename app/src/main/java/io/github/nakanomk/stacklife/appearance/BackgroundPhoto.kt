package io.github.nakanomk.stacklife.appearance

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.images.PHOTO_EDGE
import io.github.nakanomk.stacklife.planner.WallpaperSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

@Composable
internal fun wallpaperUsesSolidSurface(): Boolean {
    val appearance = LocalAppearance.current
    return appearance.reduceTransparency || appearance.highContrast || LocalPlatformAppearance.current.contrast > 0f
}

private sealed interface PhotoLoad {
    data object Loading : PhotoLoad
    data object Failed : PhotoLoad
    class Ready(val bitmap: Bitmap) : PhotoLoad
}

private class ProtectedCrop(val rect: Rect, val bands: FloatArray)

/** [record] captures just the painted canvas, not an error label, page contents or chrome. */
@Composable
internal fun WallpaperPixels(spec: WallpaperSpec, base: Color, foreground: Color, card: Boolean,
    modifier: Modifier = Modifier, record: Modifier = Modifier, reportFailure: Boolean = true) {
    val id = spec.imageId
    val preset = spec.preset
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val store = if (id != null) LocalBackgroundStore.current else null
    val resources = LocalResources.current
    val longest = maxOf(viewport.width, viewport.height).coerceAtMost(PHOTO_EDGE)
    val edge = if (longest < 2) longest else Integer.highestOneBit(longest - 1) * 2
    // Keep the same decode when only the mask/focal point or the sub-bucket viewport changes.
    val load = key(id, preset, edge, store, resources) {
        produceState<PhotoLoad>(PhotoLoad.Loading) {
            if (edge > 0 && spec.hasImage) value = try {
                PhotoLoad.Ready(if (preset != null) presetBitmap(resources, preset, edge)
                    else checkNotNull(store).bitmap(checkNotNull(id), edge))
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { PhotoLoad.Failed }
        }
    }.value
    val bitmap = (load as? PhotoLoad.Ready)?.bitmap
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val cachedBlur = remember(bitmap, base) { mutableStateOf<WallpaperBlur?>(null) }
    val blur = cachedBlur.value
    if (bitmap != null && spec.blur > 0f && blur == null) LaunchedEffect(bitmap, base) {
        cachedBlur.value = withContext(Dispatchers.Default) { wallpaperBlur(bitmap, base.toArgb()) }
    }
    val crop = remember(viewport, bitmap, spec.scale, spec.offsetX, spec.offsetY) {
        if (bitmap == null || viewport.width <= 0 || viewport.height <= 0) null
        else wallpaperCrop(viewport, IntSize(bitmap.width, bitmap.height), spec)
    }
    // Present crop and protection together. During a slider gesture the previous protected crop
    // stays on screen until the worker has measured the new one.
    val frame = key(bitmap, base, foreground, viewport) {
        produceState<ProtectedCrop?>(null, crop) {
            if (bitmap != null && crop != null) value = withContext(Dispatchers.Default) {
                ProtectedCrop(crop, wallpaperBands(bitmap, viewport, crop, base, foreground))
            }
        }
    }.value
    Box(modifier.onSizeChanged { viewport = it }) {
        Spacer(Modifier.matchParentSize().then(record).drawWithCache {
            val protection = frame?.bands?.let {
                val tint = if (card) spec.tint else (spec.tint * .55f).coerceAtMost(.28f)
                fun color(alpha: Float) = base.copy(alpha = alpha + (1f - alpha) * tint)
                // Blend at band boundaries without reducing either neighbouring band's protection.
                val stops = Array(2 * it.size + 1) { k ->
                    val i = k / 2
                    val alpha = when {
                        k == 0 -> it.first(); k == 2 * it.size -> it.last()
                        k % 2 == 1 -> it[i]; else -> max(it[i - 1], it[i])
                    }
                    (k.toFloat() / (2 * it.size)) to color(alpha)
                }
                Brush.verticalGradient(colorStops = stops)
            }
            onDrawBehind {
                drawRect(base)
                if (image != null && frame != null && protection != null) clipRect {
                    val strength = spec.blur
                    when {
                        strength == 0f || blur == null -> drawCrop(image, frame.rect)
                        strength <= .25f -> blendCrop(image, blur.quarter, strength * 4f, frame.rect)
                        strength <= .5f -> blendCrop(blur.quarter, blur.half, strength * 4f - 1f, frame.rect)
                        else -> blendCrop(blur.half, blur.full, strength * 2f - 1f, frame.rect)
                    }
                    drawRect(protection)
                }
            }
        })
        if (reportFailure && load == PhotoLoad.Failed) Text(stringResource(R.string.wallpaper_unavailable),
            Modifier.align(Alignment.BottomCenter).semantics { liveRegion = LiveRegionMode.Polite }.padding(4.dp),
            color = foreground, style = MaterialTheme.typography.labelMedium)
    }
}

private fun DrawScope.blendCrop(low: ImageBitmap, high: ImageBitmap, fraction: Float, rect: Rect) {
    if (fraction < 1f) drawCrop(low, rect)
    if (fraction > 0f) drawCrop(high, rect, fraction)
}

private fun DrawScope.drawCrop(image: ImageBitmap, rect: Rect, opacity: Float = 1f) {
    withTransform({
        translate(rect.left, rect.top)
        scale(rect.width / image.width, rect.height / image.height, Offset.Zero)
    }) { drawImage(image, alpha = opacity) }
}

@Composable
internal fun BackgroundPhoto(imageId: String, modifier: Modifier = Modifier, maskOpacity: Float = 0f) {
    if (wallpaperUsesSolidSurface()) Spacer(modifier.background(MaterialTheme.colorScheme.surface))
    else WallpaperPixels(WallpaperSpec(imageId = imageId, tint = maskOpacity), Color.Black, Color.White, card = true, modifier = modifier)
}
