package io.github.nakanomk.stacklife.today

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.ScrollEdge
import io.github.nakanomk.stacklife.TaskAddButton
import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.workspace.symbolResource
import io.github.nakanomk.stacklife.markdown.markdownSummary
import io.github.nakanomk.stacklife.planner.DayPlan
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.taskFabMargin
import io.github.nakanomk.stacklife.taskFabSize
import io.github.nakanomk.stacklife.transitions.routeOrigin
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun DayWithTray(
    plan: DayPlan, drag: AxisDrag?, enabled: Boolean, onEdit: (String) -> Unit, onAdd: () -> Unit,
    top: Dp, clearance: Dp, expanded: Boolean, toggle: () -> Unit, hideForEnd: Boolean, schedule: ((PlannerCommand) -> Unit)? = null,
    content: @Composable (spine: Dp, eventListPadding: Dp) -> Unit,
) {
    val appearance = LocalAppearance.current
    val floating = appearance.floatingDayTray
    val target = if (expanded) 1f else 0f
    val progress = if (LocalMotionEnabled.current) animateFloatAsState(target,
        MaterialTheme.motionScheme.defaultSpatialSpec(), label = "queue container").value.coerceIn(0f, 1f) else target
    var lastTarget by remember(drag) { mutableStateOf(hideForEnd) }
    val hide = if (drag?.active == true) lastTarget else hideForEnd
    SideEffect { lastTarget = hide }
    val endTarget = if (hide) 1f else 0f
    val exit = if (LocalMotionEnabled.current) animateFloatAsState(endTarget,
        MaterialTheme.motionScheme.defaultSpatialSpec(), label = "day-end controls").value.coerceIn(0f, 1f) else endTarget
    val density = LocalDensity.current
    val type = MaterialTheme.typography
    val header = with(density) { (type.titleMedium.lineHeight.toDp() + 8.dp).coerceAtLeast(48.dp) } + if (floating) 0.dp else 8.dp
    val spacing = if (appearance.compact) 2.dp else 4.dp
    val body = remember(plan.tray, appearance.showNotes, spacing, density, type) {
        val count = minOf(4, plan.tray.size)
        if (count == 0) 48.dp else with(density) {
            val line = type.bodyLarge.lineHeight.toDp() + type.labelMedium.lineHeight.toDp() + 8.dp
            val note = type.bodySmall.lineHeight.toDp()
            var height = spacing * (count - 1)
            for (i in 0 until count) {
                val showNote = appearance.showNotes && plan.tray[i].notes.isNotBlank()
                height += (line + if (showNote) note else 0.dp).coerceAtLeast(48.dp)
            }
            height
        }
    }
    val lifted = drag?.trayPickup
    var host by remember { mutableStateOf<LayoutCoordinates?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize().onPlaced { host = it }) {
        val spine = daySpine()
        val wide = maxWidth >= 480.dp && maxWidth > maxHeight
        val usable = (maxHeight - top - clearance).coerceAtLeast(0.dp)
        val oneRow = wide && usable < taskFabSize * 2 + 12.dp
        val sideWidth = (maxWidth * .40f).coerceAtMost(420.dp)
        // Keep the clock column and spine visible even when the queue is opaque.
        val margin = if (floating) (usable - 48.dp).coerceIn(0.dp, 6.dp) else 0.dp
        val bottom = clearance + margin
        val actionSize = if (oneRow) (usable - margin).coerceIn(48.dp, taskFabSize) else taskFabSize
        val openEnd = if (floating) 8.dp else 0.dp
        val openWidth = if (wide) sideWidth else (maxWidth - spine - 12.dp - openEnd).coerceAtLeast(taskFabSize)
        val end = if (floating) lerp(taskFabMargin, openEnd, progress) else openEnd
        val limit = when {
            oneRow -> usable - margin
            wide -> usable - 12.dp
            else -> maxOf(usable * .44f, header + 48.dp)
        }
        // At large type a short landscape viewport can fit one target, not a header above it.
        // Keep the same list and toggle side by side so the first row is not wholly clipped.
        val inlineToggle = wide && limit < header + 48.dp
        val width = if (floating || inlineToggle) lerp(taskFabSize, openWidth, progress) else openWidth
        val openHeight = minOf((if (inlineToggle) 0.dp else header) + body + 8.dp, limit)
            .coerceAtLeast(if (inlineToggle) 48.dp else header)
        val closedHeight = if (oneRow) actionSize else if (floating) taskFabSize else header
        // Withdraw one real row's depth even when the full list already exceeds its height limit.
        // Only the floating controls change: the axis and its clearance remain exactly as placed.
        val targetHeight = if (lifted == null) openHeight else {
            val minimum = (if (inlineToggle) 0.dp else header) +
                (if (plan.tray.size > 1 || inlineToggle) 48.dp else 0.dp)
            (openHeight - with(density) { lifted.bounds.height.toDp() } - spacing).coerceIn(minOf(minimum, openHeight), openHeight)
        }
        val shownHeight = if (drag != null && LocalMotionEnabled.current) animateDpAsState(targetHeight,
            MaterialTheme.motionScheme.defaultSpatialSpec(), label = "queue pickup depth").value else targetHeight
        val height = lerp(closedHeight, shownHeight, progress)
        // If even the closed controls won't stack, keep + beside the tray for the entire
        // gesture. With more space it first clears the end pane, then moves down beside it.
        val fabEnd = when {
            oneRow -> end + width + 8.dp
            wide -> lerp(taskFabMargin, end + width + 8.dp, (progress * 3f).coerceAtMost(1f))
            else -> taskFabMargin
        }
        val fabBottom = when {
            oneRow -> bottom
            wide -> lerp(bottom + height + 4.dp, clearance + taskFabMargin, ((progress * 3f - 1f) / 2f).coerceIn(0f, 1f))
            else -> bottom + height + 4.dp
        }
        val axisSource = rememberPlannerBackdrop()
        Box(Modifier.fillMaxSize().plannerBackdrop(axisSource)) {
            Box(Modifier.fillMaxSize().padding(bottom = clearance)) {
                val rail = if (drag == null) Modifier.fillMaxHeight() else Modifier.layout { measurable, bounds ->
                    val visible = (drag.scale.height - drag.scroll.value).coerceIn(0, bounds.maxHeight)
                    val line = measurable.measure(bounds.copy(minHeight = visible, maxHeight = visible))
                    layout(line.width, line.height) { line.placeRelative(0, 0) }
                }
                Spacer(Modifier.padding(start = spine - 1.dp, top = top).width(2.dp).then(rail)
                    .testTag("day-rail").background(MaterialTheme.colorScheme.outlineVariant))
                val eventListPadding = if (drag == null) bottom - clearance + maxOf(closedHeight, openHeight) + taskFabSize + taskFabMargin else 0.dp
                content(spine, eventListPadding)
            }
        }
        CompositionLocalProvider(LocalCapsuleBackdrop provides axisSource) {
            if (exit == 1f && hide) SideEffect { drag?.addBounds = Rect.Zero; drag?.trayBounds = Rect.Zero }
            else {
                EndOverlay(Modifier.align(Alignment.BottomEnd).padding(end = fabEnd, bottom = fabBottom).size(actionSize), exit, taskFabMargin) {
                    TaskAddButton(onAdd, Modifier.fillMaxSize().onGloballyPositioned { drag?.addBounds = it.boundsInRoot() })
                }
                EndOverlay(Modifier.align(Alignment.BottomEnd).padding(end = end, bottom = bottom).size(width, height), exit, end) {
                    QueueTray(plan, drag, expanded, toggle, enabled, onEdit, schedule, progress, floating, axisSource != null, header, openWidth, inlineToggle,
                        Modifier.fillMaxSize().onGloballyPositioned { drag?.trayBounds = it.boundsInRoot() }.testTag("day-tray-container"))
                }
            }
            if (lifted != null && drag != null) {
                val shape = MaterialTheme.shapes.medium
                TrayTaskRow(lifted.task, draggable = true, modifier = Modifier.align(AbsoluteAlignment.TopLeft)
                    .absoluteOffset {
                        val coords = host
                        if (coords == null || !coords.isAttached) IntOffset.Zero else {
                            val at = coords.localPositionOf(coords.findRootCoordinates(), drag.pointer) - lifted.grabOffset
                            IntOffset(at.x.roundToInt(), at.y.roundToInt())
                        }
                    }
                    .size(with(density) { lifted.bounds.width.toDp() }, with(density) { lifted.bounds.height.toDp() })
                    .shadow(6.dp, shape).background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                    .testTag("day-tray-pickup").clearAndSetSemantics { hideFromAccessibility() })
            }
        }
    }
}

