package io.github.nakanomk.stacklife.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import io.github.nakanomk.stacklife.DockOverlay
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.planner.Workspace
import kotlinx.coroutines.flow.collect
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private enum class Editor { PUSH, FRAME }

@Composable
fun WorkspaceScreen(
    state: Workspace,
    actions: WorkspaceActions,
    onTask: (String) -> Unit,
    onFrame: (String) -> Unit,
    onSettings: () -> Unit,
    shared: SharedModifier,
    navigationBar: @Composable () -> Unit,
    onAdd: () -> Unit,
) {
    var stack by rememberSaveable { mutableStateOf(false) }
    var editor by rememberSaveable { mutableStateOf<Editor?>(null) }
    val queueScroll = rememberLazyListState()
    val stackScroll = rememberLazyListState()
    val close = { editor = null }
    when (editor) {
        Editor.PUSH -> PushEditor(state.queue, actions, close)
        Editor.FRAME -> TaskEditor(actions, close, createFrame = true)
        null -> Page {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 20.dp)) {
                PageTitle("工作台", settings = onSettings)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    SegmentedButton(selected = !stack, onClick = { stack = false }, shape = SegmentedButtonDefaults.itemShape(0, 2),
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("队列") }
                    SegmentedButton(selected = stack, onClick = { stack = true }, shape = SegmentedButtonDefaults.itemShape(1, 2),
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("栈") }
                }
            }
            DockOverlay(navigationBar, if (stack) null else onAdd) { clearance ->
                if (stack) FrameList(state.frames, stackScroll, onFrame, { editor = Editor.FRAME }, shared, clearance)
                else QueueList(state.queue, queueScroll, actions, onTask, { editor = Editor.PUSH }, shared, clearance)
            }
        }
    }
}

@Composable
private fun QueueList(
    tasks: List<QueueTask>, scroll: LazyListState, actions: WorkspaceActions, onTask: (String) -> Unit,
    onPush: () -> Unit, shared: SharedModifier, bottomClearance: Dp,
) {
    var order by remember(tasks) { mutableStateOf(tasks) }
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
    LazyColumn(Modifier.fillMaxSize().testTag("queue"), state = scroll, contentPadding = dockListPadding(bottomClearance), verticalArrangement = listSpacing) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("待办队列")
                Hint("${tasks.size} 项未完成 · 预计 ${tasks.sumOf { it.minutes.toLong() }} 分钟")
                if (tasks.isNotEmpty()) Secondary("压栈", !action.busy, onPush)
            }
        }
        if (order.isEmpty()) item {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.fumo_face), contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("队列是空的", style = MaterialTheme.typography.titleLarge)
                    Hint("添加下一件要做的事，或从栈中恢复任务。")
                }
            }
        }
        itemsIndexed(order, key = { _, task -> task.id }) { index, task ->
            ReorderableItem(reorder, key = task.id) { dragging ->
                val head = index == 0
                Surface(
                    onClick = { onTask(task.id) }, modifier = shared("task:${task.id}").fillMaxWidth().testTag("task:${task.id}"),
                    shape = if (head) MaterialTheme.shapes.extraLarge else MaterialTheme.shapes.large,
                    color = if (head) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = if (dragging) 6.dp else 0.dp,
                ) {
                    Row(Modifier.padding(start = 18.dp, top = 14.dp, end = 6.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (head) "当前优先" else "第 ${index + 1} 项", style = MaterialTheme.typography.labelMedium)
                            Text(task.title, style = if (head) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("${task.minutes} 分钟", style = MaterialTheme.typography.bodyMedium)
                            if (task.scheduledStartMillis != null && task.scheduledEndMillis != null) {
                                Text("已安排：${savedTime(task.scheduledStartMillis)} 至 ${savedTime(task.scheduledEndMillis)}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (task.notes.isNotBlank()) Text(task.notes, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (order.size > 1) MoveMenu(
                            modifier = Modifier.longPressDraggableHandle(enabled = !action.busy, interactionSource = drags),
                            title = task.title, index = index, lastIndex = order.lastIndex, enabled = !action.busy,
                            onMove = { to -> action.send(PlannerCommand.QueueOrder(order.moved(index, to).map { it.id })) },
                        )
                    }
                }
            }
        }
    }
}

internal fun dockListPadding(bottom: Dp) = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = bottom + 12.dp)

private fun <T> List<T>.moved(from: Int, to: Int): List<T> = toMutableList().apply { add(to, removeAt(from)) }

@Composable
private fun MoveMenu(modifier: Modifier, title: String, index: Int, lastIndex: Int, enabled: Boolean, onMove: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val ink = LocalContentColor.current
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled,
            modifier = modifier.size(48.dp).semantics { contentDescription = "移动$title，长按拖动或点按选择位置" }) {
            Canvas(Modifier.size(22.dp)) {
                val left = 3.dp.toPx()
                val right = size.width - left
                val stroke = 2.dp.toPx()
                drawLine(ink, Offset(left, size.height * 0.35f), Offset(right, size.height * 0.35f), stroke, StrokeCap.Round)
                drawLine(ink, Offset(left, size.height * 0.65f), Offset(right, size.height * 0.65f), stroke, StrokeCap.Round)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((label, target) in listOf("移到队首" to 0, "上移一位" to index - 1, "下移一位" to index + 1, "移到队尾" to lastIndex)) {
                DropdownMenuItem(text = { Text(label) }, enabled = target in 0..lastIndex && target != index,
                    onClick = { expanded = false; onMove(target) })
            }
        }
    }
}
