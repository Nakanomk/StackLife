package io.github.nakanomk.stacklife.today

import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import io.github.nakanomk.stacklife.planner.DayPlan
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.taskFabMargin
import io.github.nakanomk.stacklife.taskFabSize
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun DayWithTray(
    plan: DayPlan, drag: AxisDrag?, enabled: Boolean, onEdit: (String) -> Unit, onAdd: () -> Unit,
    top: Dp, clearance: Dp, expanded: Boolean, toggle: () -> Unit, pickup: ((QueueTask) -> Modifier)? = null,
    content: @Composable (spine: Dp, eventListPadding: Dp) -> Unit,
) {
    val appearance = LocalAppearance.current
    val floating = appearance.floatingDayTray
    val target = if (expanded) 1f else 0f
    val progress = if (LocalMotionEnabled.current) animateFloatAsState(target,
        MaterialTheme.motionScheme.defaultSpatialSpec(), label = "queue container").value.coerceIn(0f, 1f) else target
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val spine = daySpine()
        val wide = maxWidth >= 480.dp && maxWidth > maxHeight
        val usable = (maxHeight - top - clearance).coerceAtLeast(0.dp)
        val sideWidth = (maxWidth * .40f).coerceAtMost(420.dp)
        // Keep the clock column and spine visible even when the queue is opaque.
        val bottom = clearance + if (floating) 6.dp else 0.dp
        val openEnd = if (floating) 8.dp else 0.dp
        val openWidth = if (wide) sideWidth else (maxWidth - spine - 12.dp - openEnd).coerceAtLeast(taskFabSize)
        val width = if (floating) lerp(taskFabSize, openWidth, progress) else openWidth
        val end = if (floating) lerp(taskFabMargin, openEnd, progress) else openEnd
        val limit = if (wide) usable - 12.dp else maxOf(usable * .44f, header + 48.dp)
        val openHeight = minOf(header + body + 8.dp, limit).coerceAtLeast(header)
        val closedHeight = if (floating) taskFabSize else header
        val height = lerp(closedHeight, openHeight, progress)
        // In a short wide window move + around the end pane, not over its rows: clear its
        // side first, then lower the button. Both legs use the same reversible progress.
        val alongside = wide
        val fabEnd = if (alongside) lerp(taskFabMargin, end + width + 8.dp, (progress * 3f).coerceAtMost(1f)) else taskFabMargin
        val fabBottom = when {
            alongside -> lerp(bottom + height + 4.dp, clearance + taskFabMargin, ((progress * 3f - 1f) / 2f).coerceIn(0f, 1f))
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
            TaskAddButton(onAdd, Modifier.align(Alignment.BottomEnd).padding(end = fabEnd, bottom = fabBottom)
                .onGloballyPositioned { drag?.addBounds = it.boundsInRoot() })
            QueueTray(plan.tray, expanded, toggle, enabled, onEdit, pickup, progress, floating, axisSource != null, header, openWidth,
                Modifier.align(Alignment.BottomEnd).padding(end = end, bottom = bottom).size(width, height)
                    .onGloballyPositioned { drag?.trayBounds = it.boundsInRoot() }.testTag("day-tray-container"))
        }
    }
}

@Composable
private fun QueueTray(
    tasks: List<QueueTask>, expanded: Boolean, toggle: () -> Unit, enabled: Boolean,
    onEdit: (String) -> Unit, pickup: ((QueueTask) -> Modifier)?, progress: Float,
    floating: Boolean, glass: Boolean, header: Dp, openWidth: Dp, modifier: Modifier,
) {
    val appearance = LocalAppearance.current
    val solid = MaterialTheme.colorScheme.surfaceContainerHigh
    val radius = if (floating) lerp(taskFabSize / 2, 16.dp, progress) else 0.dp
    val shape = remember(radius) { RoundedCornerShape(radius) }
    val listState = rememberLazyListState()
    val underHeader by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    val interaction = remember { MutableInteractionSource() }
    StackLifeSurface(if (floating) CapsuleScene.TRAY else CapsuleScene.FROST, modifier, solid, shape = shape) {
        val edgeSource = rememberPlannerBackdrop(progress > 0f && underHeader)
        // Own the panel's blank space as well as the rows. Sibling event cards underneath
        // must never receive its taps. Child scroll, click and drag handlers remain in charge.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitCancellation() }) {
            if (progress > 0f) {
                LazyColumn(Modifier.fillMaxSize().wrapContentWidth(Alignment.End, unbounded = true).width(openWidth)
                    .plannerBackdrop(edgeSource).testTag("day-tray"), state = listState,
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = header, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(if (appearance.compact) 2.dp else 4.dp)) {
                    if (tasks.isEmpty()) item {
                        Text(stringResource(R.string.today_tray_empty), Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    items(tasks, key = { it.id }) { task ->
                        Row(Modifier.fillMaxWidth().testTag("tray:${task.id}").clip(MaterialTheme.shapes.medium)
                            .then(pickup?.invoke(task) ?: Modifier)
                            .clickable(enabled && expanded, onClickLabel = stringResource(R.string.today_schedule), role = Role.Button) { onEdit(task.id) }
                            .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (appearance.showNotes && task.notes.isNotBlank()) Text(task.notes, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val action = if (pickup == null) stringResource(R.string.today_tap_to_schedule) else stringResource(R.string.today_hold_to_drag)
                                Text(if (appearance.showDuration) stringResource(R.string.today_tray_duration, task.minutes, action) else action,
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(painterResource(R.drawable.ic_schedule), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            val barHeight = if (floating) lerp(taskFabSize, header, progress) else header
            val bar = Modifier.fillMaxWidth().height(barHeight)
            if (glass && progress > 0f) {
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
            } else if (!glass && progress > 0f && underHeader) Spacer(bar.background(solid))
            val queueDescription = stringResource(R.string.today_tray_count, tasks.size)
            val queueState = if (expanded) stringResource(R.string.today_expanded) else stringResource(R.string.today_collapsed)
            val click = Modifier.testTag("day-tray-toggle").clickable(interactionSource = interaction, indication = null,
                onClickLabel = if (expanded) stringResource(R.string.today_collapse_queue) else stringResource(R.string.today_expand_queue), role = Role.Button, onClick = toggle).semantics {
                contentDescription = queueDescription; stateDescription = queueState
            }
            if (floating) Box(bar.then(click)) {
                if (progress > 0f) Text(stringResource(R.string.today_tray_title, tasks.size), Modifier.align(Alignment.CenterStart).padding(start = 16.dp, end = 48.dp)
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

@Composable
private fun TrayChevron(progress: Float, modifier: Modifier) {
    Icon(painterResource(R.drawable.ic_expand_less), null, modifier.graphicsLayer { rotationZ = 180f * progress },
        tint = MaterialTheme.colorScheme.onSurfaceVariant)
}
