package io.github.nakanomk.stacklife.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
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
import io.github.nakanomk.stacklife.courses.CourseDetails
import io.github.nakanomk.stacklife.courses.ScheduledCourse
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.ScheduledState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val spine = 56.dp
private val gutter = 72.dp

@Composable
internal fun DayAxis(
    drag: AxisDrag, revision: Long, enabled: Boolean, onEdit: (String) -> Unit,
    send: (PlannerCommand.SlotSet) -> Unit, modifier: Modifier,
) {
    val density = LocalDensity.current
    val scale = drag.scale
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().clipToBounds().onGloballyPositioned { drag.viewport = it.boundsInRoot() }) {
        Box(Modifier.fillMaxSize().testTag("day-axis").verticalScroll(drag.scroll)) {
            Box(Modifier.fillMaxWidth().height(with(density) { scale.height.toDp() }).drawBehind {
                val x = spine.toPx()
                drawLine(colors.outlineVariant, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                for (group in scale.groups) {
                    for ((index, lane) in group.lanes.withIndex()) for (entry in lane) {
                        val top = scale.yAt(entry.startMillis).toFloat()
                        val bottom = scale.yAt(entry.endMillis).toFloat()
                        val ink = if (entry is AxisEntry.Course) colors.tertiary else colors.secondary
                        if (index == 0) drawLine(ink.copy(alpha = .52f), Offset(x, top), Offset(x, bottom), 5.dp.toPx(), StrokeCap.Round)
                        drawCircle(ink, 7.dp.toPx(), Offset(x, top))
                        drawCircle(colors.surface, 2.5.dp.toPx(), Offset(x, top))
                    }
                }
            }) {
                for (band in scale.bands) for (tick in band.ticks) {
                    Box(Modifier.offset { IntOffset(0, band.top + band.yAt(tick.millis)) }.width(spine - 10.dp)
                        .testTag(tick.tag)) {
                        if (tick.major) Text(tick.label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant)
                        else Spacer(Modifier.align(Alignment.TopEnd).size(4.dp, 1.dp).background(colors.outlineVariant))
                    }
                }
                for (group in scale.groups) key(group.start) {
                    val top = scale.yAt(group.start)
                    BoxWithConstraints(Modifier.offset { IntOffset(0, top) }.fillMaxWidth().padding(start = gutter, end = 8.dp)
                        .height(with(density) { (scale.yAt(group.end) - top).toDp() })) {
                        val width = maxWidth / group.lanes.size
                        val overflow = group.lanes.size > 1 && width < 112.dp
                        Row(if (overflow) Modifier.horizontalScroll(rememberScrollState()) else Modifier) {
                            for (lane in group.lanes) Box(Modifier.width(if (overflow) 112.dp else width).fillMaxHeight()) {
                                for (entry in lane) key(entry) {
                                    val y = scale.yAt(entry.startMillis)
                                    val bounds = Modifier.offset { IntOffset(0, y - top) }.fillMaxWidth()
                                        .height(with(density) { (scale.yAt(entry.endMillis) - y).toDp() })
                                    when (entry) {
                                        is AxisEntry.Task -> BlockCard(entry, drag, revision, enabled, onEdit, send, bounds)
                                        is AxisEntry.Course -> CourseCard(entry.course, drag.scale, bounds)
                                    }
                                }
                            }
                        }
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
    entry: AxisEntry.Task, drag: AxisDrag, revision: Long, enabled: Boolean,
    onEdit: (String) -> Unit, send: (PlannerCommand.SlotSet) -> Unit, modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val block = entry.block
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
    Column(modifier.testTag("block:${block.taskId}").clip(MaterialTheme.shapes.medium).then(action)
        .semantics(mergeDescendants = true) {}.padding(start = 4.dp, end = 4.dp)) {
        Text(range, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(block.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (entry.conflict) "时段冲突 · $state" else state, style = MaterialTheme.typography.labelMedium,
            color = if (entry.conflict) scheme.error else scheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CourseCard(course: ScheduledCourse, scale: DayAxisScale, modifier: Modifier) {
    val meeting = course.meeting
    val scheme = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    if (open) CourseDetails(course) { open = false }
    Column(modifier.testTag("day-course").clip(MaterialTheme.shapes.medium)
        .clickable(onClickLabel = "查看固定课程", role = Role.Button) { open = true }.padding(horizontal = 4.dp)) {
        Text("${clockLabel(meeting.startMillis, scale.day)}–${clockLabel(meeting.endMillis, scale.day)}",
            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(meeting.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (course.conflicts.isEmpty()) "固定课程" else "时段冲突 · 固定课程",
            style = MaterialTheme.typography.labelMedium, color = if (course.conflicts.isEmpty()) scheme.tertiary else scheme.error,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (meeting.room.isNotBlank()) Text(meeting.room, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        HorizontalDivider(Modifier.padding(start = spine), color = color, thickness = 1.dp)
        Text("现在", Modifier.background(color, CircleShape).padding(horizontal = 6.dp),
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
