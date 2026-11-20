package io.github.nakanomk.stacklife.graphs

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
        if (detail.graph == null) Page { PageTitle("流程图不存在", back = onBack) }
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

    Page(AssistantPage.Graph(graph.id, selected?.id)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolbarIcon(io.github.nakanomk.stacklife.R.drawable.ic_arrow_back, "返回", onClick = onBack)
            Text(graph.title, Modifier.weight(1f).semantics { heading() }.padding(horizontal = 6.dp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { listMode = !listMode }, modifier = Modifier.heightIn(min = 48.dp).testTag("graph-toggle-view")) {
                Text(if (listMode) "画布" else "依赖列表")
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (source != null) {
                Secondary("取消连线") { sourceId = null }
                Text("${source.title} → 请选择后继", style = MaterialTheme.typography.labelLarge)
            } else {
                FilledTonalButton(onClick = { onGoal(selected?.id) }, enabled = detail.nodes.isNotEmpty() && !submission.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("graph-goal")) { Text("生成拓扑排序") }
                if (selected != null) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("graph-node-actions")) {
                            Text("节点操作")
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("从此节点连线") }, { menu = false; linkFrom(selected.id) })
                            DropdownMenuItem({ Text("移除连线") }, { menu = false; form("edges") },
                                enabled = detail.edges.any { it.from == selected.id || it.to == selected.id })
                            DropdownMenuItem({ Text("编辑节点") }, { menu = false; form("edit") })
                            DropdownMenuItem({ Text("移动位置") }, { menu = false; form("move") })
                            DropdownMenuItem({ Text("删除节点") }, { menu = false; form("remove") })
                        }
                    }
                }
                TextButton(onClick = { form("add") }, modifier = Modifier.heightIn(min = 48.dp).testTag("graph-add")) { Text("添加节点") }
            }
        }
        if (listMode) GraphDependencies(detail, selected?.id, select, onRemove = { edge ->
            edgeFrom = edge.from; edgeTo = edge.to; form("unlink")
        })
        else GraphCanvas(detail, selected?.id, select, { id -> if (sourceId != null) select(id) else linkFrom(id) }, patch,
            { moves, revision -> submission.send(PlannerCommand.GraphPatch(graph.id, moves, revision)) },
            Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
    }
    when (editing) {
        "add" -> NodeForm(model, graph.id, editRevision, null, detail.nodes, ::dismiss)
        "edit" -> if (selected != null) NodeForm(model, graph.id, editRevision, selected, detail.nodes, ::dismiss)
        "move" -> if (selected != null) MoveForm(model, graph.id, editRevision, selected, ::dismiss)
        "remove" -> if (selected != null) GraphSaveDialog(model, "删除「${selected.title}」？", "删除节点", true,
            command = { PlannerCommand.GraphPatch(graph.id, listOf(GraphEdit.RemoveNode(selected.id)), editRevision) }, onClose = ::dismiss) {
            Text("一并移除与它相连的依赖，已有栈帧不变。")
        }
        "edges" -> if (selected != null) {
            val incident = remember(detail.edges, selected.id) { detail.edges.filter { it.from == selected.id || it.to == selected.id } }
            val titles = remember(detail.nodes) { detail.nodes.associate { it.id to it.title } }
            AlertDialog(onDismissRequest = ::dismiss, title = { Text("选择要移除的连线") }, text = {
                LazyColumn {
                    if (incident.isEmpty()) item { Text("没有与此节点相连的依赖") }
                    items(incident) { edge ->
                        TextButton(onClick = { edgeFrom = edge.from; edgeTo = edge.to; form("unlink") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("unlink-choice:${edge.from}:${edge.to}")) {
                            Text("${titles.getValue(edge.from)} → ${titles.getValue(edge.to)}", Modifier.fillMaxWidth())
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = ::dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
        }
        "unlink" -> {
            val from = detail.nodes.find { it.id == edgeFrom }; val to = detail.nodes.find { it.id == edgeTo }
            if (from != null && to != null) GraphSaveDialog(model, "移除依赖？", "移除依赖", true,
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
private fun GraphDependencies(detail: GraphDetail, selected: String?, onSelect: (String) -> Unit, onRemove: (GraphEdge) -> Unit) {
    val byId = remember(detail.nodes) { detail.nodes.associateBy { it.id } }
    LazyColumn(Modifier.fillMaxSize().testTag("graph-list"), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SectionTitle("节点 · ${detail.nodes.size}") }
        items(detail.nodes, key = { it.id }) { node ->
            Surface(shape = MaterialTheme.shapes.medium,
                color = if (node.id == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().testTag("list-node:${node.id}").selectable(node.id == selected, role = Role.RadioButton) { onSelect(node.id) }
                    .heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                    Text(node.title, style = MaterialTheme.typography.titleSmall)
                    Hint("${node.minutes} 分钟" + if (node.notes.isBlank()) "" else " · ${node.notes}")
                }
            }
        }
        item { SectionTitle("依赖 · ${detail.edges.size}"); if (detail.edges.isEmpty()) Hint("还没有依赖") }
        items(detail.edges, key = { "edge:${it.from}:${it.to}" }) { edge ->
            val from = byId[edge.from]; val to = byId[edge.to]
            if (from != null && to != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("先做「${from.title}」，再做「${to.title}」", Modifier.weight(1f))
                TextButton(onClick = { onRemove(edge) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除依赖") }
            }
        }
    }
}
