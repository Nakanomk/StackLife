package io.github.nakanomk.stacklife.workspace

import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.planner.TaskInput
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.appearance.DurationWheelField
import io.github.nakanomk.stacklife.notes.NoteField
import io.github.nakanomk.stacklife.notes.rememberNoteLink

private data class Draft(
    val key: Int,
    val title: TextFieldValue = TextFieldValue(),
    val minutes: TextFieldValue = TextFieldValue(),
) {
    fun duration(): Int? = if (minutes.text.isBlank()) 30 else minutes.text.toIntOrNull()
}

private fun SaverScope.saveField(field: TextFieldValue): Any = with(TextFieldValue.Saver) { checkNotNull(save(field)) }
private fun Any.restoreField(): TextFieldValue = checkNotNull(TextFieldValue.Saver.restore(this))

private val draftSaver = listSaver<SnapshotStateList<Draft>, Any>(
    save = { rows -> rows.flatMap { listOf(it.key, saveField(it.title), saveField(it.minutes)) } },
    restore = { values -> values.chunked(3).map { Draft(it[0] as Int, it[1].restoreField(), it[2].restoreField()) }.toMutableStateList() },
)

@Composable
internal fun TaskEditor(
    actions: WorkspaceActions,
    onClose: () -> Unit,
    original: QueueTask? = null,
    createFrame: Boolean = false,
    frameId: String? = null,
    expectedRevision: Long? = null,
) {
    val drafts = rememberSaveable(saver = draftSaver) {
        mutableStateListOf(if (original == null) Draft(0) else Draft(0, TextFieldValue(original.title), TextFieldValue(original.minutes.toString())))
    }
    // Hoist all note identities out of the lazy list: off-screen frame rows still belong to the form.
    val notes = drafts.map { row -> key(row.key) { rememberNoteLink(original?.notes.orEmpty()) } }
    val abandon = { notes.forEach { it.release() }; onClose() }
    val action = rememberSubmission(actions) { if (it == SaveOutcome.APPLIED) abandon() }
    var nextKey by rememberSaveable { mutableIntStateOf(1) }
    var reason by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val scroll = rememberLazyListState()
    val close = { if (!action.busy) abandon() }
    BackHandler(enabled = action.busy || notes.any { it.ownsFile || it.opening }, onBack = close)
    val heading = if (createFrame) stringResource(R.string.ui_new_frame) else if (original != null) stringResource(R.string.ui_edit_task) else stringResource(R.string.ui_add_task)
    EditorPage(heading, close, frameId?.let { AssistantPage.Frame(it) } ?: original?.let { AssistantPage.Task(it.id) } ?: if (createFrame) AssistantPage.Frames else AssistantPage.Queue,
        backEnabled = !action.busy, footer = {
            Actions {
                if (createFrame) Secondary(stringResource(R.string.ui_add_another), enabled = !action.busy) {
                    drafts.add(Draft(nextKey++))
                    scroll.requestScrollToItem(1 + drafts.lastIndex)
                }
                Primary(if (createFrame) stringResource(R.string.ui_save_frame) else if (original != null) stringResource(R.string.ui_save_changes) else stringResource(R.string.ui_add_task_to_queue),
                    enabled = !action.busy && notes.all { it.source != null && !it.opening } && drafts.all { it.duration() != null }) {
                    val inputs = drafts.mapIndexed { index, row -> TaskInput(row.title.text, checkNotNull(notes[index].source), checkNotNull(row.duration())) }
                    val command = when {
                        createFrame -> PlannerCommand.FrameCreate(reason.text, inputs)
                        original != null -> inputs.single().let { PlannerCommand.TaskEdit(original.id, it.title, it.notes, it.minutes, expectedFrameId = frameId, expectedRevision = expectedRevision) }
                        else -> PlannerCommand.TaskAdd(inputs.single())
                    }
                    action.send(command)
                }
            }
        }) { top, bottom ->
        LazyColumn(state = scroll, contentPadding = dockListPadding(top, bottom), verticalArrangement = listSpacing) {
            if (createFrame) item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Hint(stringResource(R.string.ui_new_frame_hint))
                    ReasonField(reason) { reason = it }
                }
            }
            itemsIndexed(drafts, key = { _, draft -> draft.key }) { index, row ->
                FormGroup {
                    if (createFrame) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.ui_task_number, index + 1), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (drafts.size > 1) TextButton(enabled = !action.busy, onClick = { notes[index].release(); drafts.remove(row) }) { Text(stringResource(R.string.ui_delete_item)) }
                    }
                    TextField(row.title, { drafts[index] = row.copy(title = it) }, enabled = !action.busy,
                        modifier = Modifier.fillMaxWidth().testTag("title:$index"), colors = lineFieldColors(),
                        label = { Text(stringResource(R.string.ui_title)) }, singleLine = true)
                    NoteField(notes[index], !action.busy, "notes:$index")
                    DurationWheelField(stringResource(R.string.ui_estimated_duration), row.duration() ?: 30, !action.busy,
                        Modifier.testTag("duration:$index")) { drafts[index] = row.copy(minutes = TextFieldValue(it.toString())) }
                }
            }
        }
    }
}

@Composable
private fun ReasonField(reason: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
    TextField(colors = lineFieldColors(),
        value = reason, onValueChange = onChange, modifier = Modifier.fillMaxWidth().testTag("reason"),
        label = { Text(stringResource(R.string.ui_reason)) }, placeholder = { Text(stringResource(R.string.ui_reason_prompt)) }, maxLines = 4,
    )
}

@Composable
internal fun PushReasonEditor(frameId: String, actions: WorkspaceActions, field: androidx.compose.runtime.MutableState<TextFieldValue>? = null, close: () -> Unit) {
    var reason by field ?: rememberSaveable(frameId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val focus = LocalFocusManager.current
    var fieldFocused by remember { mutableStateOf(false) }
    fun finish() {
        if (fieldFocused) focus.clearFocus()
        close()
    }
    val action = rememberSubmission(actions) { if (it != SaveOutcome.REJECTED) finish() }
    Row(Modifier.fillMaxWidth().testTag("push-reason-editor"), verticalAlignment = Alignment.CenterVertically) {
        TextField(reason, { reason = it }, Modifier.weight(1f).testTag("push-reason").onFocusChanged { fieldFocused = it.isFocused },
            enabled = !action.busy, singleLine = true, colors = lineFieldColors(), label = { Text(stringResource(R.string.ui_push_reason)) })
        TextButton(onClick = ::finish, enabled = !action.busy) { Text(stringResource(R.string.ui_cancel)) }
        TextButton(onClick = { action.send(PlannerCommand.FrameAddReason(frameId, reason.text)) },
            enabled = !action.busy && reason.text.isNotBlank()) { Text(stringResource(R.string.ui_save)) }
    }
}
