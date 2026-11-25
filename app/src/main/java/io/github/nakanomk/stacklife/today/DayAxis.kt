package io.github.nakanomk.stacklife.today

import io.github.nakanomk.stacklife.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.courses.ScheduledCourse
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.planner.*
import androidx.compose.ui.text.style.TextDecoration
import io.github.nakanomk.stacklife.transitions.rememberRouteLaunch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
internal fun DayAxis(
    drag: AxisDrag, revision: Long, enabled: Boolean, onEdit: (AgendaOccurrence) -> Unit, openCourse: (ScheduledCourse) -> Unit,
    send: (PlannerCommand) -> Unit, onMore: (TimeGridPlacement.Overflow) -> Unit, focused: String?, modifier: Modifier, topInset: Dp, spine: Dp, endInGutter: Boolean,
) {
    val density = LocalDensity.current
    val scale = drag.scale
    val wheel = dayWheelEnabled()
    val hourStyle = MaterialTheme.typography.labelMedium
    val railWidth = 48.dp
    val gutter = spine + railWidth / 2
    var selectedEnd by remember(drag, revision) { mutableStateOf<String?>(null) }
    Box(modifier.fillMaxWidth().clipToBounds()) {
        Box(Modifier.fillMaxSize().testTag("day-axis").verticalScroll(drag.scroll).padding(top = topInset, bottom = 16.dp)) {
            val plane = Modifier.fillMaxWidth().height(with(density) { scale.height.toDp() })
            Box(plane) {
                for (band in scale.bands) for (tick in band.ticks) {
                    // The tick's page-local position is fixed for this scale. The lambda
                    // offset would place each quarter hour in its own hardware layer.
                    val y = with(density) { (band.top + band.yAt(tick.millis)).toDp() }
                    val mark = Modifier.offset(y = y).width(spine - 10.dp).testTag(tick.tag)
                    if (tick.major) Text(tick.label, mark.padding(start = 4.dp), style = hourStyle,
                        softWrap = false, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Spacer(mark.wrapContentWidth(Alignment.End).size(4.dp, 1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }
            }
            if (scale.placement.visible.isNotEmpty() || scale.placement.more.isNotEmpty()) BoxWithConstraints(plane) {
                val moreWidth = if (scale.placement.more.isEmpty()) 0.dp else 52.dp
                for (rect in scale.placement.visible) key(rect.item.key) {
                    val item = rect.item
                    val y = scale.yAt(rect.start)
                    val compact = rect.columns > 1
                    val left = (if (compact || moreWidth > 0.dp) spine + 4.dp else gutter - railWidth) + moreWidth
                    val width = (maxWidth - left - 4.dp) / rect.columns
                    val height = if (item.point) scale.minimumHeight(rect) else scale.yAt(rect.end) - y
                    val bounds = Modifier.offset(x = left + width * rect.column, y = with(density) { y.toDp() })
                        .size(width, with(density) { height.toDp() })
                    Row(bounds.axisWheel(wheel, drag, y, item.key)) {
                        if (!compact) EventRail(item, selectedEnd, { selectedEnd = it }, drag, revision, enabled, onEdit, send,
                            Modifier.width(railWidth).fillMaxHeight())
                        val source = item.dragSource()
                        val move = if (source == null) Modifier else Modifier.slotDrag(source, drag, revision, enabled, send)
                        AgendaEventCard(item, scale.day, revision, enabled, onEdit, openCourse, send, Modifier.fillMaxSize(), move, compact = compact, focused = item.stableKey == focused)
                    }
                }
                for (group in scale.placement.more) key(group.start) {
                    val y = scale.yAt(group.start)
                    MoreEventsButton(group, onMore, Modifier
                        .offset(x = spine + 4.dp, y = with(density) { y.toDp() }).width(moreWidth).axisWheel(wheel, drag, y, null))
                }
            }
            val end = remember(scale.day) { clockLabel(scale.day.endMillis, scale.day) }
            val label = stringResource(R.string.today_end_boundary, end)
            val compact = endInGutter || scale.placement.visible.any { it.end == scale.day.endMillis }
            val clock = remember(scale.day) { clockLabel(scale.day.endMillis, scale.day, includeDate = false) }
            Text(if (compact) clock else label,
                Modifier.align(Alignment.BottomStart).padding(start = if (compact) 4.dp else spine + 12.dp)
                    .testTag("time-day-end").semantics { contentDescription = label },
                style = hourStyle, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxSize().padding(top = topInset).clipToBounds().testTag("day-drop-area")
            .onGloballyPositioned(drag::placeViewport)) {
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
    entry: AgendaOccurrence, selectedId: String?, select: (String?) -> Unit, drag: AxisDrag, revision: Long,
    enabled: Boolean, onEdit: (AgendaOccurrence) -> Unit, send: (PlannerCommand) -> Unit, modifier: Modifier,
) {
    val source = entry.dragSource()
    // A clipped continuation has no endpoint here. Its full interval is still editable by tapping the title.
    val resizable = entry.takeIf { source != null && !it.point && it.endMillis <= drag.scale.day.endMillis }
    val selected = resizable != null && resizable.uiId == selectedId
    val colors = MaterialTheme.colorScheme
    val ink = when {
        selected -> colors.primary
        entry.course != null -> colors.tertiary
        else -> colors.secondary
    }
    val barDescription = resizable?.let { stringResource(R.string.today_task_bar, it.title) }
    val handleState = if (selected) stringResource(R.string.today_handle_shown)
        else stringResource(R.string.today_hold_to_resize)
    val action = if (resizable == null) Modifier else Modifier.testTag("resize-bar:${resizable.uiId}")
        .combinedClickable(enabled = enabled, role = Role.Button, onLongClickLabel = stringResource(R.string.today_show_handle),
            onLongClick = { select(resizable.uiId) }, onClickLabel = if (selected) stringResource(R.string.today_hide_handle) else stringResource(R.string.today_show_handle),
            onClick = { select(if (selected) null else resizable.uiId) })
        .semantics {
            contentDescription = barDescription.orEmpty()
            stateDescription = handleState
        }
    Box(modifier) {
        Spacer(Modifier.fillMaxSize().then(action).drawBehind {
            val radius = 6.dp.toPx()
            val top = Offset(size.width / 2, radius)
            if (!entry.point && (!selected || drag.resizeRailX == null || drag.candidate == null)) {
                drawLine(ink.copy(alpha = if (selected) 1f else .65f), top,
                    Offset(top.x, size.height - 3.dp.toPx()), 5.dp.toPx(), StrokeCap.Round)
            }
            drawCircle(ink, radius, top)
            drawCircle(colors.surface, 2.dp.toPx(), top)
        })
        if (selected && resizable != null && source != null) {
            val endLabel = clockLabel(resizable.endMillis, drag.scale.day)
            val handleLabel = stringResource(R.string.today_end_handle, resizable.title, endLabel)
            var railX by remember { mutableFloatStateOf(Float.NaN) }
            LaunchedEffect(drag, resizable.uiId, railX) {
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
            val handle = rememberRouteLaunch("slot:${resizable.uiId}", CircleShape)
            Spacer(Modifier.align(Alignment.BottomCenter).size(48.dp).then(handle.modifier).testTag("resize-end:${resizable.uiId}")
                .onGloballyPositioned { railX = it.localToRoot(Offset(it.size.width / 2f, 0f)).x }
                .endDrag(source, drag, revision, enabled, send)
                .clickable(enabled, onClickLabel = stringResource(R.string.today_edit_exact_time), role = Role.Button) { handle.open { onEdit(resizable) } }
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

private val AgendaOccurrence.uiId: String get() = (key as? OccurrenceKey.Schedule)?.takeIf { it.originalDay == null }?.scheduleId ?: stableKey
private fun AgendaOccurrence.dragSource() = (key as? OccurrenceKey.Schedule)?.takeIf { editable && !completed }?.let {
    SlotSource(it, endMillis - startMillis, startMillis)
}

@Composable
internal fun AgendaEventCard(entry: AgendaOccurrence, day: PlannerQuery.Day, revision: Long, enabled: Boolean, onEdit: (AgendaOccurrence) -> Unit,
    openCourse: (ScheduledCourse) -> Unit, complete: (PlannerCommand) -> Unit, modifier: Modifier, move: Modifier = Modifier,
    wrap: Boolean = false, compact: Boolean = false, focused: Boolean = false) {
    val container = if (focused) modifier.background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium) else modifier
    val body: @Composable (Modifier) -> Unit = { area ->
        if (entry.course != null) CourseCard(entry.course, day, area, wrap, entry.completed, openCourse)
        else {
            val scheme = MaterialTheme.colorScheme
            val range = if (entry.point) stringResource(R.string.slot_point_at, clockLabel(entry.startMillis, day))
                else "${clockLabel(entry.startMillis, day)}–${clockLabel(entry.endMillis, day)}"
            val state = stringResource(when {
                entry.completed -> R.string.today_done
                entry.daily -> R.string.slot_daily
                entry.owner == Owner.FRAME -> R.string.today_stacked_occupied
                else -> R.string.today_queue
            })
            val launch = rememberRouteLaunch("slot:${entry.uiId}", MaterialTheme.shapes.medium)
            Column(area.testTag("block:${entry.uiId}").clip(MaterialTheme.shapes.medium).then(move).then(launch.modifier)
                .clickable(enabled, onClickLabel = stringResource(R.string.today_edit_time), role = Role.Button) { launch.open { onEdit(entry) } }
                .padding(horizontal = 4.dp)) {
                val lines = if (wrap) Int.MAX_VALUE else 1
                Text(range, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = lines, overflow = TextOverflow.Ellipsis)
                TaskIconTitle(entry.icon) {
                    Text(entry.title, style = MaterialTheme.typography.titleSmall, textDecoration = if (entry.completed) TextDecoration.LineThrough else null,
                        maxLines = lines, overflow = TextOverflow.Ellipsis)
                }
                Text(if (entry.conflicts.isEmpty()) state else stringResource(R.string.today_conflict_state, state), style = MaterialTheme.typography.labelMedium, color = scheme.secondary, maxLines = lines, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val check: @Composable () -> Unit = {
        val description = stringResource(if (entry.completed) R.string.agenda_reopen else if (entry.daily) R.string.slot_complete_this else R.string.agenda_complete, entry.title)
        Checkbox(checked = entry.completed, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = description },
            onCheckedChange = { complete(PlannerCommand.SetOccurrenceCompletion(entry.key, it, revision, entry.version)) })
    }
    if (compact) Column(container.padding(end = 2.dp)) { body(Modifier.fillMaxWidth().weight(1f)); check() }
    else Row(container) { body(Modifier.weight(1f).then(if (wrap) Modifier.heightIn(min = 48.dp) else Modifier.fillMaxHeight())); check() }
}

@Composable
private fun CourseCard(course: ScheduledCourse, day: PlannerQuery.Day, modifier: Modifier, wrap: Boolean, completed: Boolean, open: (ScheduledCourse) -> Unit) {
    val meeting = course.meeting
    val scheme = MaterialTheme.colorScheme
    val lines = if (wrap) Int.MAX_VALUE else 1
    Column(modifier.testTag("day-course").clip(MaterialTheme.shapes.medium)
        .clickable(onClickLabel = stringResource(R.string.today_view_class), role = Role.Button) { open(course) }.padding(horizontal = 4.dp)) {
        Text("${clockLabel(meeting.startMillis, day)}–${clockLabel(meeting.endMillis, day)}",
            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = lines, overflow = TextOverflow.Ellipsis)
        Text(meeting.title, style = MaterialTheme.typography.titleSmall, textDecoration = if (completed) TextDecoration.LineThrough else null,
            maxLines = lines, overflow = TextOverflow.Ellipsis)
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
    CurrentTimeMarker(drag, spine, millis)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun DropPreview(drag: AxisDrag, gutter: Dp) {
    val slot = drag.candidate ?: return
    val checked = drag.checked?.takeIf { it.slot == slot }
    val (start, end) = slot.interval
    val startY = drag.scale.yAt(start)
    val endY = drag.scale.yAt(end)
    val color = if (checked?.error == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val railX = drag.resizeRailX
    val density = LocalDensity.current
    // Scroll follows the real viewport immediately. Only the snapped geometry changes
    // inside this lookahead space; neither the domain check nor release reads animated bounds.
    Box(Modifier.fillMaxWidth().layout { measurable, constraints ->
        val child = measurable.measure(constraints.copy(minHeight = 0))
        layout(child.width, child.height) { child.place(0, -drag.scroll.value) }
    }.wrapContentHeight(Alignment.Top, unbounded = true)) {
        LookaheadScope {
            val bounds = if (LocalMotionEnabled.current) Modifier.animateBounds(this) else Modifier
            val height = if (slot.interval.point) 12.dp else with(density) { (endY - startY).toDp() }
            Box(Modifier.fillMaxWidth().offset(y = with(density) { startY.toDp() })
                .then(bounds).height(height).testTag("drop-interval")) {
                if (slot.interval.point) Spacer(Modifier.padding(start = gutter).size(12.dp).background(color, CircleShape))
                else Spacer(Modifier.fillMaxSize().padding(start = gutter, end = 4.dp)
                    .background(color.copy(alpha = .29f), MaterialTheme.shapes.medium))
                if (railX != null) {
                    val centerColor = MaterialTheme.colorScheme.surface
                    Spacer(Modifier.fillMaxSize().drawBehind {
                        val x = railX - drag.viewport.left
                        drawLine(color, Offset(x, 0f), Offset(x, size.height), 5.dp.toPx())
                        drawEndKnob(x, size.height, color, centerColor)
                    })
                }
            }
        }
    }
    val status = when {
        slot.command is PlannerCommand.SetOccurrenceOverride -> stringResource(R.string.slot_release_this)
        railX != null -> stringResource(R.string.today_release_resize)
        else -> stringResource(R.string.today_release_schedule)
    }
    Surface(Modifier.fillMaxWidth().padding(start = gutter, end = 4.dp, top = 2.dp).testTag("drop-preview"),
        color = if (checked?.error == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium, shadowElevation = 2.dp) {
        val range = if (slot.interval.point) stringResource(R.string.slot_point_at, clockLabel(start, drag.scale.day))
            else "${clockLabel(start, drag.scale.day)}–${clockLabel(end, drag.scale.day)}"
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text("$range · $status", style = MaterialTheme.typography.labelLarge)
            checked?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
