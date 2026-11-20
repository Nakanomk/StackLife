package io.github.nakanomk.stacklife.workspace

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import io.github.nakanomk.stacklife.RootPane
import io.github.nakanomk.stacklife.RootToolbar
import io.github.nakanomk.stacklife.SegmentTrack
import io.github.nakanomk.stacklife.AddTaskFab
import io.github.nakanomk.stacklife.taskFabClearance
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.planner.Workspace
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.PlannerDropdownMenu
import io.github.nakanomk.stacklife.transitions.DetailCard
import io.github.nakanomk.stacklife.transitions.detailListSource
import kotlinx.coroutines.flow.collect
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun WorkspaceScreen(
    state: Workspace,
    actions: WorkspaceActions,
    onTask: (String) -> Unit,
    onFrame: (String) -> Unit,
    onSettings: () -> Unit,
    shared: SharedModifier,
    stack: Boolean, setStack: (Boolean) -> Unit, onPush: () -> Unit, onCreateFrame: () -> Unit,
    onAdd: () -> Unit,
) {
    val queueScroll = rememberLazyListState()
    val stackScroll = rememberLazyListState()
    RootPane(header = { RootToolbar(onSettings) { WorkspaceSegments(stack, setStack) } },
        overlay = { _, bottom -> if (!stack) AddTaskFab(onAdd, bottomInset = bottom) }) { top, bottom ->
        if (stack) FrameList(state.frames, stackScroll, onFrame, onCreateFrame, shared, top, bottom, state.revision)
        else QueueList(state.queue, queueScroll, actions, onTask, onPush, shared, top, bottom + taskFabClearance, state.revision)
    }
}

@Composable
private fun WorkspaceSegments(stack: Boolean, change: (Boolean) -> Unit) {
    val position = animateFloatAsState(if (stack) 1f else 0f, if (LocalMotionEnabled.current) tween(200) else snap(), label = "workspace segment")
    val colors = MaterialTheme.colorScheme
    SegmentTrack(position, 2, colors.secondaryContainer, Modifier.widthIn(max = 168.dp).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            for (index in 0..1) {
                val frame = index == 1
                val selected = stack == frame
                Box(Modifier.weight(1f).clip(CircleShape).selectable(selected, role = Role.Tab) { change(frame) }
                    .heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    Text(if (frame) "栈" else "队列", style = MaterialTheme.typography.labelLarge,
                        color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun QueueList(
    tasks: List<QueueTask>, scroll: LazyListState, actions: WorkspaceActions, onTask: (String) -> Unit,
    onPush: () -> Unit, shared: SharedModifier, topClearance: Dp, bottomClearance: Dp, revision: Long,
) {
    val appearance = LocalAppearance.current
    val source = detailListSource(scroll, topClearance, bottomClearance, revision)
    var order by remember(tasks) { mutableStateOf(tasks) }
    val settledSource = if (source != null && order != tasks) null else source
    val action = rememberSubmission(actions) { if (it != SaveOutcome.APPLIED) order = tasks }
    val drags = remember { MutableInteractionSource() }
    val reorder = rememberReorderableLazyListState(scroll) { from, to ->
        val start = order.indexOfFirst { it.id == from.key }
        val end = order.indexOfFirst { it.id == to.key }
        if (start >= 0 && end >= 0 && start != end) order = order.moved(start, end)
    }
    // The library's onDragStopped fires for cancellations too. Only Stop represents a drop.
    LaunchedEffect(drags, tasks) {
        drags.interactions.collect { event ->
            if (event is DragInteraction.Cancel) order = tasks
            if (event is DragInteraction.Stop && order != tasks) {
                if (!action.send(PlannerCommand.QueueOrder(order.map { it.id }))) order = tasks
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().then(source?.modifier ?: Modifier).testTag("queue"), state = scroll,
        contentPadding = dockListPadding(topClearance, bottomClearance)) {
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${tasks.size} 项未完成 · 预计 ${tasks.sumOf { it.minutes.toLong() }} 分钟", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tasks.isNotEmpty()) Secondary("压栈", !action.busy, onPush)
            }
        }
        if (order.isEmpty()) item {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.fumo_face), contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("队列是空的", style = MaterialTheme.typography.titleMedium)
                    Hint("添加下一件要做的事，或从栈中恢复任务。")
                }
            }
        }
        itemsIndexed(order, key = { _, task -> task.id }) { index, task ->
            ReorderableItem(reorder, key = task.id) { dragging ->
                val head = index == 0
                DetailCard(
                    source = if (dragging) null else settledSource, kind = "task", id = task.id, onDetail = onTask,
                    modifier = shared("task:${task.id}").fillMaxWidth().testTag("task:${task.id}"),
                    shape = RectangleShape, color = MaterialTheme.colorScheme.surface,
                    shadowElevation = if (dragging) 6.dp else 0.dp,
                ) {
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    Row(Modifier.fillMaxWidth().heightIn(min = if (appearance.compact) 52.dp else 64.dp)
                        .padding(vertical = if (appearance.compact) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (head) Text("当前优先", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            val meta = remember(task.minutes, task.notes, appearance.showDuration, appearance.showNotes) {
                                listOfNotNull("${task.minutes} 分钟".takeIf { appearance.showDuration },
                                    task.notes.takeIf { appearance.showNotes && it.isNotBlank() }).joinToString(" · ")
                            }
                            if (meta.isNotEmpty()) Text(meta, color = muted, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (task.scheduledStartMillis != null && task.scheduledEndMillis != null)
                                Text("已安排 ${scheduledRange(task.scheduledStartMillis, task.scheduledEndMillis)}", color = muted,
                                    style = MaterialTheme.typography.bodySmall)
                        }
                        if (order.size > 1) MoveMenu(
                            modifier = Modifier.longPressDraggableHandle(enabled = !action.busy, interactionSource = drags),
                            title = task.title, index = index, lastIndex = order.lastIndex, enabled = !action.busy,
                            onMove = { to -> action.send(PlannerCommand.QueueOrder(order.moved(index, to).map { it.id })) },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}


private fun <T> List<T>.moved(from: Int, to: Int): List<T> = toMutableList().apply { add(to, removeAt(from)) }

@Composable
private fun MoveMenu(modifier: Modifier, title: String, index: Int, lastIndex: Int, enabled: Boolean, onMove: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled,
            modifier = modifier.size(48.dp).semantics { contentDescription = "移动$title，长按拖动或点按选择位置" }) {
            Icon(painterResource(R.drawable.ic_drag_handle), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PlannerDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((label, target) in listOf("移到队首" to 0, "上移一位" to index - 1, "下移一位" to index + 1, "移到队尾" to lastIndex)) {
                DropdownMenuItem(text = { Text(label) }, enabled = target in 0..lastIndex && target != index,
                    onClick = { expanded = false; onMove(target) })
            }
        }
    }
}
