package io.github.nakanomk.stacklife.graphs

import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.RootPane
import io.github.nakanomk.stacklife.RootToolbar
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.appearance.PlannerDropdownMenu
import io.github.nakanomk.stacklife.workspace.*

@Composable
internal fun GraphsRoute(model: WorkspaceViewModel, onOpenGraph: (String) -> Unit, onSettings: () -> Unit) {
    val state by remember(model) { model.graphs() }.collectAsStateWithLifecycle(ReadState.Loading)
    var creating by rememberSaveable { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf<String?>(null) }
    var remove by rememberSaveable { mutableStateOf<String?>(null) }
    ReadPanel(state) { list ->
        RootPane(header = { RootToolbar(onSettings) {
            TextButton(onClick = { creating = true }) { Text(stringResource(R.string.ui_new_graph)) }
        } }) { top, bottom ->
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = listSpacing,
                    contentPadding = dockListPadding(top, bottom)) {
                    if (list.graphs.isEmpty()) item {
                        SectionTitle(stringResource(R.string.ui_no_graphs))
                        Hint(stringResource(R.string.ui_graph_empty_hint))
                    }
                    items(list.graphs, key = { it.id }) { graph ->
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onOpenGraph(graph.id) }, modifier = Modifier.weight(1f).heightIn(min = 60.dp)
                                    .testTag("open-graph:${graph.id}")) {
                                    Text(graph.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                }
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    ToolbarIcon(io.github.nakanomk.stacklife.R.drawable.ic_more_vert, stringResource(R.string.ui_manage)) { menu = true }
                                    PlannerDropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem({ Text(stringResource(R.string.ui_rename)) }, { menu = false; rename = graph.id })
                                        DropdownMenuItem({ Text(stringResource(R.string.ui_delete_graph)) }, { menu = false; remove = graph.id })
                                    }
                                }
                            }
                        }
                    }
                }
        }
        val renamed = list.graphs.find { it.id == rename }
        if (creating || renamed != null) key(renamed?.id) {
            var title by rememberSaveable { mutableStateOf(renamed?.title.orEmpty()) }
            GraphSaveDialog(model, if (renamed == null) stringResource(R.string.ui_new_graph) else stringResource(R.string.ui_rename_graph), stringResource(R.string.ui_save), title.isNotBlank(),
                command = { if (renamed == null) PlannerCommand.GraphCreate(title)
                    else PlannerCommand.GraphPatch(renamed.id, listOf(GraphEdit.Rename(title))) },
                onClose = { creating = false; rename = null }) {
                TextField(title, { title = it }, Modifier.fillMaxWidth().testTag("graph-title"), colors = lineFieldColors(),
                    label = { Text(stringResource(R.string.ui_graph_name)) }, singleLine = true)
            }
        }
        list.graphs.find { it.id == remove }?.let { graph ->
            GraphSaveDialog(model, stringResource(R.string.ui_delete_named, graph.title), stringResource(R.string.ui_delete_graph), true,
                command = { PlannerCommand.GraphRemove(graph.id) }, onClose = { remove = null }) {
                Text(stringResource(R.string.ui_graph_delete_hint))
            }
        }
    }
}
