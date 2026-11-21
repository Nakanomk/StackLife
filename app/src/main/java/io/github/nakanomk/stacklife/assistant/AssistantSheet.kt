package io.github.nakanomk.stacklife.assistant

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.CapsuleScene
import io.github.nakanomk.stacklife.appearance.StackLifeSurface
import io.github.nakanomk.stacklife.appearance.PlannerModalBottomSheet
import io.github.nakanomk.stacklife.workspace.ActionPill
import io.github.nakanomk.stacklife.planner.PlannerChange
import io.github.nakanomk.stacklife.workspace.CheckRow
import io.github.nakanomk.stacklife.workspace.Hint
import java.util.UUID

internal val LocalAssistant = staticCompositionLocalOf<AssistantModel?> { null }
internal val LocalOpenChatHistory = staticCompositionLocalOf<() -> Unit> { error("Missing conversation history host") }

@Composable
internal fun AssistantComposer(page: AssistantPage, persistentRoot: Boolean = false) {
    ScopedComposer(page.label, page, if (persistentRoot) null else page) { page }
}

@Composable
internal fun AssistantComposer(target: () -> AssistantPage) {
    ScopedComposer("课表导入", null, null, target)
}

@Composable
private fun ScopedComposer(scope: String, page: AssistantPage?, privateScope: AssistantPage?, target: () -> AssistantPage) {
    val model = LocalAssistant.current ?: return
    val id = rememberSaveable(privateScope) { UUID.randomUUID().toString() }
    DisposableEffect(model, id) {
        model.composerReturned(id)
        onDispose { model.composerLeft(id) }
    }
    val ui by model.state.collectAsStateWithLifecycle()
    val active = ui.page == null
    val surface = if (active) Modifier else Modifier.clearAndSetSemantics {}
    val origin = ui.draftPage.takeIf { active && ui.composerId == id }
    MessageEditor(if (active && ui.composerId == id) ui.draft else "", origin?.scopeName() ?: scope,
        { model.editLocal(id, it, page) }, { model.submitLocal(id, target) },
        origin?.takeIf { page != null && privateScope == null }?.let { "草稿范围：${it.scopeName()}" }, active, surface)
}

