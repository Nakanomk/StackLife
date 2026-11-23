package io.github.nakanomk.stacklife.countdown

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.DateWheelField
import io.github.nakanomk.stacklife.appearance.LocalBackgroundStore
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.images.PhotoProblem
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.today.deviceDay
import io.github.nakanomk.stacklife.workspace.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CountdownEditor(model: WorkspaceViewModel, id: String?, onBack: () -> Unit) = key(id) {
    if (id == null) {
        val state by remember(model) { model.read<CountdownsView>(PlannerQuery.Countdowns) }.collectAsStateWithLifecycle(ReadState.Loading)
        ReadPanel(state) { CountdownForm(model, null, null, it.revision, onBack) }
    } else {
        val state by remember(id, model) { model.read<CountdownView>(PlannerQuery.Countdown(id)) }.collectAsStateWithLifecycle(ReadState.Loading)
        ReadPanel(state) { view ->
            // Keep the receipt attached if deletion is already visible when Room emits.
            val existed = rememberSaveable { view.value != null }
            if (existed) CountdownForm(model, id, view.value, view.revision, onBack) else CountdownMissing(onBack)
        }
    }
}

@Composable
private fun CountdownForm(model: WorkspaceViewModel, id: String?, saved: Countdown?, currentRevision: Long, back: () -> Unit) {
    val today = deviceDay().date
    val revision = rememberSaveable { currentRevision }
    val oldPinned = rememberSaveable { saved?.pinned ?: false }
    val originalImage by rememberSaveable { mutableStateOf(saved?.backgroundId) }
    var title by rememberSaveable { mutableStateOf(saved?.title.orEmpty()) }
    var day by rememberSaveable { mutableLongStateOf(saved?.targetDay ?: today.toEpochDay()) }
    var mode by rememberSaveable { mutableStateOf(saved?.mode ?: CountdownMode.COUNT_DOWN) }
    var pinned by rememberSaveable { mutableStateOf(oldPinned) }
    var opacity by rememberSaveable { mutableFloatStateOf(saved?.maskOpacity ?: .3f) }
    var image by rememberSaveable { mutableStateOf(originalImage) }
    var owned by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var applied by rememberSaveable { mutableStateOf(false) }
    var closing by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var photoError by rememberSaveable { mutableStateOf<String?>(null) }
    val photoFailure = stringResource(R.string.countdown_photo_error)
    var cleanupError by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val receipt = rememberSubmission(model, onError = { error = it }) { outcome ->
        if (outcome == SaveOutcome.APPLIED) { applied = true; closing = true }
    }
    val store = LocalBackgroundStore.current
    val scope = rememberCoroutineScope()
    val latestBack by rememberUpdatedState(back)
    LaunchedEffect(closing) {
        if (closing) {
            try {
                owned?.let { store.release(it) }; owned = null
                latestBack()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { cleanupError = true; closing = false }
        }
    }
    val stale = currentRevision != revision
    val busy = importing || receipt.busy || closing
    val editable = !busy && !applied && !stale
    val leave: () -> Unit = { if (!busy) closing = true }
    BackHandler(onBack = leave)
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null && editable) {
            importing = true; photoError = null
            scope.launch {
                var incoming: String? = null
                try {
                    // A configuration change may cancel this composition during the private copy. Always
                    // retain the returned ID long enough to release it if it never reaches the form.
                    withContext(NonCancellable) { incoming = store.importImage(uri) }
                    ensureActive()
                    owned?.let { store.release(it) }
                    image = incoming; owned = incoming; incoming = null
                } catch (e: CancellationException) { throw e }
                catch (problem: PhotoProblem) { photoError = problem.message ?: photoFailure }
                catch (_: Exception) { photoError = photoFailure }
                finally {
                    withContext(NonCancellable) { incoming?.let { store.release(it) } }
                    importing = false
                }
            }
        }
    }
    val date = remember(day) { LocalDate.ofEpochDay(day) }
    val draft = Countdown(id.orEmpty(), title, day, mode, pinned, saved?.position ?: 0, image, opacity)
    val assistant = if (id == null) AssistantPage.Countdowns else AssistantPage.Countdown(id)
    fun submit(commands: List<PlannerCommand>) {
        error = null
        receipt.submit { model.startReviewed(commands, revision) }
    }
    EditorPage(stringResource(if (id == null) R.string.countdown_new else R.string.countdown_edit), leave, assistant,
        backEnabled = !busy, headerAction = {
            TextButton(enabled = editable && draft.problem() == null, modifier = Modifier.testTag("countdown-save"), onClick = {
                if (id == null) submit(listOf(PlannerCommand.CountdownAdd(title, day, mode, pinned, image, opacity, expectedRevision = revision)))
                else submit(buildList {
                    add(PlannerCommand.CountdownEdit(id, title, day, mode, opacity,
                        if (image != originalImage) BackgroundChange(image) else null, expectedRevision = revision))
                    if (pinned != oldPinned) add(PlannerCommand.CountdownSetPinned(id, pinned, revision))
                })
            }) { Text(stringResource(if (receipt.busy) R.string.ui_saving else R.string.ui_save)) }
        }) { top, bottom ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(dockListPadding(top, bottom)),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (stale && !receipt.busy && !applied) Text(stringResource(R.string.countdown_stale), color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (cleanupError) Text(stringResource(R.string.countdown_cleanup_error), color = MaterialTheme.colorScheme.error)
            val preview = stringResource(R.string.countdown_preview)
            CountdownCard(draft, today, Modifier.semantics { contentDescription = preview }.testTag("countdown-preview"))
            FormGroup {
                TextField(title, { title = it }, Modifier.fillMaxWidth().testTag("countdown-name"), enabled = editable, colors = lineFieldColors(),
                    singleLine = true, label = { Text(stringResource(R.string.countdown_name)) }, isError = title.length > 256)
                DateWheelField(stringResource(R.string.countdown_date), date, today, editable, Modifier.testTag("countdown-date")) { day = it.toEpochDay() }
                if (editable) Choice(stringResource(R.string.countdown_mode), CountdownMode.entries, mode,
                    { TextMode(it) }, Modifier.testTag("countdown-mode")) { mode = it }
                else PreferenceRow(stringResource(R.string.countdown_mode), TextMode(mode), enabled = false) {}
                CheckRow(stringResource(R.string.countdown_pinned), pinned, enabled = editable) { pinned = !pinned }
            }
            FormGroup {
                PreferenceRow(stringResource(if (importing) R.string.countdown_preparing else R.string.countdown_choose_photo),
                    enabled = editable) { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
                photoError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (image != null) {
                    val dimming = stringResource(R.string.countdown_dimming)
                    Text(dimming, style = MaterialTheme.typography.bodyLarge)
                    Slider(value = opacity, onValueChange = { opacity = it }, enabled = editable,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = dimming })
                    TextButton(enabled = editable, onClick = {
                        val temp = owned
                        if (temp == null) image = null else {
                            importing = true
                            scope.launch {
                                try { store.release(temp); owned = null; image = null }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { cleanupError = true }
                                finally { importing = false }
                            }
                        }
                    }) { Text(stringResource(R.string.countdown_clear_photo)) }
                }
            }
            if (id != null) TextButton(enabled = editable, modifier = Modifier.testTag("countdown-delete"), onClick = { confirmingDelete = true }) {
                Text(stringResource(R.string.countdown_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmingDelete && id != null) PlannerAlertDialog(onDismissRequest = { confirmingDelete = false },
        title = { Text(stringResource(R.string.ui_delete_named, saved?.title.orEmpty())) },
        dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.ui_cancel)) } },
        confirmButton = { TextButton(enabled = editable, onClick = { confirmingDelete = false; submit(listOf(PlannerCommand.CountdownRemove(id, revision))) },
            modifier = Modifier.testTag("countdown-confirm-delete")) { Text(stringResource(R.string.countdown_delete)) } })
}

@Composable
private fun TextMode(mode: CountdownMode): String = stringResource(if (mode == CountdownMode.COUNT_DOWN) R.string.countdown_until_mode else R.string.countdown_since_mode)
