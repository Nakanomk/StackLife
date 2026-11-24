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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.appearance.CardFrostGroup
import io.github.nakanomk.stacklife.appearance.FrostedCard
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.transitions.CardMetadata
import io.github.nakanomk.stacklife.transitions.CardSummary
import io.github.nakanomk.stacklife.transitions.CardTitle
import io.github.nakanomk.stacklife.transitions.DetailCard
import io.github.nakanomk.stacklife.transitions.DetailListSource
import io.github.nakanomk.stacklife.transitions.detailBodyMotion
import io.github.nakanomk.stacklife.transitions.detailListSource
import io.github.nakanomk.stacklife.transitions.title
import androidx.compose.foundation.shape.CircleShape
import io.github.nakanomk.stacklife.transitions.routeOrigin
import io.github.nakanomk.stacklife.notes.CurrentTaskNote
import io.github.nakanomk.stacklife.markdown.PlannerMarkdown

@Composable
internal fun FrameList(
    frames: List<FrameView>, scroll: LazyListState, onFrame: (String) -> Unit, onCreate: () -> Unit,
    shared: SharedModifier, topClearance: Dp, bottomClearance: Dp, revision: Long, actions: WorkspaceActions,
) {
    val source = detailListSource(scroll, topClearance, bottomClearance, revision)
    val available = remember(frames) {
        frames.indexOfFirst { it.remaining == 0 }.let { if (it == -1) frames.size else it }
    }
    var clearing by remember { mutableStateOf<List<String>?>(null) }
    val deletion = rememberSubmission(actions) { clearing = null }
    clearing?.let { ids -> ConfirmFrameDeletion(ids.size, deletion.busy, { clearing = null }) {
        deletion.send(PlannerCommand.DeleteFrameHistory(ids))
    } }
    LazyColumn(Modifier.fillMaxSize().then(source?.modifier ?: Modifier).testTag("frames"), state = scroll,
        contentPadding = dockListPadding(topClearance, bottomClearance), verticalArrangement = listSpacing) {
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { CardMetadata {
                    Text(stringResource(R.string.frames_available, available))
                    Text(stringResource(R.string.frames_emptied, frames.size - available))
                } }
                ActionPill(stringResource(R.string.ui_new_frame), onCreate, Modifier.routeOrigin("create-frame", CircleShape))
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
        if (available < frames.size) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle(stringResource(R.string.ui_emptied_frames)) }
                TextButton(modifier = Modifier.testTag("clear-frame-history"), enabled = !deletion.busy,
                    onClick = { clearing = frames.subList(available, frames.size).map { it.id } }) { Text(stringResource(R.string.agenda_clear_history)) }
            }
        }
        items(frames.size - available, key = { frames[available + it].id }) {
            FrameCard(frames[available + it], onFrame, shared, source)
        }
    }
}

@Composable
private fun FrameCard(frame: FrameView, onFrame: (String) -> Unit, shared: SharedModifier, source: DetailListSource?) {
    val appearance = LocalAppearance.current
    DetailCard(source, "frame", frame.id, onFrame, modifier = shared("frame:${frame.id}").fillMaxWidth().testTag("frame:${frame.id}"),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = if (appearance.compact) 6.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CardTitle(frameLabel(frame.reason, frame.source), shared.title("frame:${frame.id}"))
            CardMetadata {
                Text(stringResource(R.string.card_frame_saved_at, savedTime(frame.savedAtMillis)))
                Text(stringResource(R.string.card_frame_original_count, frame.items.size))
                Text(stringResource(R.string.card_frame_remaining_count, frame.remaining))
            }
            if (frame.items.isNotEmpty()) {
                val separator = stringResource(R.string.card_item_separator)
                val titles = remember(frame.items, separator) {
                    frame.items.joinToString(separator, limit = 3, truncated = "…") { it.current?.title ?: it.title }
                }
                CardSummary(titles)
            }
        }
    }
}

