package io.github.nakanomk.stacklife.assistant

import java.util.Date
import java.text.SimpleDateFormat
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.transitions.PaneOrigins
import io.github.nakanomk.stacklife.transitions.paneOrigin
import io.github.nakanomk.stacklife.transitions.rememberRouteLaunch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ConversationHistoryPane(model: AssistantModel, fallback: AssistantPage, id: Long?, origins: PaneOrigins, threads: List<ConversationRow>?,
    listState: LazyListState, active: Boolean, onThread: (Long?) -> Unit, onBack: () -> Unit, onSettings: () -> Unit, onClose: () -> Unit) {
    val history = model.history
    val selected = if (id == null) null else threads?.find { it.id == id }
    val page = remember(selected, fallback) { selected?.resumePage(fallback) ?: fallback }
    val ui by model.state.collectAsStateWithLifecycle()
    val canReply = active && ui.work == AssistantWork.IDLE && ui.pending == null && !ui.reviewAgain && !ui.importRequested
    LaunchedEffect(id, selected, threads != null) {
        if (id != null && threads != null && selected == null) onThread(null)
    }

        val recipient by produceState<ReadState<ServiceConnection?>>(ReadState.Loading, model, id) {
            if (id != null) value = try { ReadState.Loaded(model.connections.load()) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { ReadState.ReadFailed }
        }
        var confirmDelete by rememberSaveable { mutableStateOf(false) }
        var deletionError by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (id != null) IconButton(onClick = onBack, modifier = Modifier.testTag("assistant-history-back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.assistant_back_to_history))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (id == null) stringResource(R.string.assistant_history) else stringResource(R.string.assistant_transcript), Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleLarge)
                        Text(if (id == null) stringResource(R.string.assistant_local_only) else page.scopeName(),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (id == null) TextButton(onClick = onBack) { Text(stringResource(R.string.assistant_close)) }
                    else TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("assistant-history-delete")) { Text(stringResource(R.string.assistant_delete)) }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                if (id == null) {
                    val rows = threads
                    val locale = LocalConfiguration.current.locales[0]
                    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
                    val formatter = remember(locale, use24Hour) {
                        val pattern = DateFormat.getBestDateTimePattern(locale, if (use24Hour) "yMMMdHm" else "yMMMdhm")
                        SimpleDateFormat(pattern, locale)
                    }
                    LazyColumn(Modifier.fillMaxSize().testTag("assistant-history-list"), state = listState) {
                        if (rows == null) item { LinearProgressIndicator() }
                        else if (rows.isEmpty()) item {
                            Text(stringResource(R.string.assistant_empty_history), Modifier.padding(vertical = 16.dp).testTag("assistant-history-empty"),
                                style = MaterialTheme.typography.bodyLarge)
                        } else items(rows, key = { it.id }) { row ->
                            val date = remember(formatter, row.updatedAtMillis) { formatter.format(Date(row.updatedAtMillis)) }
                            val scopeName = remember(locale, row.scopeKey, row.scope) { row.displayScope() }
                            Row(Modifier.fillMaxWidth().paneOrigin(origins, "thread:${row.id}").testTag("assistant-history-thread-${row.id}")
                                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.assistant_view_conversation)) { onThread(row.id) }
                                .heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.title.ifEmpty { stringResource(R.string.chat_image_message) }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                    Text("$scopeName · $date", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(painterResource(R.drawable.ic_chevron_right), null)
                            }
                            HorizontalDivider()
                        }
                    }
                } else {
                    val messages by remember(history, id) { history.observeMessages(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
                    val draft = remember(model, id) { model.draft("history:$id") }
                    ChatMessages(messages, model.attachments, Modifier.weight(1f), "assistant-history-messages",
                        reattach = if (canReply) ({ draft.add(listOf(ImageSource.Saved(it))) }) else null)
                    val receiver = (recipient as? ReadState.Loaded)?.value
                    if (receiver != null && selected != null) {
                        Text(stringResource(R.string.assistant_history_recipient, receiver.profile.origin, receiver.profile.model),
                            Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        MessageEditor(model, draft, page.scopeName(), {
                            if (model.continueConversation(id, page, receiver)) onClose()
                        }, active = canReply, tagPrefix = "history-reply")
                    } else if (recipient != ReadState.Loading) {
                        if (active && draft.images.isNotEmpty()) DraftImageStrip(model.attachments, draft)
                        val launch = rememberRouteLaunch("model")
                        TextButton(onClick = { launch.open(onSettings) }, modifier = Modifier.align(Alignment.CenterHorizontally).then(launch.modifier)) {
                            Text(if (recipient == ReadState.ReadFailed) stringResource(R.string.assistant_repair_to_continue) else stringResource(R.string.assistant_configure_to_continue))
                        }
                    }
                }
            }
        if (id != null && confirmDelete) PlannerAlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.assistant_delete_title)) }, text = {
                Text(if (deletionError) stringResource(R.string.assistant_delete_failed) else stringResource(R.string.assistant_delete_body))
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.assistant_cancel)) } },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("assistant-history-confirm-delete"), onClick = {
                    scope.launch {
                        try { model.deleteConversation(id); confirmDelete = false; onThread(null) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { deletionError = true }
                    }
                }) { Text(stringResource(R.string.assistant_delete), color = MaterialTheme.colorScheme.error) }
            })
}
