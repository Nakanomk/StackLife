package io.github.nakanomk.stacklife.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import io.github.nakanomk.stacklife.workspace.CheckRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.nakanomk.stacklife.markdown.MarkdownComposer
import java.util.UUID

internal val LocalAssistant = staticCompositionLocalOf<AssistantModel?> { null }
internal val LocalRootDraftId = staticCompositionLocalOf<String?> { null }
internal val LocalOpenChatHistory = staticCompositionLocalOf<(AssistantPage) -> Unit> { error("Missing conversation history host") }

@Composable
internal fun AssistantComposer(page: AssistantPage, persistentRoot: Boolean = false) {
    ScopedComposer(page.label, page, if (persistentRoot) null else page, if (persistentRoot) LocalRootDraftId.current else null) { page }
}

@Composable
internal fun AssistantComposer(target: () -> AssistantPage) {
    ScopedComposer(stringResource(R.string.assistant_import_scope), null, null, target = target)
}

@Composable
private fun ScopedComposer(scope: String, page: AssistantPage?, privateScope: AssistantPage?, retainedId: String? = null, target: () -> AssistantPage) {
    val model = LocalAssistant.current ?: return
    val id = retainedId ?: rememberSaveable(privateScope) { UUID.randomUUID().toString() }
    val activity = LocalActivity.current
    val draft = remember(id, model) { model.draft(id) }
    DisposableEffect(model, id) {
        model.composerReturned(id)
        onDispose { if (retainedId == null && activity?.isChangingConfigurations != true) model.composerLeft(id) }
    }
    val ui by model.state.collectAsStateWithLifecycle()
    val active = ui.page == null && !model.browsingHistory
    val currentPage by rememberUpdatedState(page)
    LaunchedEffect(draft, active) {
        if (active) snapshotFlow { draft.field.text.isNotEmpty() || draft.images.isNotEmpty() }.collect { hasContent ->
            draft.origin = if (hasContent) draft.origin ?: currentPage else null
        }
    }
    val openHistory = LocalOpenChatHistory.current
    MessageEditor(model, draft, draft.origin?.scopeName() ?: scope, { model.submitLocal(id, target) },
        draft.origin?.takeIf { page != null && it != page && privateScope == null }?.let { stringResource(R.string.assistant_draft_scope, it.scopeName()) },
        active = active, openHistory = { openHistory(page ?: AssistantPage.Terms) })
}

