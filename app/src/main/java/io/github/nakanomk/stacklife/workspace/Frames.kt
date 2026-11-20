package io.github.nakanomk.stacklife.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.transitions.DetailCard
import io.github.nakanomk.stacklife.transitions.DetailListSource
import io.github.nakanomk.stacklife.transitions.detailListSource

@Composable
internal fun FrameList(
    frames: List<FrameView>, scroll: LazyListState, onFrame: (String) -> Unit, onCreate: () -> Unit,
    shared: SharedModifier, bottomClearance: Dp, revision: Long,
) {
    val source = detailListSource(scroll, bottomClearance, revision)
    val available = remember(frames) {
        frames.indexOfFirst { it.remaining == 0 }.let { if (it == -1) frames.size else it }
    }
    LazyColumn(Modifier.fillMaxSize().then(source?.modifier ?: Modifier).testTag("frames"), state = scroll,
        contentPadding = dockListPadding(bottomClearance), verticalArrangement = listSpacing) {
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$available 帧可恢复 · ${frames.size - available} 帧已取空", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Secondary("新建栈帧", onClick = onCreate)
            }
        }
        if (frames.isEmpty()) item {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("还没有栈帧", style = MaterialTheme.typography.titleMedium)
                    Hint("中断时，把队列和原因一起存到这里。也可以直接新建一份稍后再做的清单。")
                }
            }
        }
        if (available > 0) item { SectionTitle("可恢复 · 最近保存优先") }
        items(available, key = { frames[it].id }) { FrameCard(frames[it], onFrame, shared, source) }
        if (available < frames.size) item { SectionTitle("历史 · 已取空") }
        items(frames.size - available, key = { frames[available + it].id }) {
            FrameCard(frames[available + it], onFrame, shared, source)
        }
    }
}

@Composable
private fun FrameCard(frame: FrameView, onFrame: (String) -> Unit, shared: SharedModifier, source: DetailListSource?) {
    val appearance = LocalAppearance.current
    DetailCard(source, "frame", frame.id, onFrame, modifier = shared("frame:${frame.id}").fillMaxWidth().testTag("frame:${frame.id}"),
        shape = RectangleShape, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(vertical = if (appearance.compact) 4.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(frame.reason, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("${savedTime(frame.savedAtMillis)} · 原有 ${frame.items.size} 项 · 剩余 ${frame.remaining} 项",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(frame.items.joinToString("、", limit = 3, truncated = "…") { it.title }, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun FrameScreen(id: String, state: Workspace, actions: WorkspaceActions, onBack: () -> Unit, shared: SharedModifier) {
    val frame = state.frames.find { it.id == id }
    if (frame == null) {
        MissingDetail("栈帧详情", "这份栈帧已不存在。", onBack)
        return
    }
    var choosing by rememberSaveable(id) { mutableStateOf(false) }
    var selected by rememberSaveable(id) { mutableStateOf(arrayListOf<String>()) }
    val entries = frame.items
    val leaveSelection = { choosing = false; selected = arrayListOf() }
    val action = rememberSubmission(actions) { if (it != SaveOutcome.REJECTED) leaveSelection() }
    val selectionIds = remember(entries, selected) {
        val ids = selected.toHashSet()
        entries.mapNotNull { if (it.taskId in ids) it.taskId else null }
    }
    BackHandler(enabled = choosing) { if (!action.busy) leaveSelection() }
    Page(AssistantPage.Frame(id)) {
        LazyColumn(Modifier.weight(1f), contentPadding = pagePadding, verticalArrangement = listSpacing) {
            item { PageTitle("栈帧详情", back = { if (!action.busy) { if (choosing) leaveSelection() else onBack() } }) }
            item {
                Surface(modifier = shared("frame:$id").fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val source = when (frame.source) {
                            FrameSource.PUSH -> "队列压栈"
                            FrameSource.MANUAL -> "直接新建"
                            FrameSource.SWAP -> "切换前自动保存"
                            FrameSource.GRAPH -> "流程图生成"
                        }
                        Text("$source · ${savedTime(frame.savedAtMillis)}", style = MaterialTheme.typography.labelMedium)
                        Text(frame.reason, style = MaterialTheme.typography.titleLarge)
                        Text("原有 ${entries.size} 项 · 剩余 ${frame.remaining} 项", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (frame.remaining > 0) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(if (choosing) "选择恢复" else "恢复到队列")
                    Hint(restoreHint(state.restorePolicy, choosing, state.queue.size))
                    if (!choosing) Actions {
                        Primary("全部恢复", enabled = !action.busy) { action.send(PlannerCommand.FrameRestore(id, RestoreMode.ALL)) }
                        Secondary("选择恢复", enabled = !action.busy) { choosing = true }
                    }
                }
            }
            item {
                SectionTitle("保存时的清单")
                Hint(if (frame.remaining == 0) "这份栈帧已取空。下方保留保存时的内容。" else "按当时的顺序显示；之后的修改不会改写这里。")
            }
            itemsIndexed(entries, key = { _, item -> item.taskId }) { index, item ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("第 ${index + 1} 项 · ${if (item.restorable) "可恢复" else "已取走"}", style = MaterialTheme.typography.labelMedium)
                        if (choosing && item.restorable) {
                            CheckRow(item.title, item.taskId in selected) { selected = ArrayList(selected.toggle(item.taskId)) }
                        } else Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Hint("${item.minutes} 分钟")
                        if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodyMedium)
                        if (item.scheduledStartMillis != null && item.scheduledEndMillis != null) {
                            Hint("保存时的预约：${savedTime(item.scheduledStartMillis)} 至 ${savedTime(item.scheduledEndMillis)}")
                        }
                    }
                }
            }
        }
        if (choosing) Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("已选 ${selectionIds.size} 项 · 按清单原序恢复", style = MaterialTheme.typography.labelLarge)
                Actions {
                    Primary("恢复所选", enabled = !action.busy && selectionIds.isNotEmpty()) {
                        action.send(PlannerCommand.FrameRestore(id, RestoreMode.SELECTED, selectionIds))
                    }
                    Secondary("取消选择", enabled = !action.busy, onClick = leaveSelection)
                }
            }
        }
    }
}

private fun restoreHint(policy: RestorePolicy, selected: Boolean, queueSize: Int): String = when (policy) {
    RestorePolicy.PREPEND -> "插入队首：按帧内原顺序恢复，当前队列排在其后。"
    RestorePolicy.APPEND -> "追加队尾：按帧内原顺序恢复，接在当前队列之后。"
    RestorePolicy.SWAP -> when {
        selected -> "整帧切换仅用于全部恢复。本次所选任务按原序插入队首，不另存当前队列。"
        queueSize == 0 -> "整帧切换：当前队列为空，直接按帧内原序恢复，不新建栈帧。"
        else -> "整帧切换：全部恢复会先把当前 $queueSize 项另存为「切换栈帧前自动保存」，再接回这份清单。选择恢复只插入队首。"
    }
}
