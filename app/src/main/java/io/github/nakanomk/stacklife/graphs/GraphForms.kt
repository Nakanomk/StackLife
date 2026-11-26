package io.github.nakanomk.stacklife.graphs

import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.appearance.DateWheelField
import io.github.nakanomk.stacklife.appearance.LocalPanelBack
import io.github.nakanomk.stacklife.appearance.PlannerDropdownMenu
import io.github.nakanomk.stacklife.appearance.PlannerModalBottomSheet
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.appearance.DurationWheelField
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.*
import io.github.nakanomk.stacklife.notes.NoteField
import io.github.nakanomk.stacklife.notes.rememberNoteLink
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

@Composable
internal fun GraphSaveDialog(model: WorkspaceViewModel, title: String, confirm: String, valid: Boolean,
    command: () -> PlannerCommand, onClose: () -> Unit, visible: Boolean = true,
    content: @Composable ColumnScope.(Boolean) -> Unit) {
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val submission = rememberSubmission(model, { error = it }) { if (it == SaveOutcome.APPLIED) onClose() }
    val scroll = rememberScrollState()
    if (!visible) return
    PlannerAlertDialog(onDismissRequest = { if (!submission.busy) onClose() }, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content(!submission.busy)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = valid && !submission.busy, onClick = { submission.send(command()) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("graph-save")) { Text(confirm) }
    }, dismissButton = { TextButton(onClick = onClose, enabled = !submission.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_cancel)) } })
}

// Two default columns. Reuse vacated cells, including when other cards have been dragged nearby.
private inline fun positionedEdits(nodes: List<GraphNode>, count: Int, edit: (Int, Float, Float) -> GraphEdit): List<GraphEdit> {
    val occupied = BooleanArray(nodes.size + count)
    for (node in nodes) {
        val column = ((node.x - 16f) / 178f).roundToInt()
        val row = ((node.y - 24f) / 100f).roundToInt()
        if (column in 0..1 && row in 0..occupied.lastIndex / 2) {
            val cell = row * 2 + column
            if (cell < occupied.size) occupied[cell] = true
        }
    }
    var next = 0
    return List(count) { index ->
        while (occupied[next]) next++
        val cell = next++
        edit(index, 16f + cell % 2 * 178f, 24f + cell / 2 * 100f)
    }
}

@Composable
internal fun NodeForm(model: WorkspaceViewModel, graphId: String, revision: Long, node: GraphNode?,
    nodes: List<GraphNode>, onClose: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(node?.title.orEmpty()) }
    val note = rememberNoteLink(node?.notes.orEmpty())
    var minutes by rememberSaveable { mutableIntStateOf(node?.minutes ?: 30) }
    var icon by rememberSaveable { mutableStateOf(node?.icon ?: TaskIcon.TASK) }
    // A Dialog is a separate Android window: withdraw it while the note route is in front,
    // keeping all form fields in the graph entry's saved-state scope, not in the Dialog.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    GraphSaveDialog(model, if (node == null) stringResource(R.string.ui_add_node) else stringResource(R.string.ui_edit_node), stringResource(R.string.ui_save_node),
        title.isNotBlank() && note.source != null && !note.opening &&
            (node?.link == null || node.linkState?.let { !it.missing && !it.system } == true),
        command = {
            val notes = checkNotNull(note.source)
            val edits = if (node == null) positionedEdits(nodes, 1) { _, x, y -> GraphEdit.AddNode(title, notes, minutes, x, y, icon = icon) }
                else listOf(GraphEdit.EditNode(node.id, title, notes, minutes.takeIf { node.link == null }, icon.takeIf { it != node.icon }))
            PlannerCommand.GraphPatch(graphId, edits, revision)
        }, onClose = { note.release(); onClose() }, visible = lifecycle == Lifecycle.State.RESUMED) { enabled ->
        node?.link?.let {
            GraphSourceLabel(it)
            Hint(stringResource(if (it is GraphLink.Occurrence) R.string.graph_series_edit else R.string.graph_source_edit))
        }
        TextField(title, { title = it }, Modifier.fillMaxWidth().testTag("node-title"), colors = lineFieldColors(), enabled = enabled, singleLine = true, label = { Text(stringResource(R.string.ui_node_title)) })
        NoteField(note, enabled, "node-notes")
        if (node?.link == null) DurationWheelField(stringResource(R.string.ui_estimated_duration), minutes, enabled,
            Modifier.testTag("node-minutes")) { minutes = it }
        TaskIconField(icon, enabled, Modifier.testTag("node-icon")) { icon = it }
    }
}

