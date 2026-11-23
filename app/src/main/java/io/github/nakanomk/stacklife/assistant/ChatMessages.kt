package io.github.nakanomk.stacklife.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.markdown.LocalMarkdownReadCache
import io.github.nakanomk.stacklife.markdown.MarkdownReadCache
import io.github.nakanomk.stacklife.markdown.PlannerMarkdown
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@Composable
internal fun ChatMessages(lines: List<ChatLine>, store: AttachmentStore, modifier: Modifier, tag: String,
    emptyText: String? = null, live: Boolean = false, requestedLine: Long? = null, reattach: ((ChatImage) -> Unit)? = null,
    eventActions: @Composable (ChatLine) -> Unit = {}) {
    if (lines.isEmpty()) {
        // A real empty transcript has no rows to subcompose, follow, or parse.
        // Keep the same bottom margin and allow a tall localized hint to scroll.
        Column(modifier.fillMaxSize().testTag(tag).verticalScroll(rememberScrollState(), reverseScrolling = true)
            .padding(vertical = 6.dp), verticalArrangement = Arrangement.Bottom) {
            if (emptyText != null) Text(emptyText, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by rememberSaveable { mutableStateOf(true) }
    var seen by rememberSaveable { mutableStateOf<Long?>(null) }
    var unread by rememberSaveable { mutableStateOf(false) }
    var measuredCount by remember { mutableIntStateOf(-1) }
    val newest = lines.last()
    LaunchedEffect(list) {
        // Item zero is the newest: its edge stays put through IME/pane resizing and asynchronous
        // Markdown measurement. Only consider the viewport of the already presented list here.
        snapshotFlow {
            if (list.layoutInfo.totalItemsCount == measuredCount)
                list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 else null
        }.collect { atTail ->
            if (atTail != null) {
                follow = atTail
                if (atTail) unread = false
            }
        }
    }
    LaunchedEffect(newest.id) {
        if (newest.id == seen) { measuredCount = lines.size; return@LaunchedEffect }
        // Also follow a very fast reply if the sending row and its reply arrived in one frame.
        val ownSend = live && seen != null && lines.asReversed().asSequence().takeWhile { it.id != seen }.any { it.role == HistoryRole.USER }
        if (follow || ownSend || newest.id == requestedLine) {
            follow = true
            list.scrollToItem(0)
            if (newest.event != null) {
                // A large batch may be taller than the sheet. Land on its heading and controls,
                // not the bottom of the last graph in the batch.
                val leading = snapshotFlow {
                    val layout = list.layoutInfo
                    val item = layout.visibleItemsInfo.firstOrNull { it.key == newest.id }
                    item?.let { (it.size - (layout.viewportEndOffset - layout.viewportStartOffset) + layout.beforeContentPadding + layout.afterContentPadding).coerceAtLeast(0) }
                }.first { it != null }
                if (leading != null && leading > 0) list.scrollToItem(0, leading)
            }
        } else unread = true
        seen = newest.id
        measuredCount = lines.size
    }
    CompositionLocalProvider(LocalMarkdownReadCache provides remember { MarkdownReadCache() }) {
        Box(modifier.fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().testTag(tag), state = list, reverseLayout = true,
                contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) {
                items(lines.asReversed(), key = ChatLine::id) { line ->
                    val event = line.event
                    when {
                        event != null -> Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(when (event.status) {
                                    ProposalStatus.PROPOSED -> R.string.assistant_preview
                                    ProposalStatus.APPLIED -> R.string.chat_applied
                                    ProposalStatus.DISCARDED -> R.string.chat_discarded
                                    ProposalStatus.STALE -> R.string.chat_stale
                                    ProposalStatus.FAILED -> R.string.chat_failed
                                }), style = MaterialTheme.typography.titleMedium)
                                event.reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                eventActions(line)
                                event.changes.forEach { change -> key(change.entityId) { ChangePreview(change) } }
                            }
                        }
                        line.role == HistoryRole.NOTICE -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(stringResource(R.string.assistant_local_record), style = MaterialTheme.typography.labelMedium)
                            Text(line.text, style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> ChatBubble(line, store, reattach)
                    }
                }
            }
            // The badge must not resize the viewport and scroll the row currently being read.
            if (unread) FilledTonalButton(modifier = Modifier.align(Alignment.BottomCenter), onClick = {
                follow = true; unread = false
                scope.launch { list.scrollToItem(0) }
            }) { Text(stringResource(R.string.chat_new_messages)) }
        }
    }
}

@Composable
private fun ChatBubble(line: ChatLine, store: AttachmentStore, reattach: ((ChatImage) -> Unit)?) {
    val user = line.role == HistoryRole.USER
    val author = stringResource(if (user) R.string.assistant_user else R.string.assistant_model_reply)
    Box(Modifier.fillMaxWidth().padding(start = if (user) 24.dp else 0.dp).semantics { stateDescription = author },
        contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        if (user) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.End) {
                if (line.images.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    line.images.forEachIndexed { index, image -> key(image.id) { ChatImageThumbnail(store, image, index + 1, 80, reattach) } }
                }
                if (line.text.isNotEmpty()) PlannerMarkdown(line.text)
            }
        } else PlannerMarkdown(line.text)
    }
}
