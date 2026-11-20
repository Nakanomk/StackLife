package io.github.nakanomk.stacklife.graphs

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
import io.github.nakanomk.stacklife.DockOverlay
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.*

@Composable
internal fun GraphsRoute(model: WorkspaceViewModel, onOpenGraph: (String) -> Unit, onSettings: () -> Unit,
    navigationBar: @Composable () -> Unit) {
    val state by remember(model) { model.graphs() }.collectAsStateWithLifecycle(ReadState.Loading)
    var creating by rememberSaveable { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf<String?>(null) }
    var remove by rememberSaveable { mutableStateOf<String?>(null) }
    ReadPanel(state) { list ->
        Page {
            PageTitle("流程图", settings = onSettings)
            DockOverlay(navigationBar, AssistantPage.Graphs) { bottom ->
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = listSpacing,
                    contentPadding = dockListPadding(bottom)) {
                    item { PreferenceRow("新建流程图") { creating = true } }
                    if (list.graphs.isEmpty()) item {
                        SectionTitle("还没有流程图")
                        Hint("把目标拆成节点，用箭头连接前置任务。")
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
                                    ToolbarIcon(io.github.nakanomk.stacklife.R.drawable.ic_more_vert, "管理") { menu = true }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem({ Text("重命名") }, { menu = false; rename = graph.id })
                                        DropdownMenuItem({ Text("删除流程图") }, { menu = false; remove = graph.id })
                                    }
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
            GraphSaveDialog(model, if (renamed == null) "新建流程图" else "重命名流程图", "保存", title.isNotBlank(),
                command = { if (renamed == null) PlannerCommand.GraphCreate(title)
                    else PlannerCommand.GraphPatch(renamed.id, listOf(GraphEdit.Rename(title))) },
                onClose = { creating = false; rename = null }) {
                TextField(title, { title = it }, Modifier.fillMaxWidth().testTag("graph-title"), colors = lineFieldColors(),
                    label = { Text("流程图名称") }, singleLine = true)
            }
        }
        list.graphs.find { it.id == remove }?.let { graph ->
            GraphSaveDialog(model, "删除「${graph.title}」？", "删除流程图", true,
                command = { PlannerCommand.GraphRemove(graph.id) }, onClose = { remove = null }) {
                Text("节点和依赖一起删除，已经生成的栈帧不受影响。")
            }
        }
    }
}
