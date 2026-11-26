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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.appearance.CardFrostGroup
import io.github.nakanomk.stacklife.appearance.FrostedCard
import io.github.nakanomk.stacklife.appearance.scrollBoundaryHaptics
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
    flight: WorkspaceFlight?,
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
    LazyColumn(Modifier.fillMaxSize().then(source?.modifier ?: Modifier).scrollBoundaryHaptics(scroll).testTag("frames"), state = scroll,
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
        items(available, key = { frames[it].id }) { FrameCard(frames[it], onFrame, shared, source, flight) }
        if (available < frames.size) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle(stringResource(R.string.ui_emptied_frames)) }
                TextButton(modifier = Modifier.testTag("clear-frame-history"), enabled = !deletion.busy,
                    onClick = { clearing = frames.subList(available, frames.size).map { it.id } }) { Text(stringResource(R.string.agenda_clear_history)) }
            }
        }
        items(frames.size - available, key = { frames[available + it].id }) {
            FrameCard(frames[available + it], onFrame, shared, source, flight)
        }
    }
}

@Composable
private fun FrameCard(frame: FrameView, onFrame: (String) -> Unit, shared: SharedModifier, source: DetailListSource?, flight: WorkspaceFlight?) {
    if (flight?.Reserve(true, frame.id) == true) return
    DetailCard(source, "frame", frame.id, onFrame, modifier = shared("frame:${frame.id}").fillMaxWidth().testTag("frame:${frame.id}"),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        FrameCardBody(frame, shared.title("frame:${frame.id}"))
    }
}

