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
import io.github.nakanomk.stacklife.workspace.*
import androidx.compose.foundation.shape.CircleShape
import io.github.nakanomk.stacklife.transitions.rememberRouteLaunch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalReview(model: WorkspaceViewModel, graphId: String, graphState: ReadState<GraphDetail>,
    goalId: String?, onGoal: (String) -> Unit, onClose: () -> Unit, onFrame: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
    val reason = reasonOverride ?: goal?.let { stringResource(R.string.ui_goal_reason, it.title) }.orEmpty()
    var ticket by rememberSaveable { mutableStateOf<String?>(null) }
    var savingFrame by rememberSaveable { mutableStateOf(false) }
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
            } else receipt = context.getString(R.string.ui_queue_appended)
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
    val sheet = rememberBottomSheetState(initialValue = SheetValue.Expanded, enabledValues = setOf(SheetValue.Expanded, SheetValue.Hidden),
        confirmValueChange = { it != SheetValue.Hidden || ticket == null })
    val body: @Composable (Dp) -> Unit = { top ->
        val close = LocalPanelBack.current ?: onClose
        val pickingGoal = receipt == null && detail?.nodes?.isNotEmpty() == true && (choosing || goal == null)
        LazyColumn(Modifier.fillMaxWidth().imePadding().testTag("goal-order").then(if (pickingGoal) Modifier.selectableGroup() else Modifier),
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
                    item { PreferenceRow(stringResource(R.string.ui_goal_value, goal.title), stringResource(R.string.ui_change),
                        Modifier.testTag("goal-change"), enabled = !busy, onClick = { choosing = true }) }
                    when {
                        read == ReadState.Loading -> item { CircularProgressIndicator() }
                        order == null || order.error != null -> item { Text(order?.error ?: stringResource(R.string.ui_order_read_failed)) }
                        else -> {
                            item { SectionTitle(stringResource(R.string.ui_execution_order, order.nodes.size)) }
                            itemsIndexed(order.nodes, key = { _, node -> node.id }) { index, node ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("goal-item:$index")) {
                                    Text("${index + 1}. ${node.title}", style = MaterialTheme.typography.bodyLarge)
                                    Hint(stringResource(R.string.ui_minutes_value, node.minutes) + if (node.notes.isBlank()) "" else " · ${node.notes}")
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
                                    Hint(stringResource(R.string.ui_generation_hint))
                                    val enabled = !busy && reviewedRevision == order.revision && detail.revision == order.revision
                                    Actions {
                                        GoalReviewAction(stringResource(R.string.ui_add_to_queue), { save(order, false) },
                                            Modifier.testTag("graph-append"), enabled, prominent = true)
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
    PlannerModalBottomSheet(onDismissRequest = { if (ticket == null) onClose() }, sheetState = sheet,
        windowKey = "goal-review", canDismiss = !busy, dragHandle = { close -> GoalSheetHandle(!busy, close) }) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
            val close = LocalPanelBack.current ?: onClose
            RootPane(header = { PageTitle(stringResource(R.string.ui_topological_order), back = close, backEnabled = !busy) }) { top, _ -> body(top) }
        }
    }
}
