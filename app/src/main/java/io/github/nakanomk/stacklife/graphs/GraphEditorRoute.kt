package io.github.nakanomk.stacklife.graphs

import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.RootPane
import io.github.nakanomk.stacklife.appearance.PlannerDropdownMenu
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.appearance.rememberPanelLaunch
import io.github.nakanomk.stacklife.transitions.routeOrigin
import androidx.compose.ui.unit.Dp
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.*

@Composable
internal fun GraphEditorRoute(model: WorkspaceViewModel, graphId: String, onBack: () -> Unit, onFrame: (String) -> Unit) = key(graphId) {
    val state by remember(model, graphId) { model.graph(graphId) }.collectAsStateWithLifecycle(ReadState.Loading)
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var goal by rememberSaveable { mutableStateOf<String?>(null) }
    ReadPanel(state) { detail ->
        if (detail.graph == null) MissingDetail(stringResource(R.string.ui_graph), stringResource(R.string.ui_graph_missing), onBack)
        else GraphEditor(model, detail, onBack, onGoal = { selected ->
            goal = selected ?: run {
                val predecessors = detail.edges.mapTo(HashSet()) { it.from }
                detail.nodes.singleOrNull { it.id !in predecessors }?.id
            }
            reviewing = true
        })
    }
    if (reviewing) GoalReview(model, graphId, state, goal, onGoal = { goal = it }, onClose = { reviewing = false },
        onFrame = { reviewing = false; onFrame(it) })
}

@Composable
private fun GraphEditor(model: WorkspaceViewModel, detail: GraphDetail, onBack: () -> Unit, onGoal: (String?) -> Unit) {
    val graph = checkNotNull(detail.graph)
    val currentRevision by rememberUpdatedState(detail.revision)
    var listMode by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = detail.nodes.find { it.id == selectedId }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceRevision by rememberSaveable { mutableLongStateOf(0L) }
    val source = detail.nodes.find { it.id == sourceId }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var editRevision by rememberSaveable { mutableLongStateOf(0L) }
    var edgeFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var edgeTo by rememberSaveable { mutableStateOf<String?>(null) }
    val submission = rememberSubmission(model)
    val patch: (GraphEdit, Long) -> Unit = { edit, revision ->
        submission.send(PlannerCommand.GraphPatch(graph.id, listOf(edit), revision))
    }
    val select: (String) -> Unit = { id ->
        val from = sourceId
        if (from == null) selectedId = id else {
            patch(GraphEdit.AddEdge(from, id), sourceRevision)
            sourceId = null
        }
    }
    val linkFrom: (String) -> Unit = { id -> selectedId = id; sourceId = id; sourceRevision = currentRevision }
    fun form(name: String) { editing = name; editRevision = currentRevision }
    fun dismiss() { editing = null }
    BackHandler(enabled = source != null) { sourceId = null }

    val tools: @Composable () -> Unit = {
        ControlGroup(Modifier.horizontalScroll(rememberScrollState())) {
            if (source != null) {
                Secondary(stringResource(R.string.ui_cancel_link)) { sourceId = null }
                Text(stringResource(R.string.ui_choose_successor, source.title), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge)
            } else {
                val review = rememberPanelLaunch("goal-review")
                ActionPill(stringResource(R.string.ui_topological_order), { review.open { onGoal(selected?.id) } },
                    review.modifier.routeOrigin("generated-frame").testTag("graph-goal"),
                    enabled = detail.nodes.isNotEmpty() && !submission.busy, prominent = true)
                if (selected != null) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        ActionPill(stringResource(R.string.ui_node_actions), { menu = true }, Modifier.testTag("graph-node-actions"))
                        PlannerDropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.ui_link_from_node)) }, { menu = false; linkFrom(selected.id) })
                            DropdownMenuItem({ Text(stringResource(R.string.ui_remove_link)) }, { menu = false; form("edges") },
                                enabled = detail.edges.any { it.from == selected.id || it.to == selected.id })
                            DropdownMenuItem({ Text(stringResource(R.string.ui_edit_node)) }, { menu = false; form("edit") })
                            DropdownMenuItem({ Text(stringResource(R.string.ui_move_node)) }, { menu = false; form("move") })
                            DropdownMenuItem({ Text(stringResource(R.string.ui_delete_node)) }, { menu = false; form("remove") })
                        }
                    }
                }
                ActionPill(stringResource(R.string.ui_add_node), { form("add") }, Modifier.testTag("graph-add"))
            }
        }
    }
    Page(AssistantPage.Graph(graph.id, selected?.id)) {
        RootPane(header = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolbarIcon(io.github.nakanomk.stacklife.R.drawable.ic_arrow_back, stringResource(R.string.ui_back), onClick = onBack)
                    Text(graph.title, Modifier.weight(1f).semantics { heading() }, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                    ActionPill(if (listMode) stringResource(R.string.ui_canvas) else stringResource(R.string.ui_dependencies), { listMode = !listMode }, Modifier.testTag("graph-toggle-view"))
                }
                tools()
            }
        }) { top, bottom ->
            if (listMode) GraphDependencies(detail, selected?.id, select, top, bottom, onRemove = { edge ->
                edgeFrom = edge.from; edgeTo = edge.to; form("unlink")
            })
            else GraphCanvas(detail, selected?.id, select, { id -> if (sourceId != null) select(id) else linkFrom(id) }, patch,
                { moves, revision -> submission.send(PlannerCommand.GraphPatch(graph.id, moves, revision)) },
                Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, bottom = bottom + 8.dp), headerInset = top)
        }
    }
    when (editing) {
        "add" -> NodeForm(model, graph.id, editRevision, null, detail.nodes, ::dismiss)
        "edit" -> if (selected != null) NodeForm(model, graph.id, editRevision, selected, detail.nodes, ::dismiss)
        "move" -> if (selected != null) MoveForm(model, graph.id, editRevision, selected, ::dismiss)
        "remove" -> if (selected != null) GraphSaveDialog(model, stringResource(R.string.ui_delete_named, selected.title), stringResource(R.string.ui_delete_node), true,
            command = { PlannerCommand.GraphPatch(graph.id, listOf(GraphEdit.RemoveNode(selected.id)), editRevision) }, onClose = ::dismiss) {
            Text(stringResource(R.string.ui_node_delete_hint))
        }
        "edges" -> if (selected != null) {
            val incident = remember(detail.edges, selected.id) { detail.edges.filter { it.from == selected.id || it.to == selected.id } }
            val titles = remember(detail.nodes) { detail.nodes.associate { it.id to it.title } }
            PlannerAlertDialog(onDismissRequest = ::dismiss, title = { Text(stringResource(R.string.ui_choose_link)) }, text = {
                LazyColumn {
                    if (incident.isEmpty()) item { Text(stringResource(R.string.ui_no_node_links)) }
                    items(incident) { edge ->
                        TextButton(onClick = { edgeFrom = edge.from; edgeTo = edge.to; form("unlink") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("unlink-choice:${edge.from}:${edge.to}")) {
                            Text("${titles.getValue(edge.from)} → ${titles.getValue(edge.to)}", Modifier.fillMaxWidth())
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = ::dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_cancel)) } })
        }
        "unlink" -> {
            val from = detail.nodes.find { it.id == edgeFrom }; val to = detail.nodes.find { it.id == edgeTo }
            if (from != null && to != null) GraphSaveDialog(model, stringResource(R.string.ui_remove_dependency_question), stringResource(R.string.ui_remove_dependency), true,
                command = { PlannerCommand.GraphPatch(graph.id, listOf(GraphEdit.RemoveEdge(from.id, to.id)), editRevision) }, onClose = ::dismiss) {
                Text("${from.title} → ${to.title}")
            }
        }
    }
    // The observer can remove a dialog's subject before its receipt or after a concurrent edit.
    LaunchedEffect(detail, editing) {
        if (selected == null && editing in setOf("edit", "move", "remove", "edges")) dismiss()
    }
}

