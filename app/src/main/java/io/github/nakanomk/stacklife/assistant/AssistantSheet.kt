package io.github.nakanomk.stacklife.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.CapsuleScene
import io.github.nakanomk.stacklife.appearance.StackLifeSurface
import io.github.nakanomk.stacklife.planner.PlannerChange
import io.github.nakanomk.stacklife.workspace.CheckRow
import io.github.nakanomk.stacklife.workspace.Hint
import java.util.UUID

internal val LocalAssistant = staticCompositionLocalOf<AssistantModel?> { null }

@Composable
internal fun AssistantComposer(page: AssistantPage) {
    key(page) { ScopedComposer(page.label) { page } }
}

@Composable
internal fun AssistantComposer(target: () -> AssistantPage) {
    ScopedComposer("课表导入", target)
}

@Composable
private fun ScopedComposer(scope: String, target: () -> AssistantPage) {
    val model = LocalAssistant.current ?: return
    val id = rememberSaveable { UUID.randomUUID().toString() }
    DisposableEffect(model, id) {
        model.composerReturned(id)
        onDispose { model.composerLeft(id) }
    }
    val ui by model.state.collectAsStateWithLifecycle()
    if (ui.page != null) return
    StackLifeSurface(CapsuleScene.ASSISTANT, Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surfaceContainerHigh) {
        MessageEditor(if (ui.composerId == id) ui.draft else "", scope,
            { model.editLocal(id, it) }, { model.submitLocal(id, target) })
    }
}

@Composable
private fun MessageEditor(text: String, scope: String, onChange: (String) -> Unit, onSend: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    val enabled = text.isSendable()
    fun submit() {
        if (!enabled) return
        keyboard?.hide()
        focus.clearFocus()
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        onSend()
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            BasicTextField(value = text, onValueChange = onChange, singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("assistant-input")
                    .semantics { contentDescription = "$scope · 助理消息" }
                    .onPreviewKeyEvent {
                        if (it.key != Key.Enter && it.key != Key.NumPadEnter) false
                        else { if (it.type == KeyEventType.KeyUp) submit(); true }
                    },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                decorationBox = { field -> Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("给日程助理发消息", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    field()
                } })
        }
        IconButton(onClick = ::submit, enabled = enabled, modifier = Modifier.size(48.dp).testTag("assistant-send"),
            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.primary)) {
            Icon(painterResource(R.drawable.ic_assistant_send), contentDescription = "发送消息")
        }
    }
}