@Composable
internal fun FrameScreen(id: String, state: Workspace, actions: WorkspaceActions, onBack: () -> Unit, shared: SharedModifier,
    onSource: (GraphLink) -> Unit, onEdit: (String) -> Unit) {
    val frame = state.frames.find { it.id == id }
    var deleting by rememberSaveable(id) { mutableStateOf(false) }
    val deletion = rememberSubmission(actions) { result ->
        deleting = false
        if (result == SaveOutcome.APPLIED) onBack()
    }
    if (deleting) ConfirmFrameDeletion(1, deletion.busy, { deleting = false }) {
        deletion.send(PlannerCommand.DeleteFrameHistory(listOf(id)))
    }
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
        entries.mapNotNull { if (it.restorable && it.taskId in ids) it.taskId else null }
    }
    BackHandler(enabled = choosing || action.busy) { if (!action.busy) leaveSelection() }
    EditorPage(stringResource(R.string.ui_frame_details), { if (choosing) leaveSelection() else onBack() }, AssistantPage.Frame(id), backEnabled = !action.busy,
        footer = if (editingReason) ({ PushReasonEditor(id, actions) { editingReason = false } })
        else if (!choosing) null else ({
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ui_selected_count, selectionIds.size), style = MaterialTheme.typography.labelLarge)
                Hint(stringResource(R.string.frame_restore_order))
                Actions {
                    Primary(stringResource(R.string.ui_restore_selected), enabled = !action.busy && selectionIds.isNotEmpty()) {
                        action.send(PlannerCommand.FrameRestore(id, RestoreMode.SELECTED, selectionIds))
                    }
                    Secondary(stringResource(R.string.ui_cancel_selection), enabled = !action.busy, onClick = leaveSelection)
                }
            }
        })) { top, bottom -> CardFrostGroup(enabled = entries.isNotEmpty()) {
        LazyColumn(Modifier.detailBodyMotion(), contentPadding = dockListPadding(top, bottom), verticalArrangement = listSpacing) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val source = when (frame.source) {
                        FrameSource.PUSH -> stringResource(R.string.ui_source_queue)
                        FrameSource.MANUAL -> stringResource(R.string.ui_source_manual)
                        FrameSource.SWAP -> stringResource(R.string.ui_source_swap)
                        FrameSource.GRAPH -> stringResource(R.string.ui_source_graph)
                    }
                    Text(frameLabel(frame.reason, frame.source), shared.title("frame:$id"), style = MaterialTheme.typography.titleLarge)
                    CardMetadata {
                        Text(source)
                        Text(stringResource(R.string.card_frame_saved_at, savedTime(frame.savedAtMillis)))
                    }
                    CardMetadata {
                        Text(stringResource(R.string.card_frame_original_count, entries.size))
                        Text(stringResource(R.string.card_frame_remaining_count, frame.remaining))
                    }
                    if (frame.source == FrameSource.PUSH && frame.reason.isBlank() && !editingReason && !choosing)
                        TextButton(onClick = { editingReason = true }) { Text(stringResource(R.string.ui_add_reason)) }
                    if (frame.remaining == 0)
                        TextButton(modifier = Modifier.testTag("delete-frame-history"), onClick = { deleting = true }) {
                            Text(stringResource(R.string.agenda_delete_history), color = MaterialTheme.colorScheme.error)
                        }
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
                SectionTitle(stringResource(if (frame.remaining == 0) R.string.ui_saved_list else R.string.ui_frame_tasks))
                Hint(when { entries.isNotEmpty() && entries.all { it.referenceOnly } -> stringResource(R.string.graph_no_restorable)
                    frame.remaining == 0 -> stringResource(R.string.ui_empty_frame_hint)
                    else -> stringResource(R.string.ui_frame_history_hint) })
            }
            itemsIndexed(entries, key = { _, item -> item.originalPosition }) { index, item ->
                val current = item.current
                val title = current?.title ?: item.title
                val notes = current?.notes ?: item.notes
                val minutes = current?.minutes ?: item.minutes
                FrostedCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { CardMetadata {
                                Text(stringResource(R.string.frame_item_number, index + 1))
                                Text(stringResource(when { item.referenceOnly -> R.string.graph_linked_event; current != null -> R.string.ui_restorable; else -> R.string.ui_taken }))
                            } }
                            if (!choosing) item.link?.let { link -> TextButton(modifier = Modifier.testTag("frame-source:${item.originalPosition}"), onClick = { onSource(link) }, enabled = !action.busy) { Text(stringResource(R.string.graph_open_source)) } }
                            if (!choosing && current != null) ActionPill(stringResource(R.string.ui_edit_task), { onEdit(item.taskId) },
                                Modifier.routeOrigin("edit:${item.taskId}", CircleShape).testTag("frame-edit:${item.taskId}"), enabled = !action.busy)
                        }
                        if (choosing && item.restorable) {
                            CheckRow(title, item.taskId in selected) { selected = ArrayList(selected.toggle(item.taskId)) }
                        } else Text(title, style = MaterialTheme.typography.titleMedium)
                        Hint(stringResource(R.string.ui_minutes_value, minutes))
                        if (current != null && !choosing) CurrentTaskNote(current, state.revision, enabled = !action.busy, frameId = id)
                        else if (notes.isNotBlank()) PlannerMarkdown(notes, Modifier.fillMaxWidth())
                        if (current != null && (title != item.title || minutes != item.minutes || notes != item.notes)) {
                            SectionTitle(stringResource(R.string.frame_saved_snapshot))
                            Text(item.title, style = MaterialTheme.typography.bodyMedium)
                            Hint(stringResource(R.string.ui_minutes_value, item.minutes))
                            if (item.notes.isNotBlank()) PlannerMarkdown(item.notes, Modifier.fillMaxWidth())
                        }
                        item.occurrenceDay?.let { Text(java.time.LocalDate.ofEpochDay(it).toString(), style = MaterialTheme.typography.labelLarge) }
                        item.occurrenceInterval?.let { span ->
                            Text(if (span.point) savedTime(span.startMillis) else "${savedTime(span.startMillis)} – ${savedTime(span.endMillis)}")
                        }
                        item.schedule?.let { schedule ->
                            Hint(io.github.nakanomk.stacklife.i18n.uiText("Schedule when saved", "保存时的排程"))
                            Text(scheduleText(schedule), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    } }
}

@Composable
private fun ConfirmFrameDeletion(count: Int, busy: Boolean, close: () -> Unit, confirm: () -> Unit) {
    PlannerAlertDialog(onDismissRequest = { if (!busy) close() },
        title = { Text(stringResource(R.string.agenda_delete_history)) },
        text = { Text(stringResource(R.string.agenda_delete_history_message, count)) },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text(stringResource(R.string.ui_cancel)) } },
        confirmButton = { TextButton(onClick = confirm, enabled = !busy, modifier = Modifier.testTag("confirm-frame-deletion")) {
            Text(stringResource(R.string.agenda_delete), color = MaterialTheme.colorScheme.error)
        } })
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