@Composable
internal fun FrameCardBody(frame: FrameView, titleModifier: Modifier = Modifier) {
    val appearance = LocalAppearance.current
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = if (appearance.compact) 6.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CardTitle(frameLabel(frame.reason, frame.source), titleModifier)
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

@Composable
internal fun FrameScreen(id: String, state: Workspace, actions: io.github.nakanomk.stacklife.WorkspaceViewModel, onBack: () -> Unit, shared: SharedModifier,
    onSource: (GraphLink) -> Unit, onEdit: (String) -> Unit) = SettingsHelpHost {
    // Deep links, graph-generated frames and assistant actions have no workspace departure.
    val owner by rememberSaveable(id) { mutableStateOf(actions.transfers.claimFrame(id)) }
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(id, owner, actions, activity) {
        onDispose {
            actions.transfers.current?.takeIf { it.fromFrame == id && it.owner == owner }?.let { transfer ->
                // Navigation retains this entry through its actual pop. Only release the list
                // anchor to the pager once that entry is gone, not while the detail is landing.
                if (transfer.closingFrame) transfer.returningToPager = true
                else if (activity?.isChangingConfigurations != true) actions.transfers.end(transfer)
            }
        }
    }
    val departure = actions.transfers.current?.takeIf { it.fromFrame == id && it.owner == owner }
    val frame = state.frames.find { it.id == id } ?: departure?.before?.frames?.find { it.id == id }
    val front = io.github.nakanomk.stacklife.transitions.LocalFrontEntry.current
    var deleting by rememberSaveable(id) { mutableStateOf(false) }
    val deletion = rememberSubmission(actions) { result ->
        deleting = false
        if (result == SaveOutcome.APPLIED) onBack()
    }
    if (deleting) ConfirmFrameDeletion(1, deletion.busy, { deleting = false }) {
        deletion.send(PlannerCommand.DeleteFrameHistory(listOf(id)))
    }
    var restoreTicket by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var choosing by rememberSaveable(id) { mutableStateOf(false) }
    var selected by rememberSaveable(id) { mutableStateOf(arrayListOf<String>()) }
    var editingReason by rememberSaveable(id) { mutableStateOf(false) }
    val entries = frame?.items.orEmpty()
    val leaveSelection = { choosing = false; selected = arrayListOf() }
    val action = rememberSubmission(actions) { outcome ->
        val ticket = restoreTicket
        val own = actions.transfers.current?.takeIf { it.submission == ticket && it.owner == owner && it.fromFrame == id }
        val effect = (own?.receipt ?: ticket?.let(actions::receipt))?.effects?.singleOrNull() as? WorkspaceEffect.Restored
        restoreTicket = null
        if (outcome != SaveOutcome.REJECTED) leaveSelection()
        if (front && outcome == SaveOutcome.APPLIED && effect?.frameId == id && (own != null || owner == null && effect.sourceDeleted)) {
            own?.closingFrame = true
            onBack()
        } else own?.let(actions.transfers::end)
    }
    fun restore(cmd: PlannerCommand.FrameRestore) = action.submit {
        val origin = actions.transfers.frameOrigin?.takeIf { it.frameId == id && it.owner == owner }
        // After process death the saveable launch owner can still receive a *new* explicit
        // restore, but no geometry from the previous window can participate in its flight.
        actions.start(cmd, owner?.let { WorkspaceTransfer(it, state, id,
            origin?.rows ?: VisibleCards(androidx.compose.ui.unit.IntSize.Zero, emptyMap())) })?.also { restoreTicket = it }
    }
    if (frame == null) {
        MissingDetail(stringResource(R.string.ui_frame_details), stringResource(R.string.ui_frame_missing), onBack)
        return@SettingsHelpHost
    }
    val selectionIds = remember(entries, selected) {
        val ids = selected.toHashSet()
        entries.mapNotNull { if (it.restorable && it.taskId in ids) it.taskId else null }
    }
    BackHandler(enabled = choosing || action.busy) { if (!action.busy) leaveSelection() }
    EditorPage(stringResource(R.string.ui_frame_details), backAfterSettingHelp { if (choosing) leaveSelection() else onBack() }, AssistantPage.Frame(id), backEnabled = !action.busy,
        footer = if (editingReason) ({ PushReasonEditor(id, actions) { editingReason = false } })
        else if (!choosing) null else ({
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ui_selected_count, selectionIds.size), style = MaterialTheme.typography.labelLarge)
                Hint(stringResource(R.string.frame_restore_order))
                Actions {
                    Primary(stringResource(R.string.ui_restore_selected), enabled = !action.busy && selectionIds.isNotEmpty()) {
                        restore(PlannerCommand.FrameRestore(id, RestoreMode.SELECTED, selectionIds))
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
                    val preferences by actions.settings.collectAsStateWithLifecycle()
                    val keep = (preferences as? ReadState.Loaded)?.value?.keepRestoredFrames
                    val history = when {
                        choosing && selectionIds.size < frame.remaining -> R.string.frame_help_partial
                        keep == true -> R.string.frame_help_kept
                        keep == false -> R.string.frame_help_removed
                        else -> null
                    }
                    val order = restoreHint(state.restorePolicy, choosing, state.queue.size)
                    SettingHeading(stringResource(if (choosing) R.string.ui_select_to_restore else R.string.ui_restore_to_queue),
                        if (history == null) order else "$order\n${stringResource(history)}")
                    if (!choosing) Actions {
                        Primary(stringResource(R.string.ui_restore_all), enabled = !action.busy) { restore(PlannerCommand.FrameRestore(id, RestoreMode.ALL)) }
                        Secondary(stringResource(R.string.ui_select_to_restore), enabled = !action.busy) { editingReason = false; choosing = true }
                    }
                }
            }
            item {
                val help = when {
                    entries.isNotEmpty() && entries.all { it.referenceOnly } -> stringResource(R.string.graph_no_restorable)
                    frame.remaining == 0 -> stringResource(R.string.ui_empty_frame_hint)
                    entries.any { it.referenceOnly } -> stringResource(R.string.frame_help_mixed)
                    else -> stringResource(R.string.ui_frame_history_hint)
                }
                SettingHeading(stringResource(if (frame.remaining == 0) R.string.ui_saved_list else R.string.ui_frame_tasks), help)
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
                        } else TaskIconTitle(current?.icon ?: item.icon) { Text(title, style = MaterialTheme.typography.titleMedium) }
                        Hint(stringResource(R.string.ui_minutes_value, minutes))
                        if (current != null && !choosing) CurrentTaskNote(current, state.revision, enabled = !action.busy, frameId = id)
                        else if (notes.isNotBlank()) PlannerMarkdown(notes, Modifier.fillMaxWidth())
                        if (current != null && (title != item.title || minutes != item.minutes || notes != item.notes || current.icon != item.icon)) {
                            SectionTitle(stringResource(R.string.frame_saved_snapshot))
                            TaskIconTitle(item.icon) { Text(item.title, style = MaterialTheme.typography.bodyMedium) }
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