private fun AssistantPage.scopeName() = when (this) {
    is AssistantPage.Day -> "$date · ${zone.id}"
    is AssistantPage.Frame -> "当前栈帧 · ${id.take(8)}"
    is AssistantPage.Task -> "当前任务 · ${id.take(8)}"
    is AssistantPage.Graph -> "当前流程图 · ${id.take(8)}" + (nodeId?.let { " / 节点 ${it.take(8)}" } ?: "")
    is AssistantPage.Week -> "$termId · 第 $week 周"
    is AssistantPage.CourseDraft -> "$termId · 本地已核对草稿"
    AssistantPage.Queue -> "当前队列"
    AssistantPage.Frames -> "栈帧列表"
    AssistantPage.Graphs -> "流程图列表"
    AssistantPage.Terms -> "学期列表"
    AssistantPage.Settings -> "外观与工作台恢复设置"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantSheet(model: AssistantModel, onSettings: () -> Unit, onImport: () -> Unit) {
    val ui by model.state.collectAsStateWithLifecycle()
    val page = ui.page ?: return
    var expanded by rememberSaveable(page) { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || model.state.value.work != AssistantWork.COMMIT })
    var options by remember(page) { mutableStateOf(false) }
    val list = rememberLazyListState()
    val groups = remember(ui.pending) { ui.pending?.changes?.groupBy { it.entityId }.orEmpty() }
    LaunchedEffect(ui.pending?.proposalId) { if (ui.pending != null) expanded = true }
    LaunchedEffect(ui.lines.size, ui.pending, ui.allowed) {
        if (ui.pending != null || !ui.allowed) list.scrollToItem(0)
        else if (ui.lines.isNotEmpty()) list.scrollToItem(ui.lines.lastIndex)
    }
    val compact = ui.profile == null
    ModalBottomSheet(onDismissRequest = model::close, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().then(if (compact) Modifier else Modifier.fillMaxHeight(if (expanded) .90f else .53f))
            .imePadding().padding(horizontal = 16.dp, vertical = 4.dp).testTag("assistant-sheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${page.label}助理", style = MaterialTheme.typography.titleLarge)
                    Hint(page.scopeName())
                }
                if (!compact) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
                TextButton(onClick = model::close, enabled = ui.work != AssistantWork.COMMIT) { Text("关闭") }
            }
            val service = ui.profile
            val ready = ui.pending
            if (service != null) Text("${service.origin} · ${service.model}", style = MaterialTheme.typography.labelMedium)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            LazyColumn(Modifier.weight(1f, fill = !compact).fillMaxWidth().testTag("assistant-scroll"), state = list,
                verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                when {
                    ui.work == AssistantWork.LOADING -> item { LinearProgressIndicator() }
                    service == null -> item { Text("尚未连接模型服务", style = MaterialTheme.typography.titleMedium)
                        Hint("手动日程可离线使用。选择 DeepSeek、Claude、GPT 或自定义服务后，再发送这条消息。") }
                    !ui.allowed -> {
                        item { Text("发送前核对", style = MaterialTheme.typography.titleMedium)
                            Text("你的消息将发往 ${service.origin}。模型可按需读取${page.scopeName()}的标题、顺序、时段及必要 ID；课程默认只披露忙碌时段。")
                            Hint("不发送密钥正文、Cookie 或原始课表。授权仅限此次对话；关闭会清空对话并取消请求。") }
                        if (ui.draft.isNotEmpty()) item { Text("待发送消息", style = MaterialTheme.typography.labelMedium); Text(ui.draft) }
                        item { TextButton(onClick = { options = !options }) { Text(if (options) "收起授权选项" else "调整范围与字段") } }
                        if (options) {
                            items(ReadArea.entries) { area -> CheckRow(area.label, area in ui.grant.extra) { model.setGrant(ui.grant.copy(extra =
                                if (area in ui.grant.extra) ui.grant.extra - area else ui.grant.extra + area)) } }
                            item { CheckRow("发送任务及节点备注", ui.grant.notes) { model.setGrant(ui.grant.copy(notes = !ui.grant.notes)) }
                                CheckRow("发送栈帧原因", ui.grant.reasons) { model.setGrant(ui.grant.copy(reasons = !ui.grant.reasons)) }
                                CheckRow("发送课程名与地点", ui.grant.courseDetails) { model.setGrant(ui.grant.copy(courseDetails = !ui.grant.courseDetails)) } }
                        }
                        item { Hint(grantSummary(ui.grant)) }
                    }
                    ready != null -> {
                        item { Text("变更预览 · 尚未写入", style = MaterialTheme.typography.titleLarge); Hint("以下是本机计算的完整差异。只会应用这一份提案。") }
                        groups.forEach { (id, changes) ->
                            item(key = "head:$id") { Text(changeTitle(id, changes), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
                            items(changes) { change -> Text("${change.field}：${change.before ?: "无"} → ${change.after ?: "无"}", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    else -> {
                        items(ui.lines) { line -> Surface(color = if (line.user) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(if (line.user) "你" else "模型答复", style = MaterialTheme.typography.labelMedium); Text(line.text)
                        } } }
                        if (ui.lines.isEmpty()) item { Text("想怎么安排？", style = MaterialTheme.typography.titleMedium); Hint("可以询问当前页面，也可以描述一组日程更改。模型不能直接保存。") }
                        item { Hint(grantSummary(ui.grant)); TextButton(enabled = ui.work == AssistantWork.IDLE, onClick = { options = true; model.setGrant(ui.grant) }) { Text("重新选择授权范围") } }
                    }
                }
            }
            ui.message?.let { Text(it, color = if (ui.applied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("assistant-status")) }
            when {
                ui.work == AssistantWork.LOADING -> Unit
                service == null -> Button(onClick = { model.leaveForConfiguration(); onSettings() }) { Text("配置模型服务") }
                !ui.allowed -> Button(onClick = { model.authorize(); options = false }, modifier = Modifier.testTag("assistant-consent")) {
                    Text(if (ui.draft.isNotEmpty()) "允许并发送" else "允许本次范围")
                }
                ui.work == AssistantWork.COMMIT -> Text("正在保存已确认的更改…")
                ui.work == AssistantWork.REQUEST -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); TextButton(onClick = model::cancel) { Text("取消请求") } }
                ready != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = model::cancel) { Text("放弃提案") }
                    Button(onClick = { model.apply(ready.proposalId) }, modifier = Modifier.testTag("assistant-apply")) { Text("应用这些更改") } }
                ui.reviewAgain -> Row { TextButton(onClick = model::cancel) { Text("放弃提案") }; Button(onClick = model::refreshProposal) { Text("重新预览") } }
                ui.importRequested -> Row { TextButton(onClick = model::cancel) { Text("取消") }; Button(onClick = { model.close(); onImport() }) { Text("打开本地课表导入器") } }
                else -> Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    MessageEditor(ui.draft, page.label, model::editMessage, model::send)
                }
            }
        }
    }
}

private fun grantSummary(grant: Disclosure) = buildString {
    append("范围：当前页面"); for (area in grant.extra) append("、${area.label}")
    append("。额外字段：")
    append(listOfNotNull("备注".takeIf { grant.notes }, "栈帧原因".takeIf { grant.reasons }, "课程名与地点".takeIf { grant.courseDetails }).joinToString("、").ifEmpty { "无" })
}

private fun changeTitle(id: String, changes: List<PlannerChange>): String {
    return changes.firstNotNullOfOrNull { it.entityLabel }
        ?: changes.firstOrNull { it.field == "标题" || it.field == "流程图名称" || it.field == "压栈原因" }?.let { it.after ?: it.before }
        ?: id
}
