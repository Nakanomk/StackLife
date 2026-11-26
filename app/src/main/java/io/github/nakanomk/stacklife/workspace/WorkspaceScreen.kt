package io.github.nakanomk.stacklife.workspace

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.RootPane
import io.github.nakanomk.stacklife.RootToolbar
import io.github.nakanomk.stacklife.appearance.SegmentTrack
import io.github.nakanomk.stacklife.appearance.rememberNavigationPulse
import io.github.nakanomk.stacklife.appearance.scrollBoundaryHaptics
import io.github.nakanomk.stacklife.appearance.CapsuleScene
import io.github.nakanomk.stacklife.appearance.StackLifeSurface
import io.github.nakanomk.stacklife.appearance.CardFrostGroup
import io.github.nakanomk.stacklife.appearance.FrostedCard
import io.github.nakanomk.stacklife.AddTaskFab
import io.github.nakanomk.stacklife.taskFabClearance
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.markdown.markdownSummary
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.planner.Workspace
import io.github.nakanomk.stacklife.planner.WorkspaceEffect
import io.github.nakanomk.stacklife.planner.WorkspaceReceiptAction
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.planner.FrameDetailResult
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.i18n.uiText
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
import kotlinx.coroutines.delay
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
    val owner = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val queueSelection = rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val position = remember(pager) { derivedStateOf { pager.currentPage + pager.currentPageOffsetFraction } }
    val hasQueue = state.queue.isNotEmpty()
    val hasFrames = state.frames.isNotEmpty()
    val cardsVisible by remember(position, actions, owner, hasQueue, hasFrames) { derivedStateOf {
        actions.transfers.current?.owner == owner || (hasQueue && position.value < 1f) || (hasFrames && position.value > 0f)
    } }
    RootPane(header = { RootToolbar(Destination.WORKSPACE, onSettings) {
        WorkspaceSegments(stack, { transfersTo -> actions.transfers.cancel(owner); setStack(transfersTo) }, position)
    } },
        overlay = { _, bottom ->
            if (!stack) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottom)) { AddTaskFab(onAdd) }
        }) { top, bottom ->
        val density = LocalDensity.current
        val gutter = with(density) { dockListPadding(top, bottom).calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).roundToPx() }
        // The lists and the lifted card bodies sample the same stationary wallpaper donor.
        CardFrostGroup(enabled = cardsVisible) {
            WorkspaceTransferPager(state, actions.transfers, owner, stack, setStack, motion, pager, position, queueScroll, stackScroll, gutter, queueSelection) {
                    index, shown, selected, flight, selection ->
                val cardBounds = if (selected) shared else noCardBounds
                CompositionLocalProvider(LocalCardList provides if (index == 0) queueScroll else stackScroll) {
                    if (index == 1) FrameList(shown.frames, stackScroll, { id ->
                        actions.transfers.openFrame(owner, id, stackScroll.visibleCards(gutter))
                        onFrame(id)
                    }, onCreateFrame, cardBounds, top, bottom, shown.revision, actions, flight)
                    else QueueList(shown.queue, queueScroll, actions, onTask, cardBounds, top, bottom + taskFabClearance, shown.revision,
                        flight, queueSelection, selection) { ids, checked ->
                        actions.start(PlannerCommand.FramePush(taskIds = ids),
                            WorkspaceTransfer(owner, shown, null, queueScroll.visibleCards(gutter), checked))
                    }
                }
            }
        }
    }
}

internal val LocalWorkspaceFeedback = staticCompositionLocalOf<WorkspaceViewModel?> { null }