@Composable
internal fun MoveForm(model: WorkspaceViewModel, graphId: String, revision: Long, node: GraphNode, onClose: () -> Unit) {
    var left by rememberSaveable { mutableStateOf(node.x.toString()) }
    var top by rememberSaveable { mutableStateOf(node.y.toString()) }
    val x = left.toFloatOrNull()?.takeIf { it.isFinite() }; val y = top.toFloatOrNull()?.takeIf { it.isFinite() }
    GraphSaveDialog(model, stringResource(R.string.ui_move_named, node.title), stringResource(R.string.ui_save_position), x != null && y != null, command = {
        PlannerCommand.GraphPatch(graphId, listOf(GraphEdit.MoveNode(node.id, checkNotNull(x), checkNotNull(y))), revision)
    }, onClose = onClose) {
        TextField(left, { left = it }, colors = lineFieldColors(), singleLine = true, label = { Text(stringResource(R.string.ui_position_x)) })
        TextField(top, { top = it }, colors = lineFieldColors(), singleLine = true, label = { Text(stringResource(R.string.ui_position_y)) })
    }
}

@Composable
internal fun GraphSourceLabel(link: GraphLink) {
    val identity = when (link) {
        is GraphLink.Task -> stringResource(R.string.graph_task_identity, link.taskId)
        is GraphLink.Occurrence -> stringResource(R.string.graph_occurrence_identity, LocalDate.ofEpochDay(link.originalDate), link.scheduleId)
    }
    Text(identity, style = MaterialTheme.typography.labelMedium)
}

@Composable
internal fun GraphLinkStatus(state: LinkState?) {
    if (state == null || state.missing) {
        Text(stringResource(R.string.graph_source_removed), color = MaterialTheme.colorScheme.error)
        return
    }
    if (state.system) Text(stringResource(R.string.graph_system_source), style = MaterialTheme.typography.labelMedium)
    val ownerLabel = when (state.owner) {
        Owner.QUEUE -> R.string.graph_stays_queue
        Owner.FRAME -> R.string.graph_stays_frame
        Owner.DONE -> R.string.graph_stays_done
        else -> null
    }
    ownerLabel?.let { Text(stringResource(it), style = MaterialTheme.typography.labelMedium) }
    if (state.scheduled) Text(stringResource(R.string.graph_stays_scheduled), style = MaterialTheme.typography.labelMedium)
    if (state.completed) Text(stringResource(R.string.graph_satisfied), style = MaterialTheme.typography.labelMedium)
}

private const val PAGE_SIZE = 50
private val linkSelectionSaver = listSaver<SnapshotStateList<GraphLink>, String>(
    save = { links -> links.map { Json.encodeToString<GraphLink>(it) } },
    restore = { keys -> keys.map { Json.decodeFromString<GraphLink>(it) }.toMutableStateList() },
)

