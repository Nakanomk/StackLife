package io.github.nakanomk.stacklife.workspace

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.PagerPane
import io.github.nakanomk.stacklife.RootPane
import io.github.nakanomk.stacklife.RootToolbar
import io.github.nakanomk.stacklife.appearance.SegmentTrack
import io.github.nakanomk.stacklife.appearance.CapsuleScene
import io.github.nakanomk.stacklife.appearance.StackLifeSurface
import io.github.nakanomk.stacklife.AddTaskFab
import io.github.nakanomk.stacklife.taskFabClearance
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.markdown.markdownSummary
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.planner.Workspace
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.PlannerDropdownMenu
import io.github.nakanomk.stacklife.transitions.CardMetadata
import io.github.nakanomk.stacklife.transitions.CardSummary
import io.github.nakanomk.stacklife.transitions.CardTitle
import io.github.nakanomk.stacklife.transitions.DetailCard
import io.github.nakanomk.stacklife.transitions.LocalCardList
import io.github.nakanomk.stacklife.transitions.noCardBounds
import io.github.nakanomk.stacklife.transitions.detailListSource
import io.github.nakanomk.stacklife.transitions.title
import kotlinx.coroutines.flow.collect
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun WorkspaceScreen(
    state: Workspace,
    actions: WorkspaceViewModel,
    onTask: (String) -> Unit,
    onFrame: (String) -> Unit,
    onSettings: () -> Unit,
    shared: SharedModifier,
    stack: Boolean, setStack: (Boolean) -> Unit, onCreateFrame: () -> Unit,
    onAdd: () -> Unit,
) {
    val queueScroll = rememberLazyListState()
    val stackScroll = rememberLazyListState()
    val mode = if (stack) 1 else 0
    val pager = rememberPagerState(initialPage = mode) { 2 }
    val motion = LocalMotionEnabled.current
    LaunchedEffect(mode, motion) {
        if (pager.currentPage != mode || pager.currentPageOffsetFraction != 0f) {
            if (motion) pager.animateScrollToPage(mode, animationSpec = tween(320)) else pager.scrollToPage(mode)
        }
    }
    val position = remember(pager) { derivedStateOf { pager.currentPage + pager.currentPageOffsetFraction } }
    val pushedId by actions.pushedFrame.collectAsStateWithLifecycle()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(pushedId, editingId) {
        val frameId = pushedId ?: return@LaunchedEffect
        if (editingId != null) return@LaunchedEffect
        if (snackbar.showSnackbar(context.getString(R.string.ui_pushed), actionLabel = context.getString(R.string.ui_add_reason), withDismissAction = true,
                duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) editingId = frameId
        actions.dismissPush(frameId)
    }
    var feedbackHeight by remember { mutableIntStateOf(0) }
    val hasFeedback = editingId != null || pushedId != null
    val feedbackInset = if (hasFeedback) with(LocalDensity.current) { feedbackHeight.toDp() } else 0.dp
    RootPane(header = { RootToolbar(onSettings) { WorkspaceSegments(stack, setStack, position) } },
        overlay = { _, bottom ->
            if (!stack) AddTaskFab(onAdd, bottomInset = bottom + feedbackInset)
            if (hasFeedback) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottom)
                .onSizeChanged { feedbackHeight = it.height }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                val frameId = editingId
                if (frameId == null) SnackbarHost(snackbar) { data ->
                    PushFeedbackPanel(Modifier.testTag("push-feedback")) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(data.visuals.message, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium)
                            data.visuals.actionLabel?.let { label -> TextButton(onClick = data::performAction) { Text(label) } }
                            IconButton(onClick = data::dismiss) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.ui_close_panel)) }
                        }
                    }
                } else PushFeedbackPanel(Modifier) {
                    key(frameId) { PushReasonEditor(frameId, actions) { editingId = null } }
                }
            }
        }) { top, bottom ->
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = false, beyondViewportPageCount = 1) { index ->
            val selected = index == mode
            val inViewport by remember(pager, index) { derivedStateOf { kotlin.math.abs(position.value - index) < 1f } }
            PagerPane(selected, selected || inViewport) {
                val cardBounds = if (selected) shared else noCardBounds
                CompositionLocalProvider(LocalCardList provides if (index == 0) queueScroll else stackScroll) {
                    if (index == 1) FrameList(state.frames, stackScroll, onFrame, onCreateFrame, cardBounds, top, bottom + feedbackInset, state.revision, actions)
                    else QueueList(state.queue, queueScroll, actions, onTask, actions::pushQueue, cardBounds, top, bottom + taskFabClearance + feedbackInset, state.revision)
                }
            }
        }
    }
}

@Composable
private fun PushFeedbackPanel(modifier: Modifier, content: @Composable () -> Unit) {
    StackLifeSurface(CapsuleScene.ACTION, modifier, MaterialTheme.colorScheme.surfaceContainerHigh,
        elevation = 3.dp, shape = RoundedCornerShape(24.dp)) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
    }
}