/** Measure the resting slot, not the moving control. Absolute placement also exits right in RTL. */
@Composable
private fun EndOverlay(modifier: Modifier, fraction: Float, margin: Dp, content: @Composable () -> Unit) {
    val right = LocalWindowInfo.current.containerSize.width
    var slot by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(modifier.onPlaced { slot = it }) {
        // Normal paging moves the entire day. A resting control doesn't need a window
        // coordinate, a per-frame state write, or its own translation layer for that.
        val shift = if (fraction == 0f) Modifier else Modifier.absoluteOffset {
            val coords = slot
            if (coords == null || !coords.isAttached) IntOffset.Zero
            else IntOffset(((right - coords.positionInWindow().x + margin.toPx()) * fraction).roundToInt(), 0)
        }
        Box(Modifier.fillMaxSize().then(shift)) { content() }
    }
}

@Composable
private fun QueueTray(
    plan: DayPlan, drag: AxisDrag?, expanded: Boolean, toggle: () -> Unit, enabled: Boolean,
    onEdit: (String) -> Unit, schedule: ((PlannerCommand) -> Unit)?, progress: Float,
    floating: Boolean, glass: Boolean, header: Dp, openWidth: Dp, inlineToggle: Boolean, modifier: Modifier,
) {
    val appearance = LocalAppearance.current
    val solid = MaterialTheme.colorScheme.surfaceContainerHigh
    val radius = if (floating) lerp(taskFabSize / 2, 16.dp, progress) else 0.dp
    val shape = remember(radius) { RoundedCornerShape(radius) }
    val listState = rememberLazyListState()
    val rows = if (drag == null || schedule == null) null else remember { mutableMapOf<String, LayoutCoordinates>() }
    val tasks = plan.tray
    val liftedId = drag?.trayPickup?.task?.id
    val shownTasks = remember(tasks, liftedId) { if (liftedId == null) tasks else tasks.filterNot { it.id == liftedId } }
    val motion = LocalMotionEnabled.current
    val underHeader by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    val interaction = remember { MutableInteractionSource() }
    StackLifeSurface(if (floating) CapsuleScene.TRAY else CapsuleScene.FROST, modifier, solid, shape = shape) {
        val edgeSource = rememberPlannerBackdrop(progress > 0f && underHeader)
        // Own the panel's blank space as well as the rows. Sibling event cards underneath
        // must never receive its taps. Child scroll, click and drag handlers remain in charge.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitCancellation() }) {
            if (progress > 0f) {
                LazyColumn(Modifier.fillMaxSize().wrapContentWidth(Alignment.End, unbounded = true).width(openWidth)
                    .plannerBackdrop(edgeSource).testTag("day-tray").scrollBoundaryHaptics(listState)
                    .then(if (drag != null && schedule != null && rows != null) Modifier.trayDrag(drag, plan, enabled && expanded, listState, rows, schedule) else Modifier), state = listState,
                    contentPadding = PaddingValues(start = 8.dp, end = if (inlineToggle) taskFabSize else 8.dp,
                        top = if (inlineToggle) 0.dp else header, bottom = if (inlineToggle) 0.dp else 6.dp),
                    verticalArrangement = Arrangement.spacedBy(if (appearance.compact) 2.dp else 4.dp)) {
                    if (tasks.isEmpty()) item {
                        Text(stringResource(R.string.today_tray_empty), Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    items(shownTasks, key = { it.id }) { task ->
                        val registration = if (rows == null) Modifier else {
                            DisposableEffect(task.id) { onDispose { rows.remove(task.id) } }
                            Modifier.onPlaced { rows[task.id] = it }
                        }
                        val placement = if (rows != null && motion) Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null) else Modifier
                        TrayTaskRow(task, draggable = rows != null, modifier = placement.fillMaxWidth()
                            .then(registration).testTag("tray:${task.id}")
                            .routeOrigin("slot:${task.id}", MaterialTheme.shapes.medium).clip(MaterialTheme.shapes.medium)
                            .clickable(enabled && expanded, onClickLabel = stringResource(R.string.today_schedule), role = Role.Button) { onEdit(task.id) })
                    }
                }
            }
            val barHeight = if (floating) lerp(taskFabSize, header, progress) else header
            val bar = if (inlineToggle) Modifier.align(Alignment.CenterEnd).width(taskFabSize).fillMaxHeight() else Modifier.fillMaxWidth().height(barHeight)
            if (!inlineToggle && glass && progress > 0f) {
                // The empty list inset already shows the glass shell. Re-sampling that shell
                // as a second header paints a stripe even with no rows behind the toggle.
                // Feather the material itself; bring in real row blur only as content crosses it.
                val feather = Modifier.fillMaxWidth().height(barHeight + 12.dp)
                val tint = remember(solid) { Brush.verticalGradient(listOf(solid.copy(alpha = .40f), solid.copy(alpha = 0f))) }
                Spacer(feather.background(tint, alpha = progress))
                if (edgeSource != null) ScrollEdge(edgeSource, feather.graphicsLayer {
                    alpha = if (listState.firstVisibleItemIndex > 0) 1f
                        else (listState.firstVisibleItemScrollOffset / 12.dp.toPx()).coerceIn(0f, 1f)
                })
            } else if (!inlineToggle && !glass && progress > 0f && underHeader) Spacer(bar.background(solid))
            val queueDescription = stringResource(R.string.today_tray_count, tasks.size)
            val queueState = if (expanded) stringResource(R.string.today_expanded) else stringResource(R.string.today_collapsed)
            val click = Modifier.testTag("day-tray-toggle").clickable(interactionSource = interaction, indication = null,
                onClickLabel = if (expanded) stringResource(R.string.today_collapse_queue) else stringResource(R.string.today_expand_queue), role = Role.Button, onClick = toggle).semantics {
                contentDescription = queueDescription; stateDescription = queueState
            }
            if (floating || inlineToggle) Box(bar.then(click)) {
                if (!inlineToggle && progress > 0f) Text(stringResource(R.string.today_tray_title, tasks.size), Modifier.align(Alignment.CenterStart).padding(start = 16.dp, end = 48.dp)
                    .graphicsLayer { alpha = progress }.clearAndSetSemantics {},
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TrayChevron(progress, Modifier.align(Alignment.CenterEnd).size(lerp(taskFabSize, 48.dp, progress))
                    .indication(interaction, ripple(bounded = false, radius = taskFabSize / 2)).wrapContentSize().size(24.dp))
            } else CompositionLocalProvider(LocalCapsuleBackdrop provides (edgeSource ?: LocalCapsuleBackdrop.current)) {
                StackLifeSurface(CapsuleScene.CHROME, Modifier.align(Alignment.TopCenter).padding(vertical = 4.dp, horizontal = 12.dp), solid, elevation = 1.dp) {
                    Row(click.heightIn(min = 48.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.today_tray_title, tasks.size), Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleMedium)
                        TrayChevron(progress, Modifier.size(24.dp).indication(interaction, ripple(bounded = false, radius = 24.dp)))
                    }
                }
            }
        }
    }
}

/** Same content on the placed row and above the axis; only the placed row owns actions and semantics. */
@Composable
private fun TrayTaskRow(task: QueueTask, draggable: Boolean, modifier: Modifier) {
    val appearance = LocalAppearance.current
    Row(modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (appearance.showNotes && task.notes.isNotBlank()) Text(remember(task.notes) { markdownSummary(task.notes) }, style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val action = stringResource(if (draggable) R.string.today_hold_to_drag else R.string.today_tap_to_schedule)
            Text(if (appearance.showDuration) stringResource(R.string.today_tray_duration, task.minutes, action) else action,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(symbolResource(task.icon)), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrayChevron(progress: Float, modifier: Modifier) {
    Icon(painterResource(R.drawable.ic_expand_less), null, modifier.graphicsLayer { rotationZ = 180f * progress },
        tint = MaterialTheme.colorScheme.onSurfaceVariant)
}
