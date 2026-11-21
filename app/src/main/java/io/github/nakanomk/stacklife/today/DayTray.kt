package io.github.nakanomk.stacklife.today

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
import io.github.nakanomk.stacklife.planner.NavStyle
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.taskFabMargin
import io.github.nakanomk.stacklife.taskFabSize
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun DayWithTray(
    plan: DayPlan, drag: AxisDrag, enabled: Boolean, onEdit: (String) -> Unit, onAdd: () -> Unit,
    send: (PlannerCommand.SlotSet) -> Unit, top: Dp, clearance: Dp, expanded: Boolean, toggle: () -> Unit,
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
        val wide = maxWidth >= 480.dp && maxWidth > maxHeight
        val usable = (maxHeight - top - clearance).coerceAtLeast(0.dp)
        val sideWidth = (maxWidth * .40f).coerceAtMost(420.dp)
        // The pinned tray keeps its full-width footer (and its old side pane on wide screens).
        val underDock = !floating && !wide && LocalAppearance.current.navStyle == NavStyle.FLOATING
        val footer = if (underDock) clearance else 0.dp
        val bottom = if (floating) clearance + 6.dp else clearance - footer
        val openWidth = if (wide) sideWidth else if (floating) maxWidth - 16.dp else maxWidth
        val width = lerp(if (floating) 48.dp else maxWidth, openWidth, progress)
        val end = if (floating) lerp(taskFabMargin + (taskFabSize - 48.dp) / 2, 8.dp, progress)
            else if (wide) (maxWidth - sideWidth) * progress else 0.dp
        val limit = if (wide) usable - 12.dp else maxOf(usable * .44f, header + 48.dp)
        val openHeight = (if (wide && !floating) usable else minOf(header + body + 8.dp, limit)).coerceAtLeast(48.dp) + footer
        val closedHeight = if (floating) 48.dp else header + footer
        val height = lerp(closedHeight, openHeight, progress)
        val axisBottom = when {
            floating -> 0.dp
            wide -> lerp(bottom + closedHeight, clearance, progress)
            else -> bottom + height
        }
        val axisStart = if (wide && !floating) sideWidth * progress else 0.dp
        // In a short wide window move + around the end pane, not over its rows: clear its
        // side first, then lower the button. Both legs use the same reversible progress.
        val alongside = wide && floating
        val fabEnd = if (alongside) lerp(taskFabMargin, end + width + 8.dp, (progress * 3f).coerceAtMost(1f)) else taskFabMargin
        val fabBottom = when {
            alongside -> lerp(bottom + height + 4.dp, clearance + taskFabMargin, ((progress * 3f - 1f) / 2f).coerceIn(0f, 1f))
            floating -> bottom + height + 4.dp
            wide -> lerp(bottom + height + taskFabMargin, axisBottom + taskFabMargin,
                ((end - taskFabMargin - taskFabSize) / taskFabMargin).coerceIn(0f, 1f))
            else -> axisBottom + taskFabMargin
        }
        val axisSource = rememberPlannerBackdrop()
        Box(Modifier.fillMaxSize().plannerBackdrop(axisSource)) {
            DayAxis(drag, plan.revision, enabled, onEdit, send, Modifier.fillMaxSize().padding(start = axisStart, bottom = axisBottom),
                top, (fabBottom + taskFabSize + 8.dp - axisBottom).coerceAtLeast(0.dp), (clearance - axisBottom).coerceAtLeast(0.dp))
        }
        CompositionLocalProvider(LocalCapsuleBackdrop provides axisSource) {
            TaskAddButton(onAdd, Modifier.align(Alignment.BottomEnd).padding(end = fabEnd, bottom = fabBottom)
                .onGloballyPositioned { drag.addBounds = it.boundsInRoot() })
            QueueTray(plan.tray, plan.revision, drag, expanded, toggle, enabled, onEdit, send, progress, floating, axisSource != null, header, footer, openWidth,
                Modifier.align(Alignment.BottomEnd).padding(end = end, bottom = bottom).size(width, height)
                    .onGloballyPositioned { drag.trayBounds = it.boundsInRoot() }.testTag("day-tray-container"))
        }
    }
}

@Composable
private fun QueueTray(
    tasks: List<QueueTask>, revision: Long, drag: AxisDrag, expanded: Boolean, toggle: () -> Unit,
    enabled: Boolean, onEdit: (String) -> Unit, send: (PlannerCommand.SlotSet) -> Unit, progress: Float,
    floating: Boolean, glass: Boolean, header: Dp, footer: Dp, openWidth: Dp, modifier: Modifier,
) {
    val appearance = LocalAppearance.current
    val solid = MaterialTheme.colorScheme.surfaceContainerHigh
    val radius = if (floating) lerp(24.dp, 16.dp, progress) else 0.dp
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
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = header, bottom = footer + 6.dp),
                    verticalArrangement = Arrangement.spacedBy(if (appearance.compact) 2.dp else 4.dp)) {
                    if (tasks.isEmpty()) item {
                        Text("队列中没有待排任务", Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    items(tasks, key = { it.id }) { task ->
                        Row(Modifier.fillMaxWidth().testTag("tray:${task.id}").clip(MaterialTheme.shapes.medium)
                            .slotDrag(SlotSource(task.id, task.minutes), drag, revision, enabled && expanded, send)
                            .clickable(enabled && expanded, onClickLabel = "安排时间", role = Role.Button) { onEdit(task.id) }
                            .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (appearance.showNotes && task.notes.isNotBlank()) Text(task.notes, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (appearance.showDuration) "${task.minutes} 分钟 · 长按拖动" else "长按拖动",
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(painterResource(R.drawable.ic_schedule), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            val barHeight = if (floating) lerp(48.dp, header, progress) else header
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
            val click = Modifier.testTag("day-tray-toggle").clickable(interactionSource = interaction, indication = null,
                onClickLabel = if (expanded) "收起待排队列" else "展开待排队列", role = Role.Button, onClick = toggle).semantics {
                contentDescription = "待排队列，${tasks.size} 项"; stateDescription = if (expanded) "已展开" else "已收起"
            }
            if (floating) Box(bar.then(click)) {
                if (progress > 0f) Text("待排队列 · ${tasks.size}", Modifier.align(Alignment.CenterStart).padding(start = 16.dp, end = 48.dp)
                    .graphicsLayer { alpha = progress }.clearAndSetSemantics {},
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TrayChevron(progress, Modifier.align(Alignment.CenterEnd).size(48.dp)
                    .indication(interaction, ripple(bounded = false, radius = 24.dp)).padding(12.dp))
            } else CompositionLocalProvider(LocalCapsuleBackdrop provides (edgeSource ?: LocalCapsuleBackdrop.current)) {
                StackLifeSurface(CapsuleScene.CHROME, Modifier.align(Alignment.TopCenter).padding(vertical = 4.dp, horizontal = 12.dp), solid, elevation = 1.dp) {
                    Row(click.heightIn(min = 48.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("待排队列 · ${tasks.size}", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleMedium)
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