@Composable
private fun WorkspaceSegments(stack: Boolean, change: (Boolean) -> Unit, position: State<Float>) {
    val colors = MaterialTheme.colorScheme
    val label = MaterialTheme.typography.labelLarge
    val inset = 4.dp
    SegmentTrack(position, 2, colors.secondaryContainer, { change(it == 1) },
        Modifier.widthIn(max = 168.dp).fillMaxWidth(), indicatorInset = inset) { highlighted ->
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            for (index in 0..1) {
                val frame = index == 1
                val selected = stack == frame
                Box(Modifier.weight(1f).clip(CircleShape).selectable(selected, role = Role.Tab) { change(frame) }
                    .heightIn(min = 48.dp).padding(vertical = inset), contentAlignment = Alignment.Center) {
                    Text(if (frame) stringResource(R.string.ui_stack) else stringResource(R.string.ui_queue),
                        style = label, color = if (highlighted == index) colors.primary else colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun QueueList(
    tasks: List<QueueTask>, scroll: LazyListState, actions: WorkspaceActions, onTask: (String) -> Unit,
    onPush: (List<String>) -> String?, shared: SharedModifier, topClearance: Dp, bottomClearance: Dp, revision: Long,
) {
    val compact = LocalAppearance.current.compact
    val source = detailListSource(scroll, topClearance, bottomClearance, revision)
    var order by remember(tasks) { mutableStateOf(tasks) }
    val settledSource = if (source != null && order != tasks) null else source
    // null means ordinary browsing: the primary action saves the entire visible queue.
    var selection by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val action = rememberSubmission(actions) {
        if (it == SaveOutcome.APPLIED) selection = null else order = tasks
    }
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
        contentPadding = dockListPadding(topClearance, bottomClearance), verticalArrangement = listSpacing) {
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                val selected = selection
                Box(Modifier.weight(1f)) { CardMetadata {
                    if (selected == null) {
                        Text(stringResource(R.string.queue_unfinished_count, tasks.size))
                        Text(stringResource(R.string.queue_estimate_minutes, tasks.sumOf { it.minutes.toLong() }))
                    } else Text(stringResource(R.string.ui_selected_count, selected.size))
                } }
                if (tasks.size > 1 || selected != null) TextButton(modifier = Modifier.testTag("queue-select"), enabled = !action.busy,
                    onClick = { selection = if (selected == null) arrayListOf() else null }) {
                    Text(if (selected == null) stringResource(R.string.ui_select_multiple) else stringResource(R.string.ui_cancel))
                }
                if (tasks.isNotEmpty()) ActionPill(if (selected == null) stringResource(R.string.ui_push) else stringResource(R.string.ui_push_selected),
                    modifier = Modifier.testTag("queue-push"),
                    enabled = !action.busy && (selected == null || selected.isNotEmpty()),
                    onClick = { action.submit { onPush(selected ?: tasks.map { it.id }) } })
            }
        }
        if (order.isEmpty()) item {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.fumo_face), contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.ui_empty_queue), style = MaterialTheme.typography.titleMedium)
                    Hint(stringResource(R.string.ui_empty_queue_hint))
                }
            }
        }
        itemsIndexed(order, key = { _, task -> task.id }) { index, task ->
            val selected = selection
            val verticalInset = if (compact) 4.dp else 8.dp
            val row = Modifier.fillMaxWidth().heightIn(min = if (compact) 52.dp else 64.dp)
                .padding(start = 16.dp, end = 8.dp, top = verticalInset, bottom = verticalInset)
            if (selected != null) {
                val checked = task.id in selected
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.toggleable(checked, enabled = !action.busy, role = Role.Checkbox) {
                        selection = ArrayList(selected.toggle(task.id))
                    }.then(row), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Checkbox(checked, onCheckedChange = null, enabled = !action.busy)
                        QueueTaskInfo(task, index, Modifier.weight(1f))
                    }
                }
                return@itemsIndexed
            }
            ReorderableItem(reorder, key = task.id) { dragging ->
                DetailCard(
                    source = if (dragging) null else settledSource, kind = "task", id = task.id, onDetail = onTask,
                    modifier = shared("task:${task.id}").fillMaxWidth().testTag("task:${task.id}"),
                    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = if (dragging) 6.dp else 0.dp,
                ) {
                    Row(row, verticalAlignment = Alignment.CenterVertically) {
                        QueueTaskInfo(task, index, Modifier.weight(1f), shared.title("task:${task.id}"))
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

@Composable
private fun QueueTaskInfo(task: QueueTask, index: Int, modifier: Modifier, titleModifier: Modifier = Modifier) {
    val appearance = LocalAppearance.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardTitle(task.title, titleModifier)
        CardMetadata {
            if (index == 0) Text(stringResource(R.string.ui_current_priority), color = MaterialTheme.colorScheme.primary)
            else Text(stringResource(R.string.ui_queue_position, index + 1))
            if (appearance.showDuration) Text(stringResource(R.string.ui_duration_estimate, task.minutes))
            task.schedule?.let { Text(stringResource(R.string.ui_scheduled_range, scheduleText(it))) }
        }
        if (appearance.showNotes && task.notes.isNotBlank()) CardSummary(remember(task.notes) { markdownSummary(task.notes) })
    }
}

private fun <T> List<T>.moved(from: Int, to: Int): List<T> = toMutableList().apply { add(to, removeAt(from)) }

@Composable
private fun MoveMenu(modifier: Modifier, title: String, index: Int, lastIndex: Int, enabled: Boolean, onMove: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.ui_move_task_hint, title)
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled,
            modifier = modifier.size(48.dp).semantics { contentDescription = description }) {
            Icon(painterResource(R.drawable.ic_drag_handle), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PlannerDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((label, target) in listOf(stringResource(R.string.ui_move_to_front) to 0, stringResource(R.string.ui_move_up_one) to index - 1, stringResource(R.string.ui_move_down_one) to index + 1, stringResource(R.string.ui_move_to_end) to lastIndex)) {
                DropdownMenuItem(text = { Text(label) }, enabled = target in 0..lastIndex && target != index,
                    onClick = { expanded = false; onMove(target) })
            }
        }
    }
}
