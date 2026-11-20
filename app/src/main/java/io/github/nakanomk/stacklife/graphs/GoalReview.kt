package io.github.nakanomk.stacklife.graphs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.PlannerModalBottomSheet
import io.github.nakanomk.stacklife.RootPane
import androidx.compose.ui.unit.Dp
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalReview(model: WorkspaceViewModel, graphId: String, graphState: ReadState<GraphDetail>,
    goalId: String?, onGoal: (String) -> Unit, onClose: () -> Unit, onFrame: (String) -> Unit) {
    val detail = (graphState as? ReadState.Loaded)?.value
    val goal = detail?.nodes?.find { it.id == goalId }
    val read = key(goalId) {
        if (goalId == null) null else remember(model, graphId, goalId) { model.goal(graphId, goalId) }
            .collectAsStateWithLifecycle(ReadState.Loading).value
    }
    val order = (read as? ReadState.Loaded)?.value
    var choosing by rememberSaveable { mutableStateOf(false) }
    var reviewedRevision by rememberSaveable(goalId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(goalId, order?.revision) {
        if (reviewedRevision == null) reviewedRevision = order?.revision
    }
    var reasonOverride by rememberSaveable(goalId) { mutableStateOf<String?>(null) }
    val reason = reasonOverride ?: goal?.let { "为目标〈${it.title}〉生成" }.orEmpty()
    var ticket by rememberSaveable { mutableStateOf<String?>(null) }
    var savingFrame by rememberSaveable { mutableStateOf(false) }
    var failure by rememberSaveable(goalId) { mutableStateOf<String?>(null) }
    var receipt by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = ticket != null
    val currentOnFrame by rememberUpdatedState(onFrame)
    LaunchedEffect(model, ticket) {
        val id = ticket ?: return@LaunchedEffect
        val result = model.result(id) { failure = it }
        if (result == SaveOutcome.APPLIED) {
            if (savingFrame) {
                val frame = model.reviewedRef(id, "frame")
                if (frame != null) currentOnFrame(frame) else receipt = "已存为栈帧，可在工作台查看。"
            } else receipt = "已按此顺序加入队尾。"
        }
        ticket = null
    }
    fun save(value: GoalOrder, frame: Boolean) {
        if (ticket != null || receipt != null || failure != null || goalId == null ||
            reviewedRevision != value.revision || detail?.revision != value.revision) return
        val commands = if (frame) {
            if (reason.isBlank()) return
            listOf(PlannerCommand.GraphFrame(graphId, goalId, reason, ref = "frame", expectedRevision = value.revision))
        } else value.nodes.map { PlannerCommand.TaskAdd(TaskInput(it.title, it.notes, it.minutes)) }
        savingFrame = frame
        ticket = model.startReviewed(commands, value.revision)
    }
    val list = rememberLazyListState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || ticket == null })
    val body: @Composable (Dp) -> Unit = { top ->
        LazyColumn(Modifier.fillMaxWidth().imePadding().testTag("goal-order"), state = list, contentPadding = dockListPadding(top, 0.dp),
            verticalArrangement = listSpacing) {
            when {
                receipt != null -> item { SectionTitle(checkNotNull(receipt)); Secondary("完成", onClick = onClose) }
                graphState == ReadState.Loading -> item { CircularProgressIndicator() }
                detail?.graph == null -> item { Text(if (graphState == ReadState.ReadFailed) "未能读取流程图" else "流程图不存在") }
                detail.nodes.isEmpty() -> item { Hint("先添加节点，再选择目标。") }
                choosing || goal == null -> {
                    item { SectionTitle("选择目标节点"); Hint("只排列这个目标及其所有前置节点。") }
                    items(detail.nodes, key = { it.id }) { node ->
                        Row(Modifier.fillMaxWidth().testTag("goal-choice:${node.id}")
                            .selectable(node.id == goalId, enabled = !busy, role = Role.RadioButton) {
                                choosing = false; onGoal(node.id); list.requestScrollToItem(0)
                            }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(node.id == goalId, onClick = null)
                            Text(node.title, Modifier.padding(start = 12.dp))
                        }
                    }
                }
                else -> {
                    item { PreferenceRow("目标：${goal.title}", "更换", enabled = !busy, onClick = { choosing = true }) }
                    when {
                        read == ReadState.Loading -> item { CircularProgressIndicator() }
                        order == null || order.error != null -> item { Text(order?.error ?: "未能读取目标顺序") }
                        else -> {
                            item { SectionTitle("执行顺序 · ${order.nodes.size} 项") }
                            itemsIndexed(order.nodes, key = { _, node -> node.id }) { index, node ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("goal-item:$index")) {
                                    Text("${index + 1}. ${node.title}", style = MaterialTheme.typography.bodyLarge)
                                    Hint("${node.minutes} 分钟" + if (node.notes.isBlank()) "" else " · ${node.notes}")
                                }
                            }
                            item {
                                HorizontalDivider()
                                TextField(reason, { reasonOverride = it }, colors = lineFieldColors(), enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().testTag("graph-frame-reason"),
                                    label = { Text("栈帧原因（仅存为栈帧时使用）") }, maxLines = 4)
                            }
                            item {
                                val changed = reviewedRevision != null && reviewedRevision != order.revision
                                if (!busy && (failure != null || changed)) {
                                    Text(failure ?: "数据已更新，请重新核对顺序。", color = MaterialTheme.colorScheme.error)
                                    Secondary("重新预览", detail.revision == order.revision) {
                                        reviewedRevision = order.revision; failure = null; list.requestScrollToItem(0)
                                    }
                                } else {
                                    Hint("加入队列会追加到现有队尾；存为栈帧不动队列。")
                                    val enabled = !busy && reviewedRevision == order.revision && detail.revision == order.revision
                                    Actions {
                                        ActionPill("加入队列", { save(order, false) }, Modifier.testTag("graph-append"), enabled, prominent = true)
                                        ActionPill("存为栈帧", { save(order, true) }, Modifier.testTag("graph-generate"), enabled && reason.isNotBlank())
                                    }
                                    if (busy) Text("正在保存…")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    PlannerModalBottomSheet(onDismissRequest = { if (ticket == null) onClose() }, sheetState = sheet) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
            RootPane(header = { PageTitle("生成拓扑排序", back = onClose, backEnabled = !busy) }) { top, _ -> body(top) }
        }
    }
}