// Interaction only: Vikunja 2ba8cef799cf12f7d0cf1196a180d75a2890c873 (AGPL-3.0),
// frontend/src/components/{tasks/partials/RelatedTasks,input/Multiselect}.vue.
// StackLife keeps identities across local query pages and submits a single revision-checked GraphPatch.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GraphEventPicker(model: WorkspaceViewModel, graphId: String, nodes: List<GraphNode>, revision: Long, onClose: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LinkFilter.ALL) }
    var day by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    val selected = rememberSaveable(saver = linkSelectionSaver) { mutableStateListOf<GraphLink>() }
    val selection by remember { derivedStateOf { selected.toSet() } }
    var selectedRevision by rememberSaveable { mutableLongStateOf(0L) }
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    val submission = rememberSubmission(model, { failure = it }) { if (it == SaveOutcome.APPLIED) onClose() }
    val date = LocalDate.ofEpochDay(day)
    val query = remember(graphId, search, filter, day, offset) { PlannerQuery.LinkableEvents(graphId, search, filter, date, offset, PAGE_SIZE) }
    // Reset to Loading synchronously when the query changes: a tap must never target the previous page.
    val read = key(model, query, retry) {
        remember { model.read<LinkablePage>(query) }.collectAsStateWithLifecycle(ReadState.Loading).value
    }
    val page = (read as? ReadState.Loaded)?.value
    val stale = selected.isNotEmpty() && selectedRevision != revision
    val list = rememberLazyListState()
    LaunchedEffect(page?.total) {
        if (page != null && offset > 0 && offset >= page.total) offset = (page.total - 1).coerceAtLeast(0) / PAGE_SIZE * PAGE_SIZE
    }
    PlannerModalBottomSheet(onDismissRequest = onClose, canDismiss = !submission.busy) {
        val close = LocalPanelBack.current ?: onClose
        PageTitle(stringResource(R.string.graph_link_existing), close, backEnabled = !submission.busy)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("graph-candidates"), state = list,
            contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                TextField(search, { search = it; offset = 0 }, Modifier.fillMaxWidth().testTag("graph-link-search"),
                    enabled = !submission.busy, singleLine = true, colors = lineFieldColors(), label = { Text(stringResource(R.string.graph_search)) })
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    var filters by remember { mutableStateOf(false) }
                    Box {
                        TextButton({ filters = true }, Modifier.testTag("graph-filter"), enabled = !submission.busy) {
                            Text(stringResource(filter.label()))
                        }
                        PlannerDropdownMenu(filters, { filters = false }) {
                            for (choice in LinkFilter.entries) DropdownMenuItem({ Text(stringResource(choice.label())) }, {
                                filter = choice; offset = 0; filters = false
                            }, modifier = Modifier.testTag("graph-filter:$choice"))
                        }
                    }
                    DateWheelField(stringResource(R.string.graph_occurrence_day), date, date, !submission.busy,
                        Modifier.testTag("graph-link-date")) { day = it.toEpochDay(); offset = 0 }
                }
            }
            when (read) {
                ReadState.Loading -> item { CircularProgressIndicator() }
                ReadState.ReadFailed -> item {
                    Text(stringResource(R.string.graph_picker_failed), color = MaterialTheme.colorScheme.error)
                    TextButton({ retry++ }) { Text(stringResource(R.string.graph_retry)) }
                }
                is ReadState.Loaded -> {
                    val result = read.value
                    if (result.total == 0) item {
                        Hint(stringResource(if (search.isBlank()) R.string.graph_picker_empty else R.string.graph_picker_no_matches))
                    } else {
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.graph_page_range, offset + 1, offset + result.items.size, result.total))
                                if (result.total > PAGE_SIZE) {
                                    TextButton({ offset = query.offset - PAGE_SIZE }, Modifier.testTag("graph-page-prev"),
                                        enabled = offset > 0 && !submission.busy) { Text(stringResource(R.string.graph_previous)) }
                                    TextButton({ offset = query.offset + PAGE_SIZE }, Modifier.testTag("graph-page-next"),
                                        enabled = result.total - offset > PAGE_SIZE && !submission.busy) { Text(stringResource(R.string.graph_next)) }
                                }
                            }
                        }
                        items(result.items, key = { it.link.storageKey() }) { candidate ->
                            val checked = candidate.linked || candidate.link in selection
                            val enabled = !candidate.linked && !submission.busy && !stale && revision == result.revision
                            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Row(Modifier.fillMaxWidth().testTag("graph-option:${candidate.link.storageKey()}")
                                    .toggleable(checked, enabled = enabled, role = Role.Checkbox) { want ->
                                        if (want) {
                                            if (selected.isEmpty()) selectedRevision = result.revision
                                            if (candidate.link !in selected) selected.add(candidate.link)
                                        } else selected.remove(candidate.link)
                                        failure = null
                                    }.heightIn(min = 48.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Checkbox(checked, onCheckedChange = null, enabled = enabled)
                                    Column {
                                        Text(candidate.title, style = MaterialTheme.typography.bodyLarge)
                                        GraphSourceLabel(candidate.link)
                                        GraphLinkStatus(candidate.state)
                                        candidate.startMillis?.let { Text(savedTime(it), style = MaterialTheme.typography.labelMedium) }
                                        if (candidate.linked) Text(stringResource(R.string.graph_already_linked), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (stale) Text(stringResource(R.string.graph_selection_stale), color = MaterialTheme.colorScheme.error)
            else failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (selected.isNotEmpty()) TextButton({ selected.clear(); failure = null }, enabled = !submission.busy) {
                    Text(stringResource(R.string.graph_clear))
                }
                TextButton({
                    val edits = positionedEdits(nodes, selected.size) { index, x, y -> GraphEdit.LinkExisting(selected[index], x, y) }
                    submission.send(PlannerCommand.GraphPatch(graphId, edits, selectedRevision))
                }, Modifier.testTag("graph-link-add"), enabled = selection.isNotEmpty() && !stale &&
                    page?.revision == revision && !submission.busy) { Text(stringResource(R.string.graph_add_count, selection.size)) }
            }
        }
    }
}

private fun LinkFilter.label() = when (this) {
    LinkFilter.ALL -> R.string.graph_filter_all
    LinkFilter.QUEUE -> R.string.graph_filter_queue
    LinkFilter.FRAME -> R.string.graph_filter_frame
    LinkFilter.DONE -> R.string.graph_filter_done
    LinkFilter.DAY -> R.string.graph_filter_day
}
