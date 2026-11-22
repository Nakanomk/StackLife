package io.github.nakanomk.stacklife.notes

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.markdown.PlannerMarkdown
import io.github.nakanomk.stacklife.markdown.markdownSummary
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.transitions.CardSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Composable
internal fun noteDirectory(): File {
    val context = LocalContext.current.applicationContext
    return remember(context) { File(context.noBackupFilesDir, "note-drafts") }
}

// A route may still be composed underneath the editor during its return transition.
internal val acceptedNoteVersion = mutableLongStateOf(0L)

internal class NoteLink(val source: String?, val failed: Boolean, val opening: Boolean, val ownsFile: Boolean,
    val open: () -> Unit, val release: () -> Unit)

@Composable
internal fun rememberNoteLink(initial: String, taskId: String? = null, frameId: String? = null, revision: Long? = null): NoteLink {
    val root = noteDirectory()
    val navigate = LocalOpenNote.current
    val scope = rememberCoroutineScope()
    var draftId by rememberSaveable { mutableStateOf<String?>(null) }
    val id = draftId
    var job by remember { mutableStateOf<Job?>(null) }
    var creationError by remember { mutableStateOf(false) }
    val restored = if (id == null) null else key(id, acceptedNoteVersion.longValue) {
        produceState<Result<String>?>(null) {
            value = try { Result.success(withContext(Dispatchers.IO) { NoteDraft.accepted(root, id).source }) }
            catch (e: IOException) { Result.failure(e) }
        }.value
    }
    val source = if (id == null) initial else restored?.getOrNull()
    return NoteLink(source, creationError || restored?.isFailure == true, job != null, id != null, open = {
        if (job == null && source != null) {
            if (id != null) navigate(NoteEditorKey(id)) else {
                creationError = false
                job = scope.launch {
                    var created: String? = null
                    try {
                        val newId = withContext(Dispatchers.IO) { NoteDraft.create(root, source).id.also { created = it } }
                        if (taskId == null) draftId = newId
                        navigate(NoteEditorKey(newId, taskId, frameId, revision))
                    } catch (e: CancellationException) {
                        created?.let { NoteDraft.delete(root, it) }
                        throw e
                    } catch (e: IOException) { creationError = true }
                    finally { job = null }
                }
            }
        }
    }, release = {
        job?.cancel()
        draftId?.let { NoteDraft.delete(root, it) }
    })
}

@Composable
internal fun NoteField(link: NoteLink, enabled: Boolean, tag: String, full: Boolean = false) {
    val text = link.source
    if (link.failed) Text(stringResource(R.string.note_file_error), color = MaterialTheme.colorScheme.error)
    TextButton(onClick = link.open, enabled = enabled && !link.opening && text != null,
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) {
        Text(stringResource(if (text != null && text.isBlank()) R.string.note_add else R.string.note_edit))
    }
    if (!text.isNullOrBlank()) {
        if (full) PlannerMarkdown(text, Modifier.fillMaxWidth())
        else CardSummary(remember(text) { markdownSummary(text) })
    }
}

@Composable
internal fun CurrentTaskNote(task: QueueTask, revision: Long, enabled: Boolean, frameId: String? = null) {
    val link = rememberNoteLink(task.notes, task.id, frameId, revision)
    NoteField(link, enabled, "task-note:${task.id}", full = true)
}
