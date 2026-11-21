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
import androidx.compose.ui.res.stringResource
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
import io.github.nakanomk.stacklife.appearance.LocalPanelBack
import io.github.nakanomk.stacklife.transitions.PaneMotion
import io.github.nakanomk.stacklife.transitions.PaneOrigins
import io.github.nakanomk.stacklife.transitions.paneOrigin
import io.github.nakanomk.stacklife.transitions.rememberRouteLaunch
import io.github.nakanomk.stacklife.planner.PlannerChange
import io.github.nakanomk.stacklife.workspace.CheckRow
import io.github.nakanomk.stacklife.workspace.Hint
import java.util.UUID

internal val LocalAssistant = staticCompositionLocalOf<AssistantModel?> { null }
internal val LocalOpenChatHistory = staticCompositionLocalOf<(AssistantPage) -> Unit> { error("Missing conversation history host") }

@Composable
internal fun AssistantComposer(page: AssistantPage, persistentRoot: Boolean = false) {
    ScopedComposer(page.label, page, if (persistentRoot) null else page) { page }
}

@Composable
internal fun AssistantComposer(target: () -> AssistantPage) {
    ScopedComposer(stringResource(R.string.assistant_import_scope), null, null, target)
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
    val openHistory = LocalOpenChatHistory.current
    MessageEditor(if (active && ui.composerId == id) ui.draft else "", origin?.scopeName() ?: scope,
        { model.editLocal(id, it, page) }, { model.submitLocal(id, target) },
        origin?.takeIf { page != null && privateScope == null }?.let { stringResource(R.string.assistant_draft_scope, it.scopeName()) }, active, surface,
        openHistory = { openHistory(page ?: AssistantPage.Terms) })
}

@Composable
internal fun MessageEditor(text: String, scope: String, onChange: (String) -> Unit, onSend: () -> Unit, originHint: String? = null,
    active: Boolean = true, modifier: Modifier = Modifier, openHistory: (() -> Unit)? = null, tagPrefix: String = "assistant",
    historyOrigin: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    var editing by remember { mutableStateOf(false) }
    val solidBorder = when {
        editing -> BorderStroke(1.5.dp, colors.primary)
        colors.surface.luminance() < .5f -> BorderStroke(1.dp, colors.outline)
        else -> null
    }
    val fieldDescription = stringResource(R.string.assistant_message_field, scope)
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
            if (openHistory != null) IconButton(onClick = { keyboard?.hide(); openHistory() }, enabled = active,
                modifier = historyOrigin.size(48.dp).testTag("assistant-history")) {
                Icon(painterResource(R.drawable.ic_history), stringResource(R.string.assistant_history), tint = colors.onSurfaceVariant)
            } else Spacer(Modifier.width(16.dp))
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                BasicTextField(value = text, onValueChange = onChange, singleLine = true, enabled = active,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("$tagPrefix-input")
                        .semantics { contentDescription = fieldDescription }
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
                            if (text.isEmpty()) Text(stringResource(R.string.assistant_message_placeholder), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                            field()
                        }
                    } })
            }
            IconButton(onClick = ::submit, enabled = enabled, modifier = Modifier.size(48.dp).testTag("$tagPrefix-send"),
                colors = IconButtonDefaults.iconButtonColors(contentColor = colors.primary)) {
                Icon(painterResource(R.drawable.ic_assistant_send), contentDescription = stringResource(R.string.assistant_send))
            }
        }
    }
}


