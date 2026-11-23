package io.github.nakanomk.stacklife.appearance

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.images.PhotoProblem
import io.github.nakanomk.stacklife.planner.Appearance
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.planner.WallpaperSpec
import io.github.nakanomk.stacklife.workspace.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class WallpaperSource(@StringRes val label: Int) {
    INHERIT(R.string.wallpaper_inherit), SOLID(R.string.wallpaper_solid), IMAGE(R.string.wallpaper_photo)
}

internal fun Appearance.replacingWallpaper(page: Destination?, spec: WallpaperSpec?) = if (page == null)
    copy(background = requireNotNull(spec))
else copy(pageBackgrounds = if (spec == null) pageBackgrounds - page else pageBackgrounds + (page to spec))

internal class WallpaperPicker(val pick: (Destination?) -> Unit, val preparing: Boolean, val target: Destination?, val failure: String?)

// The grant and its copy belong to the editor, not a recycled settings row. An already handed-off
// image belongs to the saveable draft; cancelling an unfinished copy is handled by BackgroundStore.
@Composable
internal fun rememberWallpaperPicker(onImported: (Destination?, String) -> Unit): WallpaperPicker {
    val handOff by rememberUpdatedState(onImported)
    var pickerTarget by rememberSaveable { mutableStateOf<Destination?>(null) }
    var awaiting by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val store = LocalBackgroundStore.current
    val importFailure = stringResource(R.string.wallpaper_import_failed)
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        awaiting = false
        if (uri != null) {
            val page = pickerTarget
            importing = true
            scope.launch {
                try {
                    handOff(page, store.importImage(uri))
                } catch (cancel: CancellationException) { throw cancel }
                catch (problem: PhotoProblem) { failure = problem.message ?: importFailure }
                catch (_: Exception) { failure = importFailure }
                finally { importing = false }
            }
        }
    }
    val pick: (Destination?) -> Unit = { page ->
        if (!awaiting && !importing) {
            failure = null
            pickerTarget = page
            awaiting = true
            try { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
            catch (_: ActivityNotFoundException) { awaiting = false; failure = importFailure }
            catch (_: SecurityException) { awaiting = false; failure = importFailure }
        }
    }
    return remember(pick, awaiting, importing, pickerTarget, failure) { WallpaperPicker(pick, awaiting || importing, pickerTarget, failure) }
}

// BiliPai 5893c2c, HomeWallpaperAdjustmentSheet: draft-only crop in the window's aspect ratio.
@Composable
internal fun WallpaperControls(value: Appearance, change: (Appearance) -> Unit, target: Destination?,
    chooseTarget: (Destination?) -> Unit, picker: WallpaperPicker) {
    val own = if (target == null) value.background else value.pageBackgrounds[target]
    val spec = own ?: value.background
    val mode = if (own == null) WallpaperSource.INHERIT else if (own.imageId == null) WallpaperSource.SOLID else WallpaperSource.IMAGE
    FormGroup(Modifier.testTag("wallpaper-card"), frosted = true) {
        SectionTitle(stringResource(R.string.wallpaper_title))
        Choice(stringResource(R.string.wallpaper_target), listOf(null) + Destination.entries, target,
            { it?.label ?: stringResource(R.string.wallpaper_global) }, change = chooseTarget)
        if (picker.preparing) Hint(stringResource(R.string.wallpaper_preparing)) else {
            Choice(stringResource(R.string.wallpaper_source), if (target == null) listOf(WallpaperSource.SOLID, WallpaperSource.IMAGE) else WallpaperSource.entries,
                mode, { stringResource(it.label) }) {
                when (it) {
                    WallpaperSource.INHERIT -> change(value.replacingWallpaper(target, null))
                    WallpaperSource.SOLID -> change(value.replacingWallpaper(target, WallpaperSpec()))
                    WallpaperSource.IMAGE -> if (mode != WallpaperSource.IMAGE) picker.pick(target)
                }
            }
            if (mode == WallpaperSource.IMAGE) PreferenceRow(stringResource(R.string.wallpaper_replace), onClick = { picker.pick(target) })
        }
        if (picker.target == target) picker.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (spec.imageId != null) {
            if (wallpaperUsesSolidSurface()) Hint(stringResource(R.string.wallpaper_solid_accessibility))
            else {
                val window = LocalWindowInfo.current.containerSize
                val aspect = window.width.coerceAtLeast(1).toFloat() / window.height.coerceAtLeast(1)
                BoxWithConstraints(Modifier.fillMaxWidth().height(208.dp), contentAlignment = Alignment.Center) {
                    val h = minOf(maxHeight, maxWidth / aspect)
                    WallpaperPixels(spec, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, card = false,
                        modifier = Modifier.size(h * aspect, h).clip(MaterialTheme.shapes.medium).testTag("wallpaper-crop-preview"))
                }
            }
        }
        if (mode == WallpaperSource.IMAGE && !picker.preparing) {
            fun update(next: WallpaperSpec) = change(value.replacingWallpaper(target, next))
            WallpaperSlider(R.string.wallpaper_zoom, spec.scale, 1f..3f) { update(spec.copy(scale = it)) }
            WallpaperSlider(R.string.wallpaper_horizontal, spec.offsetX, -1f..1f) { update(spec.copy(offsetX = it)) }
            WallpaperSlider(R.string.wallpaper_vertical, spec.offsetY, -1f..1f) { update(spec.copy(offsetY = it)) }
            WallpaperSlider(R.string.wallpaper_protection, spec.tint, 0f..1f) { update(spec.copy(tint = it)) }
            TextButton(onClick = { update(spec.copy(scale = 1f, offsetX = 0f, offsetY = 0f)) },
                enabled = spec.scale != 1f || spec.offsetX != 0f || spec.offsetY != 0f) { Text(stringResource(R.string.wallpaper_reset_crop)) }
        }
    }
}

@Composable
private fun WallpaperSlider(@StringRes name: Int, value: Float, range: ClosedFloatingPointRange<Float>, update: (Float) -> Unit) {
    val label = stringResource(name)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.wallpaper_percent, (100f * value).roundToInt()), style = MaterialTheme.typography.labelMedium)
    }
    Slider(value = value, onValueChange = update, valueRange = range, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}
