package io.github.nakanomk.stacklife.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.planner.DayBlock
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.ScheduledState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val gutter = 64.dp

@Composable
internal fun DayAxis(
    drag: AxisDrag, revision: Long, enabled: Boolean, onEdit: (String) -> Unit,
    send: (PlannerCommand.SlotSet) -> Unit, modifier: Modifier,
) {
    val density = LocalDensity.current
    Box(modifier.fillMaxWidth().clipToBounds().onGloballyPositioned { drag.viewport = it.boundsInRoot() }) {
        Column(Modifier.fillMaxSize().testTag("day-axis").verticalScroll(drag.scroll)) {
            for (band in drag.scale.bands) key(band.start, band.block?.taskId) {
                Box(Modifier.fillMaxWidth().height(with(density) { band.height.toDp() })) {
                    for (tick in band.ticks) {
                        Box(Modifier.fillMaxWidth().offset { IntOffset(0, band.yAt(tick.millis)) }.testTag(tick.tag)) {
                            HorizontalDivider(Modifier.padding(start = if (tick.major) 52.dp else gutter),
                                color = if (tick.major) MaterialTheme.colorScheme.outlineVariant
                                else MaterialTheme.colorScheme.surfaceContainerHigh)
                            if (tick.major) {
                                Text(tick.label, modifier = Modifier.width(gutter).padding(start = 4.dp),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    band.block?.let { block ->
                        BlockCard(block, drag, revision, enabled, onEdit, send,
                            Modifier.fillMaxSize().padding(start = gutter, end = 4.dp))
                    }
                }
            }
        }
        CurrentLine(drag)
        DropPreview(drag)
    }
}

@Composable
private fun BlockCard(
    block: DayBlock, drag: AxisDrag, revision: Long, enabled: Boolean,
    onEdit: (String) -> Unit, send: (PlannerCommand.SlotSet) -> Unit, modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val color = when (block.state) {
        ScheduledState.QUEUE -> scheme.secondaryContainer
        ScheduledState.FRAME -> scheme.tertiaryContainer
        ScheduledState.DONE -> scheme.surfaceContainerHigh
    }
    val state = when (block.state) {
        ScheduledState.QUEUE -> "队列"
        ScheduledState.FRAME -> "已压栈 · 仍占用时段"
        ScheduledState.DONE -> "已完成"
    }
    val range = "${clockLabel(block.startMillis, drag.scale.day)}–${clockLabel(block.endMillis, drag.scale.day)}"
    val editable = block.state != ScheduledState.DONE
    val action = if (!editable) Modifier else Modifier.slotDrag(
        SlotSource(block.taskId, ((block.endMillis - block.startMillis) / MINUTE_MS).toInt(), block.startMillis),
        drag, revision, enabled, send,
    ).clickable(enabled, onClickLabel = "编辑时间", role = Role.Button) { onEdit(block.taskId) }
    Surface(modifier.testTag("block:${block.taskId}").clip(MaterialTheme.shapes.medium).then(action)
        .semantics(mergeDescendants = true) {}, color = color, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) {
            Text(block.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$range · $state", style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CurrentLine(drag: AxisDrag) {
    val millis by produceState(System.currentTimeMillis(), drag.scale.day) {
        while (true) {
            value = System.currentTimeMillis()
            delay(MINUTE_MS - Math.floorMod(value, MINUTE_MS))
        }
    }
    if (millis !in drag.scale.day.startMillis until drag.scale.day.endMillis) return
    val top = drag.scale.yAt(millis) - drag.scroll.value
    if (top < 0 || top >= drag.viewport.height) return
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().offset { IntOffset(0, top) }.testTag("now-line")
        .semantics { contentDescription = "现在 ${clockLabel(millis, drag.scale.day)}" }) {
        HorizontalDivider(color = color, thickness = 2.dp)
        Text("现在", Modifier.background(color, MaterialTheme.shapes.small).padding(horizontal = 4.dp),
            color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DropPreview(drag: AxisDrag) {
    val slot = drag.candidate ?: return
    val checked = drag.checked?.takeIf { it.slot == slot }
    val start = slot.start.toInstant().toEpochMilli()
    val end = start + slot.durationMinutes * MINUTE_MS
    val top = (drag.scale.yAt(start) - drag.scroll.value).coerceAtLeast(0)
    val bottom = (drag.scale.yAt(end) - drag.scroll.value).coerceAtMost(drag.viewport.height.roundToInt())
    val density = LocalDensity.current
    val color = if (checked?.error == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    if (bottom > top) Box(Modifier.fillMaxWidth().offset { IntOffset(0, top) }.padding(start = gutter, end = 4.dp)
        .height(with(density) { (bottom - top).toDp() }).background(color.copy(alpha = 0.29f), MaterialTheme.shapes.medium)) {
        HorizontalDivider(thickness = 3.dp, color = color)
    }
    val status = checked?.error ?: if (checked == null) "检查时段中" else "松手安排"
    Surface(Modifier.fillMaxWidth().padding(start = gutter, end = 4.dp, top = 2.dp).testTag("drop-preview"),
        color = if (checked?.error == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium, shadowElevation = 2.dp) {
        Text("${clockLabel(start, drag.scale.day)}–${clockLabel(end, drag.scale.day)} · $status",
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
    }
}
