package io.github.nakanomk.stacklife.assistant

import android.text.format.DateUtils
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.appearance.PlannerModalBottomSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationHistorySheet(history: ConversationHistory, delete: suspend (Long) -> Unit, onClose: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val threads by remember(history) { history.observeConversations() }.collectAsStateWithLifecycle(initialValue = null)
    val listState = rememberLazyListState()
    val id = selectedId
    val selected = if (id == null) null else threads?.find { it.id == id }
    LaunchedEffect(id, selected, threads != null) {
        if (id != null && threads != null && selected == null) selectedId = null
    }

    // Modal dismissal (including system Back) pops exactly this level. A fresh sheet state
    // lets the list reappear after the details window has animated all the way to Hidden.
    key(id) {
        val back = { if (id == null) onClose() else selectedId = null }
        var confirmDelete by rememberSaveable { mutableStateOf(false) }
        var deletionError by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        PlannerModalBottomSheet(back, rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (id != null) IconButton(onClick = back, modifier = Modifier.testTag("assistant-history-back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), "返回对话列表")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (id == null) "历史对话" else "对话记录", Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleLarge)
                        Text(if (id == null) "仅保存在本机" else if (selected == null) "只读" else "${selected.scope} · 只读",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (id == null) TextButton(onClick = back) { Text("关闭") }
                    else TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("assistant-history-delete")) { Text("删除") }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                if (id == null) {
                    val rows = threads
                    LazyColumn(Modifier.fillMaxSize().testTag("assistant-history-list"), state = listState) {
                        if (rows == null) item { LinearProgressIndicator() }
                        else if (rows.isEmpty()) item {
                            Text("暂无对话记录", Modifier.padding(vertical = 16.dp).testTag("assistant-history-empty"),
                                style = MaterialTheme.typography.bodyLarge)
                        } else items(rows, key = { it.id }) { row ->
                            val context = LocalContext.current
                            val date = remember(context, LocalConfiguration.current, row.updatedAtMillis) {
                                DateUtils.formatDateTime(context, row.updatedAtMillis, DateUtils.FORMAT_SHOW_DATE or
                                    DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
                            }
                            Row(Modifier.fillMaxWidth().testTag("assistant-history-thread-${row.id}")
                                .clickable(role = Role.Button, onClickLabel = "查看对话") { selectedId = row.id }
                                .heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                    Text("${row.scope} · $date", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(painterResource(R.drawable.ic_chevron_right), null)
                            }
                            HorizontalDivider()
                        }
                    }
                } else {
                    val messages by remember(history, id) { history.observeMessages(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
                    LazyColumn(Modifier.fillMaxSize().testTag("assistant-history-messages"),
                        contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(messages, key = { it.id }) { message ->
                            if (message.role == HistoryRole.NOTICE) {
                                Surface(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("本机记录", style = MaterialTheme.typography.labelLarge)
                                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            } else ChatBubble(ChatLine(message.role == HistoryRole.USER, message.text))
                        }
                    }
                }
            }
        }
        if (id != null && confirmDelete) PlannerAlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("删除这段对话？") }, text = {
                Text(if (deletionError) "未能删除这段对话，可以重试。" else "将从本机删除这段对话的全部消息，不会撤销已应用的日程更改。")
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("assistant-history-confirm-delete"), onClick = {
                    scope.launch {
                        try { delete(id); selectedId = null }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { deletionError = true }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            })
    }
}