// Fixed-corner field and decorated text slot adapted from BiliPai AppCompactInputField.kt
// (5893c2c80739e3759ac64ae5f232cf03a94da7f8, GPL-3.0); keep the native Markdown editor and IME.
@Composable
internal fun MessageEditor(model: AssistantModel, draft: ChatDraft, scope: String, onSend: () -> Unit, originHint: String? = null,
    active: Boolean = true, openHistory: (() -> Unit)? = null, tagPrefix: String = "assistant", historyOrigin: Modifier = Modifier) {
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
    val enabled = active && draft.sendable
    val picker = rememberLauncherForActivityResult(PickMultipleVisualMedia(MAX_CHAT_IMAGES)) { uris ->
        if (active) draft.add(uris.map { ImageSource.Picker(it) })
    }
    val submit: () -> Unit = {
        if (active && draft.sendable) {
            keyboard?.hide()
            focus.clearFocus()
            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onSend()
        }
    }
    Column(if (active) Modifier.testTag("$tagPrefix-composer") else Modifier.clearAndSetSemantics {}) {
        if (active) DraftImageStrip(model.attachments, draft)
        if (originHint != null) Text(originHint, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        StackLifeSurface(CapsuleScene.ASSISTANT, Modifier.fillMaxWidth(), colors.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp), solidBorder = solidBorder) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                val button = Modifier.align(Alignment.Bottom).size(48.dp)
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }, enabled = active,
                    modifier = button.testTag("$tagPrefix-attach")) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.chat_attach), Modifier.size(24.dp))
                }
                if (openHistory != null) IconButton(onClick = { keyboard?.hide(); openHistory() }, enabled = active,
                    modifier = historyOrigin.then(button).testTag("assistant-history")) {
                    Icon(painterResource(R.drawable.ic_history), stringResource(R.string.assistant_history), Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                }
                val field = Modifier.weight(1f).padding(vertical = 8.dp)
                val placeholder: @Composable () -> Unit = {
                    Text(stringResource(R.string.assistant_message_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                if (active) MarkdownComposer(draft.field, field.testTag("$tagPrefix-input")
                    .semantics { contentDescription = fieldDescription }.onFocusChanged { editing = it.isFocused }, submit, placeholder)
                else Box(field) { placeholder() }
                IconButton(onClick = submit, enabled = enabled, modifier = button.testTag("$tagPrefix-send"),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.primary)) {
                    Icon(painterResource(R.drawable.ic_assistant_send), stringResource(R.string.assistant_send), Modifier.size(24.dp))
                }
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
    SideEffect { model.browsingHistory = history != null }
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
    val back: () -> Unit = {
        when (panel) {
            is AssistantPanel.Thread -> { model.leaveDraft("history:${panel.id}"); selected = null }
            is AssistantPanel.History -> onCloseHistory()
            AssistantPanel.Chat -> model.close()
        }
    }
    PlannerModalBottomSheet(back, nested = previous != null, onNestedBack = back,
        canDismiss = ui.work != AssistantWork.COMMIT) {
        PaneMotion(panel, origins, depth = { when (it) {
            AssistantPanel.Chat -> 0; is AssistantPanel.History -> 1; is AssistantPanel.Thread -> 2
        } }, originKey = { when (it) {
            AssistantPanel.Chat -> "chat"; is AssistantPanel.History -> "history"; is AssistantPanel.Thread -> "thread:${it.id}"
        } }, parent = previous.takeUnless { ui.work == AssistantWork.COMMIT }, onBack = back, modifier = Modifier.fillMaxSize()) { shown ->
            when (shown) {
                AssistantPanel.Chat -> chatState.SaveableStateProvider("chat") { ChatPanel(model, ui, shown == panel, origins, onSettings, onImport) }
                else -> {
                    val id = (shown as? AssistantPanel.Thread)?.id
                    val fallback = if (shown is AssistantPanel.Thread) shown.fallback else (shown as AssistantPanel.History).fallback
                    ConversationHistoryPane(model, fallback, id, origins, threads, listState, shown == panel, onThread = {
                        if (it == null && id != null) model.leaveDraft("history:$id")
                        selected = it
                    },
                        onBack = LocalPanelBack.current ?: back,
                        onSettings = onSettings, onClose = onCloseHistory)
                }
            }
        }
    }
}

@Composable
private fun ChatPanel(model: AssistantModel, ui: AssistantState, active: Boolean, origins: PaneOrigins, onSettings: () -> Unit, onImport: () -> Unit) {
    val page = ui.page ?: return
    val openHistory = LocalOpenChatHistory.current
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
    val draft = ui.composerId?.let(model::draft) ?: return
    val settingsLaunch = rememberRouteLaunch("model")
    val importLaunch = rememberRouteLaunch("course-import")
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp).testTag("assistant-sheet")) {
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
                TextButton(onClick = LocalPanelBack.current ?: model::close, enabled = ui.work != AssistantWork.COMMIT) { Text(stringResource(R.string.assistant_close)) }
            }
            if (service != null) Text("${service.origin} · ${service.model}", style = MaterialTheme.typography.labelMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            if (noticeOpen) {
                Column(Modifier.fillMaxWidth().weight(if (options) 1.6f else 0.9f, fill = false).verticalScroll(rememberScrollState()).testTag("assistant-notice-scroll")) {
                    Surface(Modifier.fillMaxWidth().testTag("assistant-notice-details"), shape = MaterialTheme.shapes.small,
                        color = tint, contentColor = ink) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(severity.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                if (canChangeGrant) TextButton(onClick = { options = !options }) {
                                    Text(stringResource(if (options) R.string.assistant_hide_scope else R.string.assistant_edit_scope))
                                }
                            }
                            if (!options || !canChangeGrant) {
                                Text(help, style = MaterialTheme.typography.bodyMedium)
                                if (service != null) Text(grantSummary(ui.grant), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (options && canChangeGrant) {
                        CheckRow(stringResource(R.string.assistant_share_notes), ui.grant.notes) { model.setGrant(ui.grant.copy(notes = !ui.grant.notes)) }
                        CheckRow(stringResource(R.string.assistant_share_reasons), ui.grant.reasons) { model.setGrant(ui.grant.copy(reasons = !ui.grant.reasons)) }
                        CheckRow(stringResource(R.string.assistant_share_classes), ui.grant.courseDetails) { model.setGrant(ui.grant.copy(courseDetails = !ui.grant.courseDetails)) }
                        ReadArea.entries.forEach { area -> CheckRow(area.label, area in ui.grant.extra) { model.setGrant(ui.grant.copy(extra =
                            if (area in ui.grant.extra) ui.grant.extra - area else ui.grant.extra + area)) } }
                    }
                }
            }
            ChatMessages(ui.lines, model.attachments, Modifier.weight(1f), "assistant-scroll",
                emptyText = when {
                    ui.work == AssistantWork.LOADING -> stringResource(R.string.assistant_loading_settings)
                    service == null -> stringResource(R.string.assistant_not_connected)
                    else -> stringResource(R.string.assistant_empty_hint)
                }, live = true, requestedLine = ui.requestedPreview, reattach = if (active && canChangeGrant) ({ draft.add(listOf(ImageSource.Saved(it))) }) else null) { line ->
                if (active && line.id == ui.eventId && ui.work == AssistantWork.IDLE) {
                    if (ready != null) FlowRow {
                        TextButton(onClick = model::cancel) { Text(stringResource(R.string.assistant_discard)) }
                        ActionPill(stringResource(R.string.assistant_apply), { model.apply(ready.proposalId) }, Modifier.testTag("assistant-apply"), prominent = true)
                    } else if (ui.reviewAgain) FlowRow {
                        TextButton(onClick = model::cancel) { Text(stringResource(R.string.assistant_discard)) }
                        ActionPill(stringResource(R.string.assistant_preview_again), model::refreshProposal)
                    }
                }
            }
            ui.message?.takeIf { !noticeOpen && (severity != NoticeLevel.INFO || ui.importRequested) }?.let {
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
                ready != null || ui.reviewAgain -> Unit
                ui.importRequested -> Row {
                    ActionPill(stringResource(R.string.assistant_cancel), model::cancel)
                    ActionPill(stringResource(R.string.assistant_open_importer),
                        { importLaunch.open { model.close(); onImport() } }, importLaunch.modifier, prominent = true)
                }
                else -> MessageEditor(model, draft, page.scopeName(), model::send, active = active, openHistory = { openHistory(page) },
                    historyOrigin = Modifier.paneOrigin(origins, "history"))
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
