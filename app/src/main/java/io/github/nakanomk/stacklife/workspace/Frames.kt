package io.github.nakanomk.stacklife.workspace

import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.transitions.DetailCard
import io.github.nakanomk.stacklife.transitions.DetailListSource
import io.github.nakanomk.stacklife.transitions.detailListSource
import io.github.nakanomk.stacklife.transitions.title

@Composable
internal fun FrameList(
    frames: List<FrameView>, scroll: LazyListState, onFrame: (String) -> Unit, onCreate: () -> Unit,
    shared: SharedModifier, topClearance: Dp, bottomClearance: Dp, revision: Long,
) {
    val source = detailListSource(scroll, topClearance, bottomClearance, revision)
    val available = remember(frames) {
        frames.indexOfFirst { it.remaining == 0 }.let { if (it == -1) frames.size else it }
    }
    LazyColumn(Modifier.fillMaxSize().then(source?.modifier ?: Modifier).testTag("frames"), state = scroll,
        contentPadding = dockListPadding(topClearance, bottomClearance), verticalArrangement = listSpacing) {
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ui_frame_counts, available, frames.size - available), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Secondary(stringResource(R.string.ui_new_frame), onClick = onCreate)
            }
        }
        if (frames.isEmpty()) item {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.ui_no_frames), style = MaterialTheme.typography.titleMedium)
                    Hint(stringResource(R.string.ui_no_frames_hint))
                }
            }
        }
        if (available > 0) item { SectionTitle(stringResource(R.string.ui_recent_frames)) }
        items(available, key = { frames[it].id }) { FrameCard(frames[it], onFrame, shared, source) }
        if (available < frames.size) item { SectionTitle(stringResource(R.string.ui_emptied_frames)) }
        items(frames.size - available, key = { frames[available + it].id }) {
            FrameCard(frames[available + it], onFrame, shared, source)
        }
    }
}

@Composable
private fun FrameCard(frame: FrameView, onFrame: (String) -> Unit, shared: SharedModifier, source: DetailListSource?) {
    val appearance = LocalAppearance.current
    DetailCard(source, "frame", frame.id, onFrame, modifier = shared("frame:${frame.id}").fillMaxWidth().testTag("frame:${frame.id}"),
        shape = RectangleShape, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(vertical = if (appearance.compact) 4.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(frameLabel(frame.reason, frame.source), shared.title("frame:${frame.id}"), style = MaterialTheme.typography.bodyLarge,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.ui_frame_summary, savedTime(frame.savedAtMillis), frame.items.size, frame.remaining),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(frame.items.joinToString("、", limit = 3, truncated = "…") { it.title }, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun FrameScreen(id: String, state: Workspace, actions: WorkspaceActions, onBack: () -> Unit, shared: SharedModifier) {
    val frame = state.frames.find { it.id == id }
    if (frame == null) {
        MissingDetail(stringResource(R.string.ui_frame_details), stringResource(R.string.ui_frame_missing), onBack)
        return
    }
    var choosing by rememberSaveable(id) { mutableStateOf(false) }
    var selected by rememberSaveable(id) { mutableStateOf(arrayListOf<String>()) }
    var editingReason by rememberSaveable(id) { mutableStateOf(false) }
    val entries = frame.items
    val leaveSelection = { choosing = false; selected = arrayListOf() }
    val action = rememberSubmission(actions) { if (it != SaveOutcome.REJECTED) leaveSelection() }
    val selectionIds = remember(entries, selected) {
        val ids = selected.toHashSet()
        entries.mapNotNull { if (it.taskId in ids) it.taskId else null }
    }
    BackHandler(enabled = choosing || action.busy) { if (!action.busy) leaveSelection() }
    EditorPage(stringResource(R.string.ui_frame_details), { if (choosing) leaveSelection() else onBack() }, AssistantPage.Frame(id), backEnabled = !action.busy,
        footer = if (editingReason) ({ PushReasonEditor(id, actions) { editingReason = false } })
        else if (!choosing) null else ({
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ui_restore_selection_count, selectionIds.size), style = MaterialTheme.typography.labelLarge)
                Actions {
                    Primary(stringResource(R.string.ui_restore_selected), enabled = !action.busy && selectionIds.isNotEmpty()) {
                        action.send(PlannerCommand.FrameRestore(id, RestoreMode.SELECTED, selectionIds))
                    }
                    Secondary(stringResource(R.string.ui_cancel_selection), enabled = !action.busy, onClick = leaveSelection)
                }
            }
        })) { top, bottom ->
        LazyColumn(contentPadding = dockListPadding(top, bottom), verticalArrangement = listSpacing) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val source = when (frame.source) {
                        FrameSource.PUSH -> stringResource(R.string.ui_source_queue)
                        FrameSource.MANUAL -> stringResource(R.string.ui_source_manual)
                        FrameSource.SWAP -> stringResource(R.string.ui_source_swap)
                        FrameSource.GRAPH -> stringResource(R.string.ui_source_graph)
                    }
                    Text(frameLabel(frame.reason, frame.source), shared.title("frame:$id"), style = MaterialTheme.typography.titleLarge)
                    Text("$source · ${savedTime(frame.savedAtMillis)}", style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.ui_frame_remaining, entries.size, frame.remaining), style = MaterialTheme.typography.bodyMedium)
                    if (frame.source == FrameSource.PUSH && frame.reason.isBlank() && !editingReason && !choosing)
                        TextButton(onClick = { editingReason = true }) { Text(stringResource(R.string.ui_add_reason)) }
                }
            }
            if (frame.remaining > 0) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(if (choosing) stringResource(R.string.ui_select_to_restore) else stringResource(R.string.ui_restore_to_queue))
                    Hint(restoreHint(state.restorePolicy, choosing, state.queue.size))
                    if (!choosing) Actions {
                        Primary(stringResource(R.string.ui_restore_all), enabled = !action.busy) { action.send(PlannerCommand.FrameRestore(id, RestoreMode.ALL)) }
                        Secondary(stringResource(R.string.ui_select_to_restore), enabled = !action.busy) { editingReason = false; choosing = true }
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.ui_saved_list))
                Hint(if (frame.remaining == 0) stringResource(R.string.ui_empty_frame_hint) else stringResource(R.string.ui_frame_history_hint))
            }
            itemsIndexed(entries, key = { _, item -> item.taskId }) { index, item ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.ui_entry_status, index + 1, stringResource(if (item.restorable) R.string.ui_restorable else R.string.ui_taken)), style = MaterialTheme.typography.labelMedium)
                        if (choosing && item.restorable) {
                            CheckRow(item.title, item.taskId in selected) { selected = ArrayList(selected.toggle(item.taskId)) }
                        } else Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Hint(stringResource(R.string.ui_minutes_value, item.minutes))
                        if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodyMedium)
                        if (item.scheduledStartMillis != null && item.scheduledEndMillis != null) {
                            Hint(stringResource(R.string.ui_saved_slot, savedTime(item.scheduledStartMillis), savedTime(item.scheduledEndMillis)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun restoreHint(policy: RestorePolicy, selected: Boolean, queueSize: Int): String = when (policy) {
    RestorePolicy.PREPEND -> stringResource(R.string.ui_prepend_detail)
    RestorePolicy.APPEND -> stringResource(R.string.ui_append_detail)
    RestorePolicy.SWAP -> when {
        selected -> stringResource(R.string.ui_swap_selected)
        queueSize == 0 -> stringResource(R.string.ui_swap_empty)
        else -> stringResource(R.string.ui_swap_all, queueSize)
    }
}