@Composable
internal fun WorkspaceFeedbackBar(actions: WorkspaceViewModel) {
    val feedback by actions.feedback.collectAsStateWithLifecycle()
    // Growing the bottom bar while the rows are airborne moves their viewport boundary;
    // a long queue then loses its last measured slot partway through its own transfer.
    // Present the new receipt on landing, without extending the transaction's undo period.
    val transfer = actions.transfers.current
    var previous by remember(actions) { mutableStateOf<WorkspaceViewModel.Feedback?>(null) }
    if (transfer == null) SideEffect { previous = feedback }
    val shown = if (transfer == null) feedback else previous
    val editor = actions.pushReason
    val editingId = editor?.frameId
    val pushId = (shown?.effect as? WorkspaceEffect.Pushed)?.frameId
    val mayAddReason = if (pushId == null) false else {
        val frame by remember(actions, pushId) { actions.read<FrameDetailResult>(PlannerQuery.Frame(pushId)) }
            .collectAsStateWithLifecycle(initialValue = ReadState.Loading)
        (frame as? ReadState.Loaded)?.value?.frame?.reason?.isBlank() == true
    }
    LaunchedEffect(feedback?.receipt?.requestId) {
        val receipt = feedback?.receipt ?: return@LaunchedEffect
        delay((requireNotNull(receipt.expiresAtElapsed) - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        actions.dismissFeedback(receipt.requestId)
    }
    val command = rememberSubmission(actions)
    PushFeedbackPanel(shown != null || editingId != null, editingId, Modifier) { frameId ->
        if (frameId != null) key(frameId) {
            if (editor?.frameId == frameId) PushReasonEditor(frameId, actions, editor.field) { actions.closeReason(frameId) }
        } else if (shown != null) ReceiptRow(shown, enabled = transfer == null && editingId == null && !command.busy, mayAddReason,
            onAction = { command.submit { actions.applyFeedback(shown.receipt.requestId, it) } },
            onEdit = { pushId?.let(actions::editReason) }, onDismiss = { actions.dismissFeedback(shown.receipt.requestId) })
    }
}

@Composable
private fun PushFeedbackPanel(visible: Boolean, editingId: String?, modifier: Modifier,
    content: @Composable (String?) -> Unit) {
    val spatial = MaterialTheme.motionScheme
    val motion = LocalMotionEnabled.current
    Box(modifier) {
        AnimatedVisibility(visible,
            enter = if (motion) expandVertically(spatial.defaultSpatialSpec(), Alignment.Bottom) else EnterTransition.None,
            exit = if (motion) shrinkVertically(spatial.defaultSpatialSpec(), Alignment.Bottom) else ExitTransition.None) {
            StackLifeSurface(CapsuleScene.ACTION, Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("push-feedback"),
                MaterialTheme.colorScheme.surfaceContainerHigh, elevation = 3.dp, shape = RoundedCornerShape(24.dp)) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    AnimatedContent(editingId, contentKey = { it != null }, contentAlignment = Alignment.BottomEnd,
                        label = "push reason", transitionSpec = {
                            if (!motion) (EnterTransition.None togetherWith ExitTransition.None).using(null)
                            else (expandHorizontally(spatial.defaultSpatialSpec(), Alignment.End) togetherWith
                                shrinkHorizontally(spatial.defaultSpatialSpec(), Alignment.End))
                                .using(SizeTransform { _, _ -> spatial.defaultSpatialSpec() })
                        }) { content(it) }
                }
            }
        }
    }
}

private val receiptTextPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)

