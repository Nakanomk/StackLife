package io.github.nakanomk.stacklife.today

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.planner.DayScrollEffect
import io.github.nakanomk.stacklife.planner.OccurrenceKey
import kotlin.math.abs

@Composable
internal fun dayWheelEnabled(): Boolean {
    if (LocalAppearance.current.dayScrollEffect != DayScrollEffect.WHEEL || !LocalMotionEnabled.current) return false
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var exploring by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploring = it }
        manager.addTouchExplorationStateChangeListener(listener)
        exploring = manager.isTouchExplorationEnabled
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return !exploring
}

// BiliPai 5893c2c, ThemeTimeWheelPicker.kt / ThemeTimeWheelColumn (GPL-3.0).
// Normalize the visible center against the physical viewport edges, not the duration of the event.
private fun GraphicsLayerScope.projectDayWheel(top: Float, viewport: Float, bottomEnabled: Boolean) {
    if (viewport <= 0f || size.height <= 0f) return
    val center = (top.coerceIn(0f, viewport) + (top + size.height).coerceIn(0f, viewport)) * .5f
    val band = maxOf(50f, 44.dp.toPx() * fontScale)
    val distance = if (center <= viewport / 2) -3f * (1f - center / band).coerceAtLeast(0f)
        else if (bottomEnabled) 3f * (1f - (viewport - center) / band).coerceAtLeast(0f) else 0f
    val weight = (1f - abs(distance) / 3f).coerceIn(0f, 1f)
    // A day-long rail can exceed the device's texture limit. Fade its primitives, not a bitmap of the row.
    compositingStrategy = CompositingStrategy.ModulateAlpha
    alpha = .25f + .75f * weight
    scaleX = .82f + .18f * weight
    scaleY = scaleX
    rotationX = (distance * -16f).coerceIn(-65f, 65f)
    cameraDistance = 12f * density
    transformOrigin = TransformOrigin(.5f, ((center - top) / size.height).coerceIn(0f, 1f))
}

internal fun Modifier.axisWheel(enabled: Boolean, drag: AxisDrag, y: Int, key: OccurrenceKey?): Modifier = if (!enabled) this else graphicsLayer {
    if (key == null || !drag.isDragging(key)) projectDayWheel((y - drag.scroll.value).toFloat(), drag.viewportHeight,
        drag.scroll.maxValue == Int.MAX_VALUE || drag.scroll.canScrollForward)
}

internal fun Modifier.listWheel(enabled: Boolean, list: LazyListState, index: Int): Modifier = if (!enabled) this else graphicsLayer {
    val layout = list.layoutInfo
    val visible = layout.visibleItemsInfo
    // Calendar rows are not sticky or pinned: visible indices are contiguous in scroll order.
    if (visible.isEmpty()) return@graphicsLayer
    val item = visible.getOrNull(index - visible[0].index) ?: return@graphicsLayer
    // Rows scroll under the fixed header for glass sampling; the visible wheel starts below it.
    val top = layout.viewportStartOffset + layout.beforeContentPadding
    projectDayWheel(item.offset - top + (item.size - size.height) / 2f,
        (layout.viewportEndOffset - top).toFloat(), list.canScrollForward)
}

/** Read-only clock rows share the event list projection. No selection, haptics or snapping. */
@Composable
internal fun DayWheelPreview() {
    val enabled = dayWheelEnabled()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = 7)
    val row = 48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val zoneDay = deviceDay()
    val day = remember(zoneDay) { io.github.nakanomk.stacklife.planner.PlannerQuery.Day(zoneDay.date, zoneDay.zone) }
    val hours = ((day.endMillis - day.startMillis + 60 * MINUTE_MS - 1) / (60 * MINUTE_MS)).toInt()
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height(row * 3).testTag("day-wheel-preview")) {
            items(hours) { index ->
                val time = day.startMillis + index * 60 * MINUTE_MS
                Row(Modifier.fillMaxWidth().heightIn(min = row).listWheel(enabled, list, index).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(clockLabel(time, day), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.today_free_duration, elapsedLabel(minOf(60 * MINUTE_MS, day.endMillis - time))),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
