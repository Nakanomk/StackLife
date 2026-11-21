package io.github.nakanomk.stacklife.graphs

import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.*
import kotlin.math.roundToInt

@Composable
internal fun GraphSaveDialog(model: WorkspaceViewModel, title: String, confirm: String, valid: Boolean,
    command: () -> PlannerCommand, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val submission = rememberSubmission(model, { error = it }) { if (it == SaveOutcome.APPLIED) onClose() }
    PlannerAlertDialog(onDismissRequest = { if (!submission.busy) onClose() }, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = valid && !submission.busy, onClick = { submission.send(command()) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("graph-save")) { Text(confirm) }
    }, dismissButton = { TextButton(onClick = onClose, enabled = !submission.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_cancel)) } })
}

// Two default columns. Reuse vacated cells, including when other cards have been dragged nearby.
private fun firstVacantCell(nodes: List<GraphNode>): Int {
    val occupied = BooleanArray(nodes.size + 1)
    for (node in nodes) {
        val column = ((node.x - 16f) / 178f).roundToInt()
        val row = ((node.y - 24f) / 100f).roundToInt()
        if (column in 0..1 && row in 0..occupied.lastIndex / 2) {
            val cell = row * 2 + column
            if (cell < occupied.size) occupied[cell] = true
        }
    }
    return occupied.indexOf(false)
}

@Composable
internal fun NodeForm(model: WorkspaceViewModel, graphId: String, revision: Long, node: GraphNode?,
    nodes: List<GraphNode>, onClose: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(node?.title.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(node?.notes.orEmpty()) }
    var minutes by rememberSaveable { mutableStateOf(node?.minutes?.toString().orEmpty()) }
    val duration = if (minutes.isBlank()) 30 else minutes.toIntOrNull()
    GraphSaveDialog(model, if (node == null) stringResource(R.string.ui_add_node) else stringResource(R.string.ui_edit_node), stringResource(R.string.ui_save_node), title.isNotBlank() && (duration ?: 0) > 0,
        command = {
            val edit = if (node == null) {
                val cell = firstVacantCell(nodes)
                GraphEdit.AddNode(title, notes, checkNotNull(duration), x = 16f + cell % 2 * 178f, y = 24f + cell / 2 * 100f)
            } else GraphEdit.EditNode(node.id, title, notes, checkNotNull(duration))
            PlannerCommand.GraphPatch(graphId, listOf(edit), revision)
        }, onClose = onClose) {
        TextField(title, { title = it }, Modifier.fillMaxWidth().testTag("node-title"), colors = lineFieldColors(), singleLine = true, label = { Text(stringResource(R.string.ui_node_title)) })
        TextField(notes, { notes = it }, Modifier.fillMaxWidth().testTag("node-notes"), colors = lineFieldColors(), maxLines = 4, label = { Text(stringResource(R.string.ui_optional_notes)) })
        TextField(minutes, { minutes = it }, Modifier.fillMaxWidth().testTag("node-minutes"), colors = lineFieldColors(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.ui_estimated_minutes)) }, placeholder = { Text("30") })
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