@Composable
private fun ReceiptRow(shown: WorkspaceViewModel.Feedback, enabled: Boolean, mayAddReason: Boolean,
    onAction: (WorkspaceReceiptAction) -> Unit, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val effect = shown.effect
    Column(Modifier.fillMaxWidth().semantics {
        liveRegion = LiveRegionMode.Polite
        if (enabled) dismiss { onDismiss(); true }
    }.padding(start = 16.dp, end = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(when (effect) {
                is WorkspaceEffect.Pushed -> stringResource(R.string.ui_pushed)
                is WorkspaceEffect.Restored -> if (shown.kept) uiText("History kept", "已保留历史") else uiText("Restored to queue", "已恢复到队列")
                is WorkspaceEffect.Removed -> uiText("Removed from queue", "已移出队列")
                is WorkspaceEffect.DeletedHistory -> uiText("History deleted", "已删除历史")
            }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDismiss, enabled = enabled, modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.ui_close_panel), Modifier.size(14.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (effect is WorkspaceEffect.Restored && effect.sourceDeleted && !shown.kept)
                TextButton({ onAction(WorkspaceReceiptAction.KEEP_HISTORY) }, Modifier.weight(1f, fill = false).testTag("receipt-keep"),
                    enabled = enabled, contentPadding = receiptTextPadding) { Text(uiText("Keep history", "保留历史")) }
            else if (mayAddReason) TextButton(onEdit, Modifier.weight(1f, fill = false),
                enabled = enabled, contentPadding = receiptTextPadding) { Text(stringResource(R.string.ui_add_reason)) }
            IconButton(onClick = { onAction(WorkspaceReceiptAction.UNDO) }, modifier = Modifier.size(48.dp).testTag("receipt-undo"),
                enabled = enabled, colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                Icon(painterResource(R.drawable.ic_symbol_undo), uiText("Undo", "撤回"))
            }
        }
    }
}

@Composable
private fun WorkspaceSegments(stack: Boolean, change: (Boolean) -> Unit, position: State<Float>) {
    val colors = MaterialTheme.colorScheme
    val label = MaterialTheme.typography.labelLarge
    val inset = 4.dp
    val pulse = rememberNavigationPulse()
    var accepted = stack
    val select: (Boolean) -> Unit = { next -> if (next != accepted) { accepted = next; pulse(); change(next) } }
    SegmentTrack(position, 2, colors.secondaryContainer, { select(it == 1) },
        Modifier.widthIn(max = 168.dp).fillMaxWidth(), indicatorInset = inset) { highlighted ->
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            for (index in 0..1) {
                val frame = index == 1
                val selected = stack == frame
                Box(Modifier.weight(1f).clip(CircleShape).selectable(selected, role = Role.Tab) { select(frame) }
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
    shared: SharedModifier, topClearance: Dp, bottomClearance: Dp, revision: Long,
    flight: WorkspaceFlight?, selectionState: MutableState<ArrayList<String>?>, departingSelection: List<String>?, push: (List<String>, List<String>?) -> String?,
) {
    val source = detailListSource(scroll, topClearance, bottomClearance, revision)
    var order by remember(tasks) { mutableStateOf(tasks) }
    val settledSource = if (source != null && order != tasks) null else source
    // null means ordinary browsing: the primary action saves the entire visible queue.
    var selection by selectionState
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
    LazyColumn(Modifier.fillMaxSize().then(source?.modifier ?: Modifier).scrollBoundaryHaptics(scroll).testTag("queue"), state = scroll,
        contentPadding = dockListPadding(topClearance, bottomClearance), verticalArrangement = listSpacing) {
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                val selected = departingSelection ?: selection
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
                    onClick = { action.submit { push(selected ?: tasks.map { it.id }, selected) } })
            }
        }
        if (order.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.fumo_face), contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.ui_empty_queue), style = MaterialTheme.typography.titleMedium)
                Hint(stringResource(R.string.ui_empty_queue_hint))
            }
        }
        itemsIndexed(order, key = { _, task -> task.id }) { index, task ->
            val selected = departingSelection ?: selection
            if (flight?.Reserve(false, task.id) == true) return@itemsIndexed
            if (selected != null) {
                val checked = task.id in selected
                FrostedCard {
                    QueueCardBody(task, index, checked = checked, checkEnabled = !action.busy,
                        modifier = Modifier.toggleable(checked, enabled = !action.busy, role = Role.Checkbox) {
                            selection = ArrayList(selected.toggle(task.id))
                        })
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
                    QueueCardBody(task, index, titleModifier = shared.title("task:${task.id}")) {
                        if (order.size > 1) MoveMenu(
                            modifier = Modifier.draggableHandle(enabled = !action.busy, interactionSource = drags),
                            title = task.title, index = index, lastIndex = order.lastIndex, enabled = !action.busy,
                            onMove = { to -> action.send(PlannerCommand.QueueOrder(order.moved(index, to).map { it.id })) },
                        )
                    }
                }
            }
        }
    }
}

/** Shared by the grounded row and the receipt overlay; only the grounded row adds controls. */
@Composable
internal fun QueueCardBody(task: QueueTask, index: Int, checked: Boolean? = null, checkEnabled: Boolean = true,
    titleModifier: Modifier = Modifier, modifier: Modifier = Modifier, handle: @Composable () -> Unit = {}) {
    val compact = LocalAppearance.current.compact
    val inset = if (compact) 4.dp else 8.dp
    Row(modifier.fillMaxWidth().heightIn(min = if (compact) 52.dp else 64.dp)
        .padding(start = 16.dp, end = 8.dp, top = inset, bottom = inset), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (checked != null) Arrangement.spacedBy(12.dp) else Arrangement.Start) {
        if (checked != null) Checkbox(checked = checked, onCheckedChange = null, enabled = checkEnabled)
        QueueTaskInfo(task, index, Modifier.weight(1f), titleModifier)
        if (checked == null) handle()
    }
}

@Composable
internal fun QueueHandleGlyph() {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_drag_handle), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QueueTaskInfo(task: QueueTask, index: Int, modifier: Modifier, titleModifier: Modifier = Modifier) {
    val appearance = LocalAppearance.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TaskIconTitle(task.icon) { CardTitle(task.title, titleModifier) }
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
            QueueHandleGlyph()
        }
        PlannerDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((label, target) in listOf(stringResource(R.string.ui_move_to_front) to 0, stringResource(R.string.ui_move_up_one) to index - 1, stringResource(R.string.ui_move_down_one) to index + 1, stringResource(R.string.ui_move_to_end) to lastIndex)) {
                DropdownMenuItem(text = { Text(label) }, enabled = target in 0..lastIndex && target != index,
                    onClick = { expanded = false; onMove(target) })
            }
        }
    }
}