@Composable
private fun GraphDependencies(detail: GraphDetail, selected: String?, onSelect: (String) -> Unit, top: Dp, bottom: Dp, onRemove: (GraphEdge) -> Unit) {
    val byId = remember(detail.nodes) { detail.nodes.associateBy { it.id } }
    LazyColumn(Modifier.fillMaxSize().testTag("graph-list"), contentPadding = dockListPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SectionTitle(stringResource(R.string.ui_node_count, detail.nodes.size)) }
        items(detail.nodes, key = { it.id }) { node ->
            Surface(shape = MaterialTheme.shapes.medium,
                color = if (node.id == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().testTag("list-node:${node.id}").selectable(node.id == selected, role = Role.RadioButton) { onSelect(node.id) }
                    .heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                    Text(node.title, style = MaterialTheme.typography.titleSmall)
                    Hint(stringResource(R.string.ui_minutes_value, node.minutes) + if (node.notes.isBlank()) "" else " · ${node.notes}")
                }
            }
        }
        item { SectionTitle(stringResource(R.string.ui_dependency_count, detail.edges.size)); if (detail.edges.isEmpty()) Hint(stringResource(R.string.ui_no_dependencies)) }
        items(detail.edges, key = { "edge:${it.from}:${it.to}" }) { edge ->
            val from = byId[edge.from]; val to = byId[edge.to]
            if (from != null && to != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ui_dependency_order, from.title, to.title), Modifier.weight(1f))
                TextButton(onClick = { onRemove(edge) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_remove_dependency)) }
            }
        }
    }
}
