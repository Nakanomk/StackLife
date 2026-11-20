package io.github.nakanomk.stacklife.graphs

import android.database.sqlite.SQLiteException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

private class FrameProposal(val ready: PreviewResult.Ready, var submitted: Boolean = false)

@Composable
internal fun GoalReview(model: WorkspaceViewModel, graphId: String, goalId: String, onBack: () -> Unit, onFrame: (String) -> Unit) {
    val read by remember(model, graphId, goalId) { model.goal(graphId, goalId) }.collectAsStateWithLifecycle(ReadState.Loading)
    val order = (read as? ReadState.Loaded)?.value
    val goal = order?.nodes?.find { it.id == goalId }
    var reviewedRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(order?.revision) { if (reviewedRevision == null) reviewedRevision = order?.revision }
    var reasonOverride by rememberSaveable { mutableStateOf<String?>(null) }
    val reason = reasonOverride ?: goal?.let { "为目标〈${it.title}〉生成" }.orEmpty()
    var frameId by rememberSaveable { mutableStateOf<String?>(null) }
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val submission = rememberSubmission(model, { failure = it }) { outcome ->
        if (outcome == SaveOutcome.APPLIED) onFrame(checkNotNull(frameId)) else frameId = null
    }
    val changed = order != null && reviewedRevision != null && order.revision != reviewedRevision
    val command = if (!submission.busy && failure == null && order != null && goal != null && order.error == null && order.revision == reviewedRevision && reason.isNotBlank())
        PlannerCommand.GraphFrame(graphId, goalId, reason, ref = "frame", expectedRevision = order.revision) else null
    var prepared by remember(command, attempt) { mutableStateOf<FrameProposal?>(null) }
    var rejection by remember(command, attempt) { mutableStateOf<String?>(null) }
    LaunchedEffect(model, command, attempt) {
        if (command == null) return@LaunchedEffect
        var owned: FrameProposal? = null
        try {
            // Capture ownership even when the form leaves while the Room read is in flight.
            withContext(NonCancellable) {
                when (val result = model.preview(command)) {
                    is PreviewResult.Ready -> { owned = FrameProposal(result); prepared = owned }
                    is PreviewResult.Rejected -> rejection = result.message
                }
            }
            if (owned != null) awaitCancellation()
        } catch (_: SQLiteException) {
            rejection = "未能读取流程图，请重新预览。"
        } finally {
            owned?.let { if (!it.submitted) model.discardProposal(it.ready.proposalId) }
        }
    }
    val close = { if (!submission.busy) onBack() }
    BackHandler(onBack = close)
    Page {
        LazyColumn(Modifier.fillMaxSize().testTag("goal-order"), contentPadding = pagePadding, verticalArrangement = listSpacing) {
            item { PageTitle("目标顺序", back = close, backEnabled = !submission.busy) }
            when {
                read == ReadState.Loading -> item { CircularProgressIndicator() }
                read == ReadState.ReadFailed -> item { Text("未能读取流程图") }
                order == null || goal == null || order.error != null -> item { Text(order?.error ?: "目标已不存在") }
                else -> {
                    item { SectionTitle("${goal.title} · ${order.nodes.size} 项"); Hint("按此顺序生成新栈帧，当前队列不变。") }
                    itemsIndexed(order.nodes, key = { _, node -> node.id }) { index, node ->
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(12.dp).testTag("goal-item:$index")) {
                                Text("${index + 1}. ${node.title}", style = MaterialTheme.typography.titleMedium)
                                Hint("${node.minutes} 分钟" + if (node.notes.isBlank()) "" else " · ${node.notes}")
                            }
                        }
                    }
                    item {
                        OutlinedTextField(reason, { reasonOverride = it }, enabled = !submission.busy,
                            modifier = Modifier.fillMaxWidth().testTag("graph-frame-reason"), label = { Text("生成原因") }, maxLines = 4)
                    }
                    item {
                        val message = failure ?: rejection ?: if (changed) "数据已更新，请重新核对顺序。" else null
                        if (message != null && !submission.busy) {
                            Text(message, color = MaterialTheme.colorScheme.error)
                            Secondary("重新预览") { reviewedRevision = order.revision; failure = null; attempt++ }
                        } else Button(onClick = {
                            val proposal = prepared
                            val current = (read as? ReadState.Loaded)?.value
                            if (proposal != null && !proposal.submitted && current == order && command?.reason ==
                                (reasonOverride ?: "为目标〈${goal.title}〉生成") && reviewedRevision == proposal.ready.baseRevision) {
                                val ready = proposal.ready
                                frameId = ready.refs.getValue("frame")
                                proposal.submitted = submission.submit { model.confirmProposal(ready.proposalId) }
                                if (!proposal.submitted) frameId = null
                            }
                        }, enabled = prepared != null && !submission.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("graph-generate")) {
                            Text(if (submission.busy) "正在生成…" else "生成栈帧")
                        }
                    }
                }
            }
        }
    }
}
