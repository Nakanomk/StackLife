package io.github.nakanomk.stacklife.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.courses.CourseDetails
import io.github.nakanomk.stacklife.courses.ScheduledCourse
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.ScheduledState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
internal fun DayAxis(
    drag: AxisDrag, revision: Long, enabled: Boolean, onEdit: (String) -> Unit,
    send: (PlannerCommand.SlotSet) -> Unit, modifier: Modifier, topInset: Dp, scrollTail: Dp,
) {
    val density = LocalDensity.current
    val scale = drag.scale
    val colors = MaterialTheme.colorScheme
    val hourStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val spine = remember(measurer, hourStyle, density) {
        val clockWidth = measurer.measure("00:00", style = hourStyle).size.width
        with(density) { (clockWidth.toDp() + 16.dp).coerceAtLeast(56.dp) }
    }
    val railWidth = 48.dp
    val gutter = spine + railWidth / 2
    var selectedEnd by remember(drag, revision) { mutableStateOf<String?>(null) }
    Box(modifier.fillMaxWidth().clipToBounds()) {
        Box(Modifier.fillMaxSize().testTag("day-axis").verticalScroll(drag.scroll).padding(top = topInset, bottom = scrollTail)) {
            Box(Modifier.fillMaxWidth().height(with(density) { scale.height.toDp() }).drawBehind {
                val x = if (layoutDirection == LayoutDirection.Ltr) spine.toPx() else size.width - spine.toPx()
                drawLine(colors.outlineVariant, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            }) {
                for (band in scale.bands) for (tick in band.ticks) {
                    Box(Modifier.offset { IntOffset(0, band.top + band.yAt(tick.millis)) }.width(spine - 10.dp)
                        .testTag(tick.tag)) {
                        if (tick.major) Text(tick.label, Modifier.padding(start = 4.dp), style = hourStyle, softWrap = false,
                            color = colors.onSurfaceVariant)
                        else Spacer(Modifier.align(Alignment.TopEnd).size(4.dp, 1.dp).background(colors.outlineVariant))
                    }
                }
                for (group in scale.groups) key(group.start) {
                    val top = scale.yAt(group.start)
                    BoxWithConstraints(Modifier.offset { IntOffset(0, top) }.fillMaxWidth().padding(start = gutter - railWidth, end = 8.dp)
                        .height(with(density) { (scale.yAt(group.end) - top).toDp() })) {
                        val width = maxWidth / group.lanes.size
                        val minimumLane = railWidth + 112.dp
                        val overflow = group.lanes.size > 1 && width < minimumLane
                        Row(if (overflow) Modifier.horizontalScroll(rememberScrollState()) else Modifier) {
                            for (lane in group.lanes) Box(Modifier.width(if (overflow) minimumLane else width).fillMaxHeight()) {
                                for (entry in lane) key(entry) {
                                    val y = scale.yAt(entry.startMillis)
                                    val bounds = Modifier.offset { IntOffset(0, y - top) }.fillMaxWidth()
                                        .height(with(density) { (scale.yAt(entry.endMillis) - y).toDp() })
                                    Row(bounds) {
                                        EventRail(entry, selectedEnd, { selectedEnd = it }, drag, revision, enabled, onEdit, send,
                                            Modifier.width(railWidth).fillMaxHeight())
                                        when (entry) {
                                            is AxisEntry.Task -> BlockCard(entry, drag, revision, enabled, onEdit, send, Modifier.fillMaxSize())
                                            is AxisEntry.Course -> CourseCard(entry.course, drag.scale, Modifier.fillMaxSize())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().padding(top = topInset).clipToBounds().testTag("day-drop-area")
            .onGloballyPositioned { drag.viewport = it.boundsInRoot() }) {
            CurrentLine(drag, spine)
            DropPreview(drag, gutter)
        }
    }
}

@Composable
private fun EventRail(
    entry: AxisEntry, selectedId: String?, select: (String?) -> Unit, drag: AxisDrag, revision: Long,
    enabled: Boolean, onEdit: (String) -> Unit, send: (PlannerCommand.SlotSet) -> Unit, modifier: Modifier,
) {
    val task = (entry as? AxisEntry.Task)?.block
    // A clipped continuation has no endpoint here. Its full interval is still editable by tapping the title.
    val resizable = task?.takeIf { it.state != ScheduledState.DONE && it.endMillis <= drag.scale.day.endMillis }
    val selected = resizable != null && resizable.taskId == selectedId
    val colors = MaterialTheme.colorScheme
    val ink = when {
        selected -> colors.primary
        task == null -> colors.tertiary
        else -> colors.secondary
    }
    val action = if (resizable == null) Modifier else Modifier.testTag("resize-bar:${resizable.taskId}")
        .combinedClickable(enabled = enabled, role = Role.Button, onLongClickLabel = "显示结束手柄",
            onLongClick = { select(resizable.taskId) }, onClickLabel = if (selected) "收起结束手柄" else "显示结束手柄",
            onClick = { select(if (selected) null else resizable.taskId) })
        .semantics {
            contentDescription = "${resizable.title}，时段条"
            stateDescription = if (selected) "已显示结束手柄" else "长按调整结束时间"
        }
    Box(modifier) {
        Spacer(Modifier.fillMaxSize().then(action).drawBehind {
            val radius = 6.dp.toPx()
            val top = Offset(size.width / 2, radius)
            if (!selected || drag.resizeRailX == null || drag.candidate == null) {
                drawLine(ink.copy(alpha = if (selected) 1f else .65f), top,
                    Offset(top.x, size.height - 3.dp.toPx()), 5.dp.toPx(), StrokeCap.Round)
            }
            drawCircle(ink, radius, top)
            drawCircle(colors.surface, 2.dp.toPx(), top)
        })
        if (selected && resizable != null) {
            val source = SlotSource(resizable.taskId, ((resizable.endMillis - resizable.startMillis) / MINUTE_MS).toInt(), resizable.startMillis)
            val endLabel = clockLabel(resizable.endMillis, drag.scale.day)
            var railX by remember { mutableFloatStateOf(Float.NaN) }
            LaunchedEffect(drag, resizable.taskId, railX) {
                if (railX.isNaN()) return@LaunchedEffect
                // A long task may continue below the screen. Reveal the real end, never put
                // a substitute handle at the viewport edge or on a clipped midnight boundary.
                val endY = drag.scale.yAt(resizable.endMillis)
                val top = drag.viewport.top
                val visibleHeight = (drag.uncoveredBottom(Offset(railX, top)) - top).roundToInt()
                if (visibleHeight > 0 && endY > drag.scroll.value + visibleHeight) {
                    val margin = visibleHeight / 4
                    drag.scroll.scrollTo((endY - visibleHeight + margin).coerceAtLeast(0))
                }
            }
            Spacer(Modifier.align(Alignment.BottomCenter).size(48.dp).testTag("resize-end:${resizable.taskId}")
                .onGloballyPositioned { railX = it.localToRoot(Offset(it.size.width / 2f, 0f)).x }
                .endDrag(source, drag, revision, enabled, send)
                .clickable(enabled, onClickLabel = "精确编辑时间", role = Role.Button) { onEdit(resizable.taskId) }
                .semantics { contentDescription = "${resizable.title}，结束手柄，$endLabel，上下拖动调整结束时间" }
                .drawBehind {
                    if (drag.resizeRailX == null || drag.candidate == null) drawEndKnob(size.width / 2, size.height, ink, colors.surface)
                })
        }
    }
}

private fun DrawScope.drawEndKnob(x: Float, endY: Float, ink: Color, centerColor: Color) {
    val radius = 8.dp.toPx()
    val center = Offset(x, endY - radius)
    drawCircle(ink, radius, center)
    drawCircle(centerColor, 3.dp.toPx(), center)
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
private fun CurrentLine(drag: AxisDrag, spine: Dp) {
    val millis by produceState(System.currentTimeMillis(), drag.scale.day) {
        while (true) {
            value = System.currentTimeMillis()
            delay(MINUTE_MS - Math.floorMod(value, MINUTE_MS))
        }
    }
    if (millis !in drag.scale.day.startMillis until drag.scale.day.endMillis) return
    val top = drag.scale.yAt(millis) - drag.scroll.value
    if (top < 0) return
    val color = MaterialTheme.colorScheme.primary
    HorizontalDivider(Modifier.offset { IntOffset(0, top) }.padding(start = spine).testTag("now-line")
        .semantics { contentDescription = "现在 ${clockLabel(millis, drag.scale.day)}" }, color = color, thickness = 2.dp)
}

@Composable
private fun DropPreview(drag: AxisDrag, gutter: Dp) {
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
        HorizontalDivider(Modifier.align(if (drag.resizeRailX == null) Alignment.TopCenter else Alignment.BottomCenter),
            thickness = 3.dp, color = color)
    }
    val railX = drag.resizeRailX
    if (railX != null) {
        val edge = drag.scale.yAt(end) - drag.scroll.value
        val centerColor = MaterialTheme.colorScheme.surface
        Spacer(Modifier.fillMaxSize().clipToBounds().drawBehind {
            val x = railX - drag.viewport.left
            if (bottom > top) drawLine(color, Offset(x, top.toFloat()), Offset(x, bottom.toFloat()), 5.dp.toPx())
            if (edge in 0..size.height.roundToInt()) {
                drawEndKnob(x, edge.toFloat(), color, centerColor)
            }
        })
    }
    val status = checked?.error ?: when {
        checked == null -> "检查时段中"
        railX != null -> "松手调整结束时间"
        else -> "松手安排"
    }
    Surface(Modifier.fillMaxWidth().padding(start = gutter, end = 4.dp, top = 2.dp).testTag("drop-preview"),
        color = if (checked?.error == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium, shadowElevation = 2.dp) {
        Text("${clockLabel(start, drag.scale.day)}–${clockLabel(end, drag.scale.day)} · $status",
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
    }
}
