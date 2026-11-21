package io.github.nakanomk.stacklife.today

import io.github.nakanomk.stacklife.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.courses.CourseDetails
import io.github.nakanomk.stacklife.courses.ScheduledCourse
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.planner.ScheduledState
import io.github.nakanomk.stacklife.transitions.rememberRouteLaunch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
internal fun DayAxis(
    drag: AxisDrag, revision: Long, enabled: Boolean, onEdit: (String) -> Unit,
    send: (PlannerCommand.SlotSet) -> Unit, modifier: Modifier, topInset: Dp, spine: Dp, endInGutter: Boolean,
) {
    val density = LocalDensity.current
    val scale = drag.scale
    val hourStyle = MaterialTheme.typography.labelMedium
    val railWidth = 48.dp
    val gutter = spine + railWidth / 2
    var selectedEnd by remember(drag, revision) { mutableStateOf<String?>(null) }
    Box(modifier.fillMaxWidth().clipToBounds()) {
        Box(Modifier.fillMaxSize().testTag("day-axis").verticalScroll(drag.scroll).padding(top = topInset, bottom = 16.dp)) {
            Box(Modifier.fillMaxWidth().height(with(density) { scale.height.toDp() })) {
                for (band in scale.bands) for (tick in band.ticks) {
                    Box(Modifier.offset { IntOffset(0, band.top + band.yAt(tick.millis)) }.width(spine - 10.dp)
                        .testTag(tick.tag)) {
                        if (tick.major) Text(tick.label, Modifier.padding(start = 4.dp), style = hourStyle, softWrap = false,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Spacer(Modifier.align(Alignment.TopEnd).size(4.dp, 1.dp).background(MaterialTheme.colorScheme.outlineVariant))
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
                                            is AxisEntry.Task -> {
                                                val block = entry.block
                                                BlockCard(entry, scale.day, enabled, onEdit, Modifier.fillMaxSize(),
                                                    Modifier.slotDrag(SlotSource(block.taskId,
                                                        ((block.endMillis - block.startMillis) / MINUTE_MS).toInt(), block.startMillis),
                                                        drag, revision, enabled, send))
                                            }
                                            is AxisEntry.Course -> CourseCard(entry.course, scale.day, Modifier.fillMaxSize())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val end = remember(scale.day) { clockLabel(scale.day.endMillis, scale.day) }
            val label = stringResource(R.string.today_end_boundary, end)
            val compact = endInGutter || scale.groups.lastOrNull()?.end == scale.day.endMillis
            val clock = remember(scale.day) { clockLabel(scale.day.endMillis, scale.day, includeDate = false) }
            Text(if (compact) clock else label,
                Modifier.align(Alignment.BottomStart).padding(start = if (compact) 4.dp else spine + 12.dp)
                    .testTag("time-day-end").semantics { contentDescription = label },
                style = hourStyle, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxSize().padding(top = topInset).clipToBounds().testTag("day-drop-area")
            .onGloballyPositioned { drag.viewport = it.boundsInRoot() }) {
            CurrentLine(drag, spine)
            DropPreview(drag, gutter)
        }
    }
}

@Composable
internal fun daySpine(): Dp {
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    return remember(measurer, style, density) {
        with(density) { (measurer.measure("00:00", style = style).size.width.toDp() + 16.dp).coerceAtLeast(56.dp) }
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
    val barDescription = resizable?.let { stringResource(R.string.today_task_bar, it.title) }
    val handleState = if (selected) stringResource(R.string.today_handle_shown)
        else stringResource(R.string.today_hold_to_resize)
    val action = if (resizable == null) Modifier else Modifier.testTag("resize-bar:${resizable.taskId}")
        .combinedClickable(enabled = enabled, role = Role.Button, onLongClickLabel = stringResource(R.string.today_show_handle),
            onLongClick = { select(resizable.taskId) }, onClickLabel = if (selected) stringResource(R.string.today_hide_handle) else stringResource(R.string.today_show_handle),
            onClick = { select(if (selected) null else resizable.taskId) })
        .semantics {
            contentDescription = barDescription.orEmpty()
            stateDescription = handleState
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
            val handleLabel = stringResource(R.string.today_end_handle, resizable.title, endLabel)
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
            val handle = rememberRouteLaunch("slot:${resizable.taskId}", CircleShape)
            Spacer(Modifier.align(Alignment.BottomCenter).size(48.dp).then(handle.modifier).testTag("resize-end:${resizable.taskId}")
                .onGloballyPositioned { railX = it.localToRoot(Offset(it.size.width / 2f, 0f)).x }
                .endDrag(source, drag, revision, enabled, send)
                .clickable(enabled, onClickLabel = stringResource(R.string.today_edit_exact_time), role = Role.Button) { handle.open { onEdit(resizable.taskId) } }
                .semantics { contentDescription = handleLabel }
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
internal fun BlockCard(
    entry: AxisEntry.Task, day: PlannerQuery.Day, enabled: Boolean, onEdit: (String) -> Unit,
    modifier: Modifier, move: Modifier = Modifier, wrap: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val block = entry.block
    val state = when (block.state) {
        ScheduledState.QUEUE -> stringResource(R.string.today_queue)
        ScheduledState.FRAME -> stringResource(R.string.today_stacked_occupied)
        ScheduledState.DONE -> stringResource(R.string.today_done)
    }
    val range = "${clockLabel(block.startMillis, day)}–${clockLabel(block.endMillis, day)}"
    val editable = block.state != ScheduledState.DONE
    val lines = if (wrap) Int.MAX_VALUE else 1
    val action = if (!editable) Modifier else {
        val launch = rememberRouteLaunch("slot:${block.taskId}", MaterialTheme.shapes.medium)
        move.then(launch.modifier).clickable(enabled, onClickLabel = stringResource(R.string.today_edit_time), role = Role.Button) {
            launch.open { onEdit(block.taskId) }
        }
    }
    Column(modifier.testTag("block:${block.taskId}").clip(MaterialTheme.shapes.medium).then(action)
        .semantics(mergeDescendants = true) {}.padding(start = 4.dp, end = 4.dp)) {
        Text(range, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = lines, overflow = TextOverflow.Ellipsis)
        Text(block.title, style = MaterialTheme.typography.titleSmall, maxLines = lines, overflow = TextOverflow.Ellipsis)
        Text(if (entry.conflict) stringResource(R.string.today_conflict_state, state) else state, style = MaterialTheme.typography.labelMedium,
            color = if (entry.conflict) scheme.error else scheme.secondary, maxLines = lines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CourseCard(course: ScheduledCourse, day: PlannerQuery.Day, modifier: Modifier, wrap: Boolean = false) {
    val meeting = course.meeting
    val scheme = MaterialTheme.colorScheme
    val lines = if (wrap) Int.MAX_VALUE else 1
    var open by rememberSaveable { mutableStateOf(false) }
    if (open) CourseDetails(course) { open = false }
    Column(modifier.testTag("day-course").clip(MaterialTheme.shapes.medium)
        .clickable(onClickLabel = stringResource(R.string.today_view_class), role = Role.Button) { open = true }.padding(horizontal = 4.dp)) {
        Text("${clockLabel(meeting.startMillis, day)}–${clockLabel(meeting.endMillis, day)}",
            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = lines, overflow = TextOverflow.Ellipsis)
        Text(meeting.title, style = MaterialTheme.typography.titleSmall, maxLines = lines, overflow = TextOverflow.Ellipsis)
        Text(if (course.conflicts.isEmpty()) stringResource(R.string.today_fixed_class) else stringResource(R.string.today_conflicting_class),
            style = MaterialTheme.typography.labelMedium, color = if (course.conflicts.isEmpty()) scheme.tertiary else scheme.error,
            maxLines = lines, overflow = TextOverflow.Ellipsis)
        if (meeting.room.isNotBlank()) Text(meeting.room, style = MaterialTheme.typography.bodySmall, maxLines = lines, overflow = TextOverflow.Ellipsis)
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
    val now = stringResource(R.string.today_now_at, clockLabel(millis, drag.scale.day))
    HorizontalDivider(Modifier.offset { IntOffset(0, top) }.padding(start = spine).testTag("now-line")
        .semantics { contentDescription = now }, color = color, thickness = 2.dp)
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
        checked == null -> stringResource(R.string.today_checking)
        railX != null -> stringResource(R.string.today_release_resize)
        else -> stringResource(R.string.today_release_schedule)
    }
    Surface(Modifier.fillMaxWidth().padding(start = gutter, end = 4.dp, top = 2.dp).testTag("drop-preview"),
        color = if (checked?.error == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium, shadowElevation = 2.dp) {
        Text("${clockLabel(start, drag.scale.day)}–${clockLabel(end, drag.scale.day)} · $status",
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
    }
}