@Composable
private fun MessageEditor(text: String, scope: String, onChange: (String) -> Unit, onSend: () -> Unit, originHint: String? = null,
    active: Boolean = true, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    val openHistory = LocalOpenChatHistory.current
    var editing by remember { mutableStateOf(false) }
    val solidBorder = when {
        editing -> BorderStroke(1.5.dp, colors.primary)
        colors.surface.luminance() < .5f -> BorderStroke(1.dp, colors.outline)
        else -> null
    }
    val enabled = active && text.isSendable()
    fun submit() {
        if (!enabled) return
        keyboard?.hide()
        focus.clearFocus()
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        onSend()
    }
    StackLifeSurface(CapsuleScene.ASSISTANT, Modifier.fillMaxWidth().then(modifier), colors.surfaceContainerHigh, solidBorder = solidBorder) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { keyboard?.hide(); openHistory() }, enabled = active,
                modifier = Modifier.size(48.dp).testTag("assistant-history")) {
                Icon(painterResource(R.drawable.ic_history), "历史对话", tint = colors.onSurfaceVariant)
            }
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                BasicTextField(value = text, onValueChange = onChange, singleLine = true, enabled = active,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("assistant-input")
                        .semantics { contentDescription = "$scope · 助理消息" }
                        .onFocusChanged { editing = it.isFocused }
                        .onPreviewKeyEvent {
                            if (it.key != Key.Enter && it.key != Key.NumPadEnter) false
                            else { if (it.type == KeyEventType.KeyUp) submit(); true }
                        },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    decorationBox = { field -> Column(verticalArrangement = Arrangement.Center) {
                        if (originHint != null) Text(originHint, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) Text("给日程助理发消息", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                            field()
                        }
                    } })
            }
            IconButton(onClick = ::submit, enabled = enabled, modifier = Modifier.size(48.dp).testTag("assistant-send"),
                colors = IconButtonDefaults.iconButtonColors(contentColor = colors.primary)) {
                Icon(painterResource(R.drawable.ic_assistant_send), contentDescription = "发送消息")
            }
        }
    }
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
    val service = ui.profile
    val ready = ui.pending
    val severity = when {
        ui.message != null -> ui.noticeLevel
        ready != null || service == null && ui.work != AssistantWork.LOADING -> NoticeLevel.WARNING
        else -> NoticeLevel.INFO
    }
    val help = ui.message ?: when {
        ui.work == AssistantWork.LOADING -> "正在读取本机服务配置，尚未联网。"
        service == null -> "手动日程可离线使用。配置模型服务后再发送，草稿留在本机。"
        ready != null -> "以下差异由本机计算；应用只提交这一份提案，放弃不会写入。"
        else -> "点发送即发往当前服务。默认只开放当前页面；课程只披露忙碌时段。对话保存在本机，查看历史不会联网。"
    }
    var noticeOpen by remember(page) { mutableStateOf(false) }
    val canChangeGrant = service != null && ready == null && !ui.reviewAgain && !ui.importRequested && ui.work == AssistantWork.IDLE
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val tint = when (severity) {
        NoticeLevel.INFO -> if (dark) Color(0xFF0C2D6B) else Color(0xFFDDF4FF)
        NoticeLevel.WARNING -> if (dark) Color(0xFF4B2900) else Color(0xFFFFF8C5)
        NoticeLevel.ERROR -> if (dark) Color(0xFF67060C) else Color(0xFFFFEBE9)
    }
    val ink = when (severity) {
        NoticeLevel.INFO -> if (dark) Color(0xFFA5D6FF) else Color(0xFF0550AE)
        NoticeLevel.WARNING -> if (dark) Color(0xFFF2CC60) else Color(0xFF7D4E00)
        NoticeLevel.ERROR -> if (dark) Color(0xFFFFC1BA) else Color(0xFFA40E26)
    }
    val list = rememberLazyListState()
    val groups = remember(ui.pending) { ui.pending?.changes?.groupBy { it.entityId }.orEmpty() }
    LaunchedEffect(ui.pending?.proposalId) { if (ui.pending != null) expanded = true }
    LaunchedEffect(ui.lines.lastOrNull(), ui.pending, noticeOpen) {
        if (noticeOpen || ui.pending != null) list.scrollToItem(0)
        else if (ui.lines.isNotEmpty()) list.scrollToItem(ui.lines.lastIndex)
    }
    val compact = ui.profile == null
    PlannerModalBottomSheet(onDismissRequest = model::close, sheetState = sheet) {
        val density = LocalDensity.current
        val needsHeight = LocalConfiguration.current.screenHeightDp < 480 || density.fontScale >= 1.5f ||
            WindowInsets.ime.getBottom(density) > 0
        Column(Modifier.fillMaxWidth().then(if (compact) Modifier else Modifier.fillMaxHeight(if (expanded || needsHeight) .94f else .56f))
            .imePadding().padding(horizontal = 16.dp, vertical = 4.dp).testTag("assistant-sheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${page.label}助理", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(page.scopeName(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { noticeOpen = !noticeOpen }, modifier = Modifier.size(48.dp).testTag("assistant-notice")
                    .semantics {
                        contentDescription = "${severity.label}与说明"
                        stateDescription = if (noticeOpen) "已展开" else "已收起"
                    }) {
                    Surface(shape = CircleShape, color = tint, contentColor = ink) {
                        Box(Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                            Text("!", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                if (!compact && !needsHeight) IconButton(onClick = { expanded = !expanded }) {
                    Icon(painterResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less),
                        contentDescription = if (expanded) "收起对话" else "展开对话")
                }
                TextButton(onClick = model::close, enabled = ui.work != AssistantWork.COMMIT) { Text("关闭") }
            }
            if (service != null) Text("${service.origin} · ${service.model}", style = MaterialTheme.typography.labelMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            LazyColumn(Modifier.weight(1f, fill = !compact).fillMaxWidth().testTag("assistant-scroll"), state = list,
                verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                if (noticeOpen) item {
                    Surface(Modifier.fillMaxWidth().testTag("assistant-notice-details"), shape = MaterialTheme.shapes.small,
                        color = tint, contentColor = ink) {
                        Column(Modifier.padding(12.dp)) {
                            Text(severity.label, style = MaterialTheme.typography.labelLarge)
                            Text(help, style = MaterialTheme.typography.bodyMedium)
                            if (service != null) {
                                Text(grantSummary(ui.grant), style = MaterialTheme.typography.bodyMedium)
                                if (canChangeGrant) TextButton(onClick = { options = !options }) {
                                    Text(if (options) "收起数据范围" else "调整数据范围")
                                }
                            }
                        }
                    }
                }
                if (noticeOpen && options && canChangeGrant) {
                    items(ReadArea.entries) { area -> CheckRow(area.label, area in ui.grant.extra) { model.setGrant(ui.grant.copy(extra =
                        if (area in ui.grant.extra) ui.grant.extra - area else ui.grant.extra + area)) } }
                    item { CheckRow("发送任务及节点备注", ui.grant.notes) { model.setGrant(ui.grant.copy(notes = !ui.grant.notes)) }
                        CheckRow("发送栈帧原因", ui.grant.reasons) { model.setGrant(ui.grant.copy(reasons = !ui.grant.reasons)) }
                        CheckRow("发送课程名与地点", ui.grant.courseDetails) { model.setGrant(ui.grant.copy(courseDetails = !ui.grant.courseDetails)) } }
                }
                when {
                    ui.work == AssistantWork.LOADING -> item { LinearProgressIndicator() }
                    service == null -> item { Text("尚未连接模型服务", style = MaterialTheme.typography.titleMedium) }
                    ready != null -> {
                        item { Text("变更预览 · 尚未写入", style = MaterialTheme.typography.titleMedium) }
                        groups.forEach { (id, changes) ->
                            item(key = "head:$id") { Text(changeTitle(id, changes), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
                            items(changes) { change -> Text("${change.field}：${change.before ?: "无"} → ${change.after ?: "无"}", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    else -> {
                        items(ui.lines) { ChatBubble(it) }
                        if (ui.lines.isEmpty()) item { Hint("可以询问当前日程，也可以描述想做的更改。") }
                    }
                }
            }
            ui.message?.takeIf { !noticeOpen && (severity != NoticeLevel.INFO || ui.applied || ui.importRequested) }?.let {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    .testTag("assistant-status"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(severity.label, color = ink, style = MaterialTheme.typography.labelLarge)
                    Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
            }
            when {
                ui.work == AssistantWork.LOADING -> Unit
                service == null -> ActionPill("配置模型服务", { model.leaveForConfiguration(); onSettings() }, prominent = true)
                ui.work == AssistantWork.COMMIT -> Text("正在保存已确认的更改…")
                ui.work == AssistantWork.REQUEST -> Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("正在处理请求…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = model::cancel) { Text("取消请求") }
                }
                ready != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill("放弃提案", model::cancel)
                    ActionPill("应用这些更改", { model.apply(ready.proposalId) }, Modifier.testTag("assistant-apply"), prominent = true)
                }
                ui.reviewAgain -> Row { ActionPill("放弃提案", model::cancel); ActionPill("重新预览", model::refreshProposal, prominent = true) }
                ui.importRequested -> Row { ActionPill("取消", model::cancel); ActionPill("打开本地课表导入器", { model.close(); onImport() }, prominent = true) }
                else -> MessageEditor(ui.draft, page.scopeName(), model::editMessage, model::send)
            }
        }
    }
}

@Composable
internal fun ChatBubble(line: ChatLine, author: String = if (line.user) "你" else "模型答复") {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = if (line.user) 40.dp else 0.dp, end = if (line.user) 0.dp else 40.dp),
        horizontalArrangement = if (line.user) Arrangement.End else Arrangement.Start) {
        Surface(Modifier.widthIn(max = 440.dp).semantics(mergeDescendants = true) { stateDescription = author },
            shape = MaterialTheme.shapes.large, color = if (line.user) colors.secondaryContainer else colors.surfaceContainerHigh,
            contentColor = if (line.user) colors.onSecondaryContainer else colors.onSurface) {
            Text(line.text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
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
