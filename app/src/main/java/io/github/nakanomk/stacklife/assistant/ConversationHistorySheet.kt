package io.github.nakanomk.stacklife.assistant

import java.util.Date
import java.text.SimpleDateFormat
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import io.github.nakanomk.stacklife.appearance.PlannerModalBottomSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationHistorySheet(model: AssistantModel, fallback: AssistantPage, configuring: Boolean,
    onSettings: () -> Unit, onClose: () -> Unit) {
    val history = model.history
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val threads by remember(history) { history.observeConversations() }.collectAsStateWithLifecycle(initialValue = null)
    val listState = rememberLazyListState()
    val id = selectedId
    val selected = if (id == null) null else threads?.find { it.id == id }
    val page = remember(selected, fallback) { selected?.resumePage(fallback) ?: fallback }
    var draft by remember(id) { mutableStateOf("") }
    if (configuring) return
    LaunchedEffect(id, selected, threads != null) {
        if (id != null && threads != null && selected == null) selectedId = null
    }

    // Modal dismissal (including system Back) pops exactly this level. A fresh sheet state
    // lets the list reappear after the details window has animated all the way to Hidden.
    key(id) {
        val back = { if (id == null) onClose() else selectedId = null }
        val recipient by produceState<ReadState<ServiceConnection?>>(ReadState.Loading, model, id) {
            if (id != null) value = try { ReadState.Loaded(model.connections.load()) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { ReadState.ReadFailed }
        }
        var confirmDelete by rememberSaveable { mutableStateOf(false) }
        var deletionError by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        PlannerModalBottomSheet(back, rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).imePadding().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (id != null) IconButton(onClick = back, modifier = Modifier.testTag("assistant-history-back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.assistant_back_to_history))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (id == null) stringResource(R.string.assistant_history) else stringResource(R.string.assistant_transcript), Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleLarge)
                        Text(if (id == null) stringResource(R.string.assistant_local_only) else page.scopeName(),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (id == null) TextButton(onClick = back) { Text(stringResource(R.string.assistant_close)) }
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
                            Row(Modifier.fillMaxWidth().testTag("assistant-history-thread-${row.id}")
                                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.assistant_view_conversation)) { selectedId = row.id }
                                .heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
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
                    val transcript = rememberLazyListState()
                    LaunchedEffect(messages.lastOrNull()?.id) {
                        if (messages.isNotEmpty()) transcript.scrollToItem(messages.lastIndex)
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("assistant-history-messages"), state = transcript,
                        contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(messages, key = { it.id }) { message ->
                            if (message.role == HistoryRole.NOTICE) {
                                Surface(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(stringResource(R.string.assistant_local_record), style = MaterialTheme.typography.labelLarge)
                                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            } else ChatBubble(ChatLine(message.role == HistoryRole.USER, message.text))
                        }
                    }
                    val receiver = (recipient as? ReadState.Loaded)?.value
                    if (receiver != null && selected != null) {
                        Text(stringResource(R.string.assistant_history_recipient, receiver.profile.origin, receiver.profile.model),
                            Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        MessageEditor(draft, page.scopeName(), { draft = it }, {
                            if (model.continueConversation(id, page, draft, receiver)) onClose()
                        }, tagPrefix = "history-reply")
                    } else if (recipient != ReadState.Loading) {
                        TextButton(onClick = onSettings, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text(if (recipient == ReadState.ReadFailed) stringResource(R.string.assistant_repair_to_continue) else stringResource(R.string.assistant_configure_to_continue))
                        }
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
                        try { model.deleteConversation(id); selectedId = null }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { deletionError = true }
                    }
                }) { Text(stringResource(R.string.assistant_delete), color = MaterialTheme.colorScheme.error) }
            })
    }
}
