package io.github.nakanomk.stacklife.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.LocalAppearance

internal fun DayAxisScale.cursorY(millis: Long): Int? =
    if (millis >= day.startMillis && millis < day.endMillis) yAt(millis) else null

/** Behind the events; the end of the coloured segment is the arrow's centroid. */
@Composable
internal fun TimeAxisRail(drag: AxisDrag, spine: Dp, cursorY: Int?, pastDay: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val scale = drag.scale
    val elapsed = when {
        pastDay -> scale.height
        LocalAppearance.current.nowArrow -> cursorY ?: 0
        else -> 0
    }
    Spacer(modifier.padding(start = spine - 1.dp).width(2.dp).layout { child, bounds ->
        val height = (scale.height - drag.scroll.value).coerceIn(0, bounds.maxHeight)
        val line = child.measure(bounds.copy(minHeight = height, maxHeight = height))
        layout(line.width, height) { line.placeRelative(0, 0) }
    }.testTag("day-rail").background(scheme.outlineVariant).drawBehind {
        val past = (elapsed - drag.scroll.value).toFloat().coerceIn(0f, size.height)
        if (past > 0f) drawRect(scheme.primary, size = Size(size.width, past))
    })
}

@Composable
internal fun CurrentTimeMarker(drag: AxisDrag, spine: Dp, millis: Long, y: Int?) {
    val day = drag.scale.day
    if (y == null) return
    val inAxis by remember(drag, y) { derivedStateOf {
        val local = y - drag.scroll.value
        drag.viewport.width > 0f && local >= 0 && local < drag.viewportHeight
    } }
    if (!inAxis) return

    val appearance = LocalAppearance.current
    val scheme = MaterialTheme.colorScheme
    val time = remember(day, millis) { clockLabel(millis, day) }
    val now = stringResource(R.string.today_now_at, time)

    Box(Modifier.layout { measurable, constraints ->
        val child = measurable.measure(constraints)
        layout(child.width, child.height) { child.place(0, y - drag.scroll.value - child.height / 2) }
    }.fillMaxWidth().heightIn(min = 24.dp).testTag("now-line")) {
        if (appearance.showNowLine) {
            Spacer(Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(start = spine)
                .height(5.dp).background(scheme.surface).padding(vertical = 1.dp).background(scheme.primary))
        }
        val cursorSize = 24.dp
        if (appearance.nowArrow) {
            // Both the drawable's area centroid and the timeline rail are at the icon's centre.
            Icon(painterResource(R.drawable.ic_now_navigation), contentDescription = null,
                modifier = Modifier.align(Alignment.CenterStart).offset(x = spine - cursorSize / 2)
                    .size(cursorSize), tint = scheme.primary)
        }
        Text(time, Modifier.align(Alignment.CenterStart).widthIn(max = if (appearance.nowArrow) spine - cursorSize / 2 else spine)
            .clearAndSetSemantics { contentDescription = now }
            .background(scheme.primary, MaterialTheme.shapes.small).padding(horizontal = 3.dp),
            style = MaterialTheme.typography.labelMedium, color = scheme.onPrimary)
    }
}