private sealed interface AssistantPanel {
    data object Chat : AssistantPanel
    data class History(val fallback: AssistantPage) : AssistantPanel
    data class Thread(val fallback: AssistantPage, val id: Long) : AssistantPanel
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantPanels(model: AssistantModel, history: AssistantPage?, configuring: Boolean,
    onCloseHistory: () -> Unit, onSettings: () -> Unit, onImport: () -> Unit) {
    val ui by model.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable(history) { mutableStateOf<Long?>(null) }
    if (history == null && ui.page == null) return
    val origins = remember { PaneOrigins() }
    val listState = rememberLazyListState()
    val chatState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    if (configuring) return
    val threads = if (history != null) remember(model) { model.history.observeConversations() }
        .collectAsStateWithLifecycle(initialValue = null).value else null
    val thread = selected
    val panel = when {
        history == null -> AssistantPanel.Chat
        thread == null -> AssistantPanel.History(history)
        else -> AssistantPanel.Thread(history, thread)
    }
    val previous = when (panel) {
        is AssistantPanel.Thread -> AssistantPanel.History(panel.fallback)
        is AssistantPanel.History -> AssistantPanel.Chat.takeIf { ui.page != null }
        AssistantPanel.Chat -> null
    }
    fun back() {
        when (panel) {
            is AssistantPanel.Thread -> selected = null
            is AssistantPanel.History -> onCloseHistory()
            AssistantPanel.Chat -> model.close()
        }
    }
    PlannerModalBottomSheet(::back, nested = previous != null, onNestedBack = ::back,
        canDismiss = ui.work != AssistantWork.COMMIT) {
        PaneMotion(panel, origins, depth = { when (it) {
            AssistantPanel.Chat -> 0; is AssistantPanel.History -> 1; is AssistantPanel.Thread -> 2
        } }, originKey = { when (it) {
            AssistantPanel.Chat -> "chat"; is AssistantPanel.History -> "history"; is AssistantPanel.Thread -> "thread:${it.id}"
        } }, parent = previous, onBack = ::back, modifier = Modifier.fillMaxWidth()) { shown ->
            when (shown) {
                AssistantPanel.Chat -> chatState.SaveableStateProvider("chat") { ChatPanel(model, ui, origins, onSettings, onImport) }
                else -> {
                    val id = (shown as? AssistantPanel.Thread)?.id
                    val fallback = if (shown is AssistantPanel.Thread) shown.fallback else (shown as AssistantPanel.History).fallback
                    ConversationHistoryPane(model, fallback, id, origins, threads, listState, onThread = { selected = it },
                        onBack = LocalPanelBack.current ?: ::back,
                        onSettings = onSettings, onClose = onCloseHistory)
                }
            }
        }
    }
}

@Composable
private fun ChatPanel(model: AssistantModel, ui: AssistantState, origins: PaneOrigins, onSettings: () -> Unit, onImport: () -> Unit) {
    val page = ui.page ?: return
    val openHistory = LocalOpenChatHistory.current
    var expanded by rememberSaveable(page) { mutableStateOf(false) }
    var options by remember(page) { mutableStateOf(false) }
    val service = ui.profile
    val ready = ui.pending
    val severity = when {
        ui.message != null -> ui.noticeLevel
        ready != null || service == null && ui.work != AssistantWork.LOADING -> NoticeLevel.WARNING
        else -> NoticeLevel.INFO
    }
    val help = ui.message ?: when {
        ui.work == AssistantWork.LOADING -> stringResource(R.string.assistant_loading_settings)
        service == null -> stringResource(R.string.assistant_offline_help)
        ready != null -> stringResource(R.string.assistant_proposal_help)
        else -> stringResource(R.string.assistant_privacy_help)
    }
    var noticeOpen by remember(page) { mutableStateOf(false) }
    val noticeDescription = stringResource(R.string.assistant_notice_description, severity.label)
    val noticeState = stringResource(if (noticeOpen) R.string.assistant_expanded else R.string.assistant_collapsed)
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
    val settingsLaunch = rememberRouteLaunch("model")
    val importLaunch = rememberRouteLaunch("course-import")
        val density = LocalDensity.current
        val needsHeight = LocalConfiguration.current.screenHeightDp < 480 || density.fontScale >= 1.5f ||
            WindowInsets.ime.getBottom(density) > 0
        Column(Modifier.fillMaxWidth().then(if (compact) Modifier else Modifier.fillMaxHeight(if (expanded || needsHeight) .94f else .56f))
            .imePadding().padding(horizontal = 16.dp, vertical = 4.dp).testTag("assistant-sheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.assistant_page_title, page.label), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(page.scopeName(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { noticeOpen = !noticeOpen }, modifier = Modifier.size(48.dp).testTag("assistant-notice")
                    .semantics {
                        contentDescription = noticeDescription
                        stateDescription = noticeState
                    }) {
                    Surface(shape = CircleShape, color = tint, contentColor = ink) {
                        Box(Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                            Text("!", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                if (!compact && !needsHeight) IconButton(onClick = { expanded = !expanded }) {
                    Icon(painterResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less),
                        contentDescription = if (expanded) stringResource(R.string.assistant_collapse) else stringResource(R.string.assistant_expand))
                }
                TextButton(onClick = LocalPanelBack.current ?: model::close, enabled = ui.work != AssistantWork.COMMIT) { Text(stringResource(R.string.assistant_close)) }
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
                                    Text(if (options) stringResource(R.string.assistant_hide_scope) else stringResource(R.string.assistant_edit_scope))
                                }
                            }
                        }
                    }
                }
                if (noticeOpen && options && canChangeGrant) {
                    items(ReadArea.entries) { area -> CheckRow(area.label, area in ui.grant.extra) { model.setGrant(ui.grant.copy(extra =
                        if (area in ui.grant.extra) ui.grant.extra - area else ui.grant.extra + area)) } }
                    item { CheckRow(stringResource(R.string.assistant_share_notes), ui.grant.notes) { model.setGrant(ui.grant.copy(notes = !ui.grant.notes)) }
                        CheckRow(stringResource(R.string.assistant_share_reasons), ui.grant.reasons) { model.setGrant(ui.grant.copy(reasons = !ui.grant.reasons)) }
                        CheckRow(stringResource(R.string.assistant_share_classes), ui.grant.courseDetails) { model.setGrant(ui.grant.copy(courseDetails = !ui.grant.courseDetails)) } }
                }
                when {
                    ui.work == AssistantWork.LOADING -> item { LinearProgressIndicator() }
                    service == null -> item { Text(stringResource(R.string.assistant_not_connected), style = MaterialTheme.typography.titleMedium) }
                    ready != null -> {
                        item { Text(stringResource(R.string.assistant_preview), style = MaterialTheme.typography.titleMedium) }
                        groups.forEach { (id, changes) ->
                            item(key = "head:$id") { Text(changes.firstNotNullOfOrNull { it.entityLabel } ?: id, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
                            items(changes) { change -> Text(stringResource(R.string.assistant_change, change.field, change.before ?: stringResource(R.string.assistant_none), change.after ?: stringResource(R.string.assistant_none)), style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    else -> {
                        items(ui.lines) { ChatBubble(it) }
                        if (ui.lines.isEmpty()) item { Hint(stringResource(R.string.assistant_empty_hint)) }
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
                service == null -> ActionPill(stringResource(R.string.assistant_configure_service),
                    { settingsLaunch.open { model.leaveForConfiguration(); onSettings() } }, settingsLaunch.modifier, prominent = true)
                ui.work == AssistantWork.COMMIT -> Text(stringResource(R.string.assistant_saving))
                ui.work == AssistantWork.REQUEST -> Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text(stringResource(R.string.assistant_processing), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = model::cancel) { Text(stringResource(R.string.assistant_cancel_request)) }
                }
                ready != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(stringResource(R.string.assistant_discard), model::cancel)
                    ActionPill(stringResource(R.string.assistant_apply), { model.apply(ready.proposalId) }, Modifier.testTag("assistant-apply"), prominent = true)
                }
                ui.reviewAgain -> Row { ActionPill(stringResource(R.string.assistant_discard), model::cancel); ActionPill(stringResource(R.string.assistant_preview_again), model::refreshProposal, prominent = true) }
                ui.importRequested -> Row {
                    ActionPill(stringResource(R.string.assistant_cancel), model::cancel)
                    ActionPill(stringResource(R.string.assistant_open_importer),
                        { importLaunch.open { model.close(); onImport() } }, importLaunch.modifier, prominent = true)
                }
                else -> MessageEditor(ui.draft, page.scopeName(), model::editMessage, model::send, openHistory = { openHistory(page) },
                    historyOrigin = Modifier.paneOrigin(origins, "history"))
            }
        }
}

@Composable
internal fun ChatBubble(line: ChatLine, author: String = if (line.user) stringResource(R.string.assistant_user) else stringResource(R.string.assistant_model_reply)) {
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

@Composable
private fun grantSummary(grant: Disclosure): String {
    val separator = stringResource(R.string.assistant_list_separator)
    val areas = buildList {
        add(stringResource(R.string.assistant_current_page))
        for (area in grant.extra) add(area.label)
    }
    val fields = buildList {
        if (grant.notes) add(stringResource(R.string.assistant_notes))
        if (grant.reasons) add(stringResource(R.string.assistant_reasons))
        if (grant.courseDetails) add(stringResource(R.string.assistant_classes))
    }
    return stringResource(R.string.assistant_scope_summary, areas.joinToString(separator),
        if (fields.isEmpty()) stringResource(R.string.assistant_none) else fields.joinToString(separator))
}
