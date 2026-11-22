package io.github.nakanomk.stacklife.graphs

import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
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
import io.github.nakanomk.stacklife.appearance.PlannerModalBottomSheet
import io.github.nakanomk.stacklife.appearance.LocalPanelBack
import io.github.nakanomk.stacklife.RootPane
import androidx.compose.ui.unit.Dp
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.markdown.markdownSummary
import io.github.nakanomk.stacklife.workspace.*
import androidx.compose.foundation.shape.CircleShape
import io.github.nakanomk.stacklife.transitions.rememberRouteLaunch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalReview(model: WorkspaceViewModel, graphId: String, graphState: ReadState<GraphDetail>,
    goalId: String?, onGoal: (String) -> Unit, onClose: () -> Unit, onSource: (GraphLink) -> Unit, onFrame: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val detail = (graphState as? ReadState.Loaded)?.value
    val goal = detail?.nodes?.find { it.id == goalId }
    val read = key(goalId) {
        if (goalId == null) null else remember(model, graphId, goalId) { model.goal(graphId, goalId) }
            .collectAsStateWithLifecycle(ReadState.Loading).value
    }
    val order = (read as? ReadState.Loaded)?.value
    val newCount = remember(order) { order?.nodes?.count { it.link == null } ?: 0 }
    val removed = remember(order) { order?.nodes?.filter { it.link != null && it.linkState?.missing != false }.orEmpty() }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var reviewedRevision by rememberSaveable(goalId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(goalId, order?.revision) {
        if (reviewedRevision == null) reviewedRevision = order?.revision
    }
    var reasonOverride by rememberSaveable(goalId) { mutableStateOf<String?>(null) }
    val reason = reasonOverride ?: goal?.let { stringResource(R.string.ui_goal_reason, it.title) }.orEmpty()
    var ticket by rememberSaveable { mutableStateOf<String?>(null) }
    var savingFrame by rememberSaveable { mutableStateOf(false) }
    var queueCount by rememberSaveable { mutableIntStateOf(0) }
    var failure by rememberSaveable(goalId) { mutableStateOf<String?>(null) }
    var receipt by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = ticket != null
    val frameLaunch = rememberRouteLaunch("generated-frame", CircleShape)
    val currentOnFrame by rememberUpdatedState(onFrame)
    LaunchedEffect(model, ticket) {
        val id = ticket ?: return@LaunchedEffect
        val result = model.result(id) { failure = it }
        if (result == SaveOutcome.APPLIED) {
            if (savingFrame) {
                val frame = model.reviewedRef(id, "frame")
                if (frame != null) frameLaunch.open { currentOnFrame(frame) } else receipt = context.getString(R.string.ui_frame_saved)
            } else receipt = context.getString(R.string.graph_queue_receipt, queueCount)
        }
        ticket = null
    }
    fun save(value: GoalOrder, frame: Boolean) {
        if (ticket != null || receipt != null || failure != null || goalId == null || removed.isNotEmpty() ||
            reviewedRevision != value.revision || detail?.revision != value.revision) return
        val command = if (frame) {
            if (reason.isBlank()) return
            PlannerCommand.GraphFrame(graphId, goalId, reason, ref = "frame", expectedRevision = value.revision)
        } else {
            if (newCount == 0) return
            queueCount = newCount
            PlannerCommand.GraphQueue(graphId, goalId, expectedRevision = value.revision)
        }
        savingFrame = frame
        ticket = model.startReviewed(listOf(command), value.revision)
    }
    val list = rememberLazyListState()
    val body: @Composable (Dp) -> Unit = { top ->
        val close = LocalPanelBack.current ?: onClose
        val pickingGoal = receipt == null && detail?.nodes?.isNotEmpty() == true && (choosing || goal == null)
        LazyColumn(Modifier.fillMaxSize().testTag("goal-order").then(if (pickingGoal) Modifier.selectableGroup() else Modifier),
            state = list, contentPadding = dockListPadding(top, 0.dp),
            verticalArrangement = listSpacing) {
            when {
                receipt != null -> item { SectionTitle(checkNotNull(receipt)); Secondary(stringResource(R.string.ui_done), onClick = close) }
                graphState == ReadState.Loading -> item { CircularProgressIndicator() }
                detail?.graph == null -> item { Text(if (graphState == ReadState.ReadFailed) stringResource(R.string.ui_graph_read_failed) else stringResource(R.string.ui_graph_missing)) }
                detail.nodes.isEmpty() -> item { Hint(stringResource(R.string.ui_add_nodes_first)) }
                choosing || goal == null -> {
                    item { SectionTitle(stringResource(R.string.ui_choose_goal)); Hint(stringResource(R.string.ui_goal_prereqs)) }
                    items(detail.nodes, key = { it.id }) { node ->
                        GoalChoice(node.id, node.title, selected = node.id == goalId, enabled = !busy) {
                            choosing = false; onGoal(node.id); list.requestScrollToItem(0)
                        }
                    }
                }
                else -> {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(goal.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            TextButton(modifier = Modifier.testTag("goal-change").heightIn(min = 48.dp), enabled = !busy,
                                onClick = { choosing = true }) { Text(stringResource(R.string.ui_change)) }
                        }
                    }
                    when {
                        read == ReadState.Loading -> item { CircularProgressIndicator() }
                        order == null || order.error != null -> item { Text(order?.error ?: stringResource(R.string.ui_order_read_failed)) }
                        else -> {
                            item { SectionTitle(stringResource(R.string.goal_execution_heading))
                                Hint(stringResource(R.string.goal_order_count, order.nodes.size)) }
                            itemsIndexed(order.nodes, key = { _, node -> node.id }) { index, node ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("goal-item:$index")) {
                                    Text("${index + 1}. ${node.title}", style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.ui_minutes_value, node.minutes), style = MaterialTheme.typography.labelMedium)
                                    if (node.notes.isNotBlank()) Hint(remember(node.notes) { markdownSummary(node.notes) })
                                    val link = node.link
                                    if (link == null) Hint(stringResource(R.string.graph_new_template)) else {
                                        GraphSourceLabel(link)
                                        GraphLinkStatus(node.linkState)
                                        TextButton({ onSource(link) }, Modifier.testTag("goal-open:${node.id}"),
                                            enabled = !busy && node.linkState?.missing == false) { Text(stringResource(R.string.graph_open_source)) }
                                    }
                                }
                            }
                            item {
                                HorizontalDivider()
                                TextField(reason, { reasonOverride = it }, colors = lineFieldColors(), enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().testTag("graph-frame-reason"),
                                    label = { Text(stringResource(R.string.ui_frame_reason_only)) }, maxLines = 4)
                            }
                            item {
                                val changed = reviewedRevision != null && reviewedRevision != order.revision
                                if (!busy && (failure != null || changed)) {
                                    Text(failure ?: stringResource(R.string.ui_data_changed), color = MaterialTheme.colorScheme.error)
                                    Secondary(stringResource(R.string.ui_preview_again), detail.revision == order.revision) {
                                        reviewedRevision = order.revision; failure = null; list.requestScrollToItem(0)
                                    }
                                } else {
                                    if (removed.isNotEmpty()) {
                                        removed.forEach { node ->
                                            Text(stringResource(R.string.graph_missing_block, node.title), color = MaterialTheme.colorScheme.error)
                                        }
                                    } else {
                                        if (newCount == 0) Hint(stringResource(R.string.graph_no_restorable))
                                        else {
                                            Hint(stringResource(R.string.goal_queue_policy, newCount))
                                            Hint(stringResource(R.string.goal_frame_policy, newCount))
                                        }
                                        if (order.nodes.size > newCount) Hint(stringResource(R.string.graph_frame_references, order.nodes.size - newCount))
                                    }
                                    val enabled = !busy && reviewedRevision == order.revision && detail.revision == order.revision && removed.isEmpty()
                                    Actions {
                                        GoalReviewAction(stringResource(R.string.graph_queue_action, newCount), { save(order, false) },
                                            Modifier.testTag("graph-append"), enabled && newCount > 0, prominent = true)
                                        GoalReviewAction(stringResource(R.string.ui_save_as_frame), { save(order, true) },
                                            Modifier.testTag("graph-generate"), enabled && reason.isNotBlank(), anchor = frameLaunch.modifier)
                                    }
                                    if (busy) Text(stringResource(R.string.ui_saving))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    PlannerModalBottomSheet(onDismissRequest = onClose, nested = choosing, onNestedBack = { choosing = false }, canDismiss = !busy) {
        Box(Modifier.fillMaxSize()) {
            val close = LocalPanelBack.current ?: onClose
            RootPane(header = { PageTitle(stringResource(R.string.ui_topological_order), back = close, backEnabled = !busy) }) { top, _ -> body(top) }
        }
    }
}
