package io.github.nakanomk.stacklife.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation3.runtime.NavKey
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.markdown.MarkdownComposer
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.workspace.EditorPage
import io.github.nakanomk.stacklife.workspace.SaveOutcome
import io.github.nakanomk.stacklife.workspace.rememberSubmission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

@Serializable
internal data class NoteEditorKey(val draftId: String, val taskId: String? = null,
    val frameId: String? = null, val revision: Long? = null) : NavKey

internal val LocalOpenNote: ProvidableCompositionLocal<(NoteEditorKey) -> Unit> = staticCompositionLocalOf { { error("Missing note navigation") } }

private class EditingNote(private val root: File, private val draft: NoteDraft) {
    val initial = draft.accepted.source
    val state = TextFieldState(draft.working.source, draft.working.selection)
    private var closed = false

    // A stopped Activity flushes the latest input even if the IO collector was between frames.
    // Reading the current snapshot under the lock prevents an older queued flush from winning.
    @Synchronized fun checkpoint() {
        if (!closed) {
            val snapshot = Snapshot.takeSnapshot()
            try { draft.checkpoint(snapshot.enter { NoteValue(state.text.toString(), state.selection) }) }
            finally { snapshot.dispose() }
        }
    }

    @Synchronized fun candidate(): String {
        checkpoint()
        return draft.working.source
    }

    @Synchronized fun close(direct: Boolean, accept: Boolean = false) {
        if (closed) return
        if (direct) NoteDraft.delete(root, draft.id)
        else if (accept) { checkpoint(); draft.accept() }
        else draft.discardEdit()
        closed = true
    }
}

@Composable
internal fun NoteEditorScreen(key: NoteEditorKey, model: WorkspaceViewModel, back: () -> Unit) {
    val root = noteDirectory()
    val loaded by produceState<Result<EditingNote>?>(null, key.draftId) {
        value = try {
            val draft = withContext(Dispatchers.IO) { NoteDraft.open(root, key.draftId) }
            Result.success(EditingNote(root, draft))
        } catch (e: IOException) { Result.failure(e) }
    }
    val note = loaded?.getOrNull()
    val direct = key.taskId != null
    var ioFailure by remember { mutableStateOf(false) }
    var receiptError by rememberSaveable { mutableStateOf<String?>(null) }
    var applied by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun finish(accept: Boolean) {
        if (finishing) return
        finishing = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (note != null) note.close(direct, accept)
                    else if (direct) NoteDraft.delete(root, key.draftId)
                    else try { NoteDraft.open(root, key.draftId, discardWorking = true) }
                    catch (_: IOException) {
                        // If the accepted copy is damaged too, leave it untouched. The parent
                        // reopens that copy below and blocks its own save rather than using "".
                    }
                }
                if (!direct) acceptedNoteVersion.longValue++
                back()
            } catch (e: IOException) { ioFailure = true; finishing = false }
        }
    }
    val receipt = rememberSubmission(model, onError = { receiptError = it }) {
        if (it == SaveOutcome.APPLIED) applied = true
    }
    LaunchedEffect(applied) { if (applied) finish(accept = true) }
    val busy = receipt.busy || finishing || loaded == null
    val leave = {
        if (!busy) {
            if (applied) finish(accept = true)
            else if (note == null || note.state.text.toString() != note.initial) confirming = true
            else finish(accept = false)
        }
    }
    BackHandler(onBack = leave)
    if (confirming) PlannerAlertDialog(onDismissRequest = { confirming = false },
        title = { Text(stringResource(R.string.note_discard_question)) },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.note_keep_editing)) } },
        confirmButton = { TextButton(onClick = { confirming = false; finish(accept = false) }, modifier = Modifier.testTag("note-discard")) {
            Text(stringResource(R.string.note_discard))
        } })

    if (note != null) {
        LaunchedEffect(note) {
            snapshotFlow { note.state.text to note.state.selection }.collect {
                try { withContext(Dispatchers.IO) { note.checkpoint() }; ioFailure = false }
                catch (e: IOException) { ioFailure = true }
            }
        }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
            try { note.checkpoint() } catch (e: IOException) { ioFailure = true }
        }
    }
    EditorPage(stringResource(R.string.ui_notes), leave, backEnabled = !busy, headerAction = {
        TextButton(enabled = note != null && !busy && !applied, modifier = Modifier.testTag("note-save"), onClick = {
            if (note != null && !finishing && !receipt.busy && !applied) {
                receiptError = null
                if (key.taskId == null) finish(accept = true)
                else {
                    // Persist the exact candidate before submitting; a lost receipt must not clear the draft.
                    finishing = true
                    scope.launch {
                        try {
                            val text = withContext(Dispatchers.IO) { note.candidate() }
                            val command = PlannerCommand.TaskEdit(key.taskId, notes = text, expectedFrameId = key.frameId)
                            receipt.submit { model.startReviewed(listOf(command), checkNotNull(key.revision)) }
                        } catch (e: IOException) { ioFailure = true }
                        finally { finishing = false }
                    }
                }
            }
        }) { Text(stringResource(R.string.ui_save)) }
    }) { top, bottom ->
        Column(Modifier.fillMaxSize().padding(top = top, bottom = bottom).padding(horizontal = 16.dp)) {
            if (loaded?.isFailure == true || ioFailure) Text(stringResource(R.string.note_file_error), color = MaterialTheme.colorScheme.error)
            receiptError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (note != null && !busy && !applied) MarkdownComposer(note.state, Modifier.fillMaxSize().testTag("note-source"))
            else if (loaded == null || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
