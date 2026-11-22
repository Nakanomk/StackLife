package io.github.nakanomk.stacklife.courses

import io.github.nakanomk.stacklife.R
import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.i18n.uiText
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.RootPane
import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.assistant.AssistantComposer
import io.github.nakanomk.stacklife.workspace.*
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.planner.PreviewResult
import io.github.nakanomk.stacklife.transitions.PaneMotion
import io.github.nakanomk.stacklife.transitions.PaneOrigins
import io.github.nakanomk.stacklife.transitions.paneOrigin
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray

private val chromePackages = listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")

private sealed class ImportStep(val key: String, val depth: Int) {
    data object Source : ImportStep("source", 0)
    data object Hub : ImportStep("hub", 1)
    class Calendar(val rows: JsonArray, fromHub: Boolean) : ImportStep(if (fromHub) "hub-calendar" else "file-calendar", 2)
    class Entries(val draft: ImportDraft) : ImportStep("entries", 3)
    class Fix(val issue: ImportIssue, val fields: CourseFields, val term: TermSpec) : ImportStep("issue:${issue.position}", 4)
    class Diff(val draft: ReviewedTimetable, val ready: PreviewResult.Ready, origin: String) : ImportStep(origin, 4)
}

@Composable
internal fun HustImportScreen(model: WorkspaceViewModel, onBack: () -> Unit, onImported: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resolver = context.contentResolver
    var rows by remember { mutableStateOf<JsonArray?>(null) }
    var hub by remember { mutableStateOf(false) }
    var fromHub by remember { mutableStateOf(false) }
    val origins = remember { PaneOrigins() }
    var form by remember { mutableStateOf(TermForm()) }
    var hints by remember { mutableStateOf(emptyList<String>()) }
    var draft by remember { mutableStateOf<ImportDraft?>(null) }
    val decisions = remember { mutableStateMapOf<String, IssueDecision>() }
    var editing by remember { mutableStateOf<ImportIssue?>(null) }
    var proposal by remember { mutableStateOf<PreviewResult.Ready?>(null) }
    var effective by remember { mutableStateOf<ReviewedTimetable?>(null) }
    var previewOrigin by remember { mutableStateOf("diff-top") }
    var confirmedTerm by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val submission = rememberSubmission(model, onError = { message = it }) { outcome ->
        val term = confirmedTerm
        confirmedTerm = null
        proposal = null
        effective = null
        if (outcome == SaveOutcome.APPLIED) onImported(checkNotNull(term))
    }
    val writing = submission.busy
    var busy by remember { mutableStateOf(false) }
    var attached by remember { mutableStateOf(true) }
    DisposableEffect(model) {
        attached = true
        onDispose {
            attached = false
            if (!submission.busy) proposal?.let { model.discardProposal(it.proposalId) }
        }
    }
    fun receive(data: JsonArray) {
        fromHub = hub
        hub = false
        if (data.isEmpty()) { message = uiText("The raw array is empty. Your existing timetable has not changed.", "原始数组为空，已有课表未更改"); return }
        hints = firstWeekHints(data)
        rows = data
        val hint = hints.singleOrNull()?.let { text ->
            try { LocalDate.parse(text).takeIf { it.year in 1..9999 } } catch (_: DateTimeParseException) { null }
        }
        form = TermForm(monday = hint)
        message = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val data = withContext(Dispatchers.IO) {
                        resolver.openInputStream(uri)?.use(::readWeekFile) ?: throw SourceProblem(uiText("Cannot open the selected file.", "无法打开所选文件"))
                    }
                    receive(data)
                } catch (e: SourceProblem) { message = e.message }
                catch (_: SecurityException) { message = uiText("Read permission denied. Select the raw file again.", "没有读取权限，请重新选择原始文件") }
                catch (_: IOException) { message = uiText("Cannot read the UTF-8 file. Select the raw JSON again.", "无法读取 UTF-8 文件，请重新选择原始 JSON") }
                finally { busy = false }
            }
        }
    }
    fun chooseFile() {
        message = null
        try { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
        catch (_: ActivityNotFoundException) { message = uiText("No system file picker is available on this device.", "设备没有可用的系统文件选择器") }
    }
    fun openChrome() {
        message = null
        val chrome = CustomTabsClient.getPackageName(context, chromePackages, true)
        if (chrome == null) {
            message = uiText("No supported Chrome browser was found.", "未找到可用的 Chrome 浏览器")
            return
        }
        val tab = CustomTabsIntent.Builder().build()
        tab.intent.setPackage(chrome)
        try { tab.launchUrl(context, Uri.parse(HUB_LOGIN_URL)) }
        catch (_: ActivityNotFoundException) { message = uiText("Cannot open Chrome.", "无法打开 Chrome 浏览器") }
        catch (_: SecurityException) { message = uiText("The system did not allow Chrome to open.", "系统不允许启动 Chrome 浏览器") }
    }
    fun leavePreview() {
        if (submission.busy) return
        proposal?.let { model.discardProposal(it.proposalId) }
        proposal = null
        effective = null
        message = null
    }
    fun cancel() {
        if (submission.busy || !attached) return
        attached = false
        leavePreview()
        onBack()
    }
    val raw = rows
    val parsed = draft
    val ready = proposal
    val issue = editing
    val calendar = remember(raw, fromHub) { raw?.let { ImportStep.Calendar(it, fromHub) } }
    val entries = remember(parsed) { parsed?.let { ImportStep.Entries(it) } }
    val fix = remember(issue, parsed) {
        if (issue == null || parsed == null) null else ((decisions[issue.position] as? IssueDecision.Correct)?.fields ?: issue.fields)?.let {
            ImportStep.Fix(issue, it, parsed.term)
        }
    }
    val reviewed = effective
    val diff = remember(ready, reviewed, previewOrigin) {
        if (ready == null || reviewed == null) null else ImportStep.Diff(reviewed, ready, previewOrigin)
    }
    val step = if (hub) ImportStep.Hub else fix ?: diff ?: entries ?: calendar ?: ImportStep.Source
    val previous = when (step) {
        is ImportStep.Diff, is ImportStep.Fix -> entries
        is ImportStep.Entries -> calendar
        is ImportStep.Calendar -> ImportStep.Source
        else -> null // The WebView owns its history and wipes its session before leaving.
    }
    fun backStep() {
        if (busy || writing) return
        when (step) {
            is ImportStep.Diff -> leavePreview()
            is ImportStep.Fix -> editing = null
            is ImportStep.Entries -> { draft = null; decisions.clear(); message = null }
            is ImportStep.Calendar -> { rows = null; hints = emptyList(); message = null }
            else -> cancel()
        }
    }
    BackHandler(enabled = !hub && (writing || busy)) { }
    val calendarScroll = rememberScrollState()
    val entriesScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val steps: @Composable (Dp) -> Unit = { top ->
        PaneMotion(step, origins, depth = { it.depth }, originKey = { it.key }, modifier = Modifier.fillMaxSize(),
            parent = previous.takeUnless { busy || writing }, onBack = ::backStep) { shown ->
        when (shown) {
            ImportStep.Hub -> HubWebView(onRows = ::receive, onBack = { hub = false }, onFile = { hub = false; chooseFile() },
                extractOrigin = Modifier.paneOrigin(origins, "hub-calendar"))
            ImportStep.Source -> if (!writing) {
                ImportPane(composer = { if (!busy && !hub) AssistantComposer(AssistantPage.Terms) }) { bottom ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(dockListPadding(top, bottom))) {
                        SectionTitle(stringResource(R.string.courses_choose_source))
                        FormGroup {
                            PreferenceRow(stringResource(R.string.courses_hub_extract), modifier = Modifier.paneOrigin(origins, "hub")
                                .paneOrigin(origins, "hub-calendar").testTag("importCourseHub"), enabled = !busy) { message = null; hub = true }
                            HorizontalDivider()
                            PreferenceRow(stringResource(R.string.courses_browse_chrome), modifier = Modifier.testTag("browseHubChrome"), enabled = !busy, onClick = ::openChrome)
                            HorizontalDivider()
                            PreferenceRow(stringResource(R.string.courses_choose_json), modifier = Modifier.paneOrigin(origins, "file-calendar").testTag("importCourseFile"), enabled = !busy, onClick = ::chooseFile)
                        }
                    }
                }
            } else Unit
            is ImportStep.Calendar -> Column(Modifier.fillMaxSize().verticalScroll(calendarScroll).padding(dockListPadding(top, 0.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.courses_confirm_calendar), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.courses_confirm_calendar_hint))
                if (hints.isNotEmpty()) Text(stringResource(R.string.courses_week_hint, hints.joinToString(stringResource(R.string.courses_list_separator))))
                TermFields(form, !busy) { form = it }
                ActionPill(stringResource(R.string.courses_review_entries), enabled = !busy, prominent = true, modifier = Modifier.paneOrigin(origins, "entries").testTag("reviewCourseRows"), onClick = {
                    if (busy) return@ActionPill
                    busy = true; message = null
                    scope.launch {
                        try {
                            val term = form.term()
                            draft = withContext(Dispatchers.Default) { HustCourseImporter.parse(shown.rows, term) }
                            decisions.clear()
                        } catch (e: IllegalArgumentException) { message = e.message }
                        finally { busy = false }
                    }
                })
                Secondary(stringResource(R.string.courses_choose_new_source), !busy) { rows = null; hints = emptyList(); message = null }
            }
            is ImportStep.Entries -> {
                val parsed = shown.draft
                val canPreview = !busy && !writing && proposal == null && decisions.size == parsed.issues.size
                fun preview(origin: String) {
                    if (busy || submission.busy || proposal != null || !attached || decisions.size != parsed.issues.size) return
                    previewOrigin = origin
                    busy = true; message = null
                    val choices = decisions.toMap()
                    scope.launch {
                        try {
                            val reviewed = withContext(Dispatchers.Default) { parsed.resolve(choices) }
                            val owner = currentCoroutineContext()
                            withContext(NonCancellable) {
                                when (val preview = model.previewImport(reviewed.term, reviewed.meetings, reviewed.source)) {
                                    is PreviewResult.Ready -> if (owner.isActive && attached) {
                                        effective = reviewed; proposal = preview
                                    } else model.discardProposal(preview.proposalId)
                                    is PreviewResult.Rejected -> if (attached) message = preview.message
                                }
                            }
                        } catch (e: IllegalArgumentException) { message = e.message }
                        finally { busy = false }
                    }
                }
                LazyColumn(Modifier.fillMaxSize().testTag("courseIssues"), state = entriesScroll, contentPadding = dockListPadding(top, 0.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(stringResource(R.string.courses_review_source), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.courses_review_counts, parsed.term.id, parsed.validCount, parsed.issues.size - decisions.size))
                    Text(stringResource(R.string.courses_issue_counts, decisions.values.count { it is IssueDecision.Correct }, decisions.values.count { it == IssueDecision.Exclude }))
                    ActionPill(stringResource(R.string.courses_preview_import), { preview("diff-top") },
                        Modifier.paneOrigin(origins, "diff-top").testTag("previewCourseImportTop"), enabled = canPreview, prominent = true)
                    Secondary(stringResource(R.string.courses_edit_term), !busy) { draft = null; decisions.clear(); message = null }
                }
                itemsIndexed(parsed.issues, key = { _, issue -> issue.position }) { index, issue ->
                    val decision = decisions[issue.position]
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(issue.label, style = MaterialTheme.typography.titleSmall)
                            Text(issue.reason)
                            issue.fields?.let { Text(stringResource(R.string.courses_raw_entry, it.week.ifEmpty { "?" }, it.title.ifEmpty { stringResource(R.string.courses_no_name) }, it.period, it.room.ifEmpty { stringResource(R.string.courses_no_location) })) }
                            when (decision) {
                                null -> Unit
                                IssueDecision.Exclude -> Text(stringResource(R.string.courses_excluded))
                                is IssueDecision.Correct -> Text(stringResource(R.string.courses_corrected_entry, decision.fields.week, decision.fields.title, decision.fields.period, decision.fields.room.ifEmpty { stringResource(R.string.courses_no_location) }))
                            }
                            Actions {
                                if (issue.fields != null) TextButton(enabled = !busy, onClick = { editing = issue },
                                    modifier = Modifier.paneOrigin(origins, "issue:${issue.position}").testTag("correctIssue-$index")) { Text(stringResource(R.string.courses_correct)) }
                                if (decision == null || decision is IssueDecision.Correct) TextButton(enabled = !busy,
                                    onClick = { decisions[issue.position] = IssueDecision.Exclude; message = null }, modifier = Modifier.testTag("excludeIssue-$index")) { Text(stringResource(R.string.courses_exclude)) }
                                if (decision != null) TextButton(enabled = !busy, onClick = { decisions.remove(issue.position); message = null }) { Text(stringResource(R.string.courses_undo)) }
                            }
                        }
                    }
                }
                item {
                    ActionPill(stringResource(R.string.courses_preview_import), { preview("diff-bottom") },
                        Modifier.paneOrigin(origins, "diff-bottom").testTag("previewCourseImport"), enabled = canPreview, prominent = true)
                }
            }
            }
            is ImportStep.Fix -> Correction(shown.issue, shown.fields, shown.term, top, onClose = { editing = null }, onSave = {
                decisions[shown.issue.position] = IssueDecision.Correct(it); editing = null; message = null
            })
            is ImportStep.Diff -> {
                val ready = shown.ready
                ReviewedImport(model, shown.draft, ready, busy || writing, top, onImported, onEdit = ::leavePreview, onConfirm = {
                    if (!busy && attached && proposal?.proposalId == ready.proposalId && submission.submit { model.confirmProposal(ready.proposalId) }) {
                        confirmedTerm = shown.draft.term.id
                        message = null
                    }
                })
            }
        }
        }
    }
    // Never register the school WebView as an optical source, even for a single transition frame.
    CompositionLocalProvider(LocalPlannerGlassAllowed provides (LocalPlannerGlassAllowed.current && !hub)) {
    PlannerPopupHost(enabled = !hub) {
        Page {
            RootPane(header = {
                if (!hub) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(stringResource(R.string.courses_import))
                        Spacer(Modifier.weight(1f))
                        ActionPill(stringResource(R.string.courses_cancel_import), ::cancel, Modifier.testTag("cancelCourseImport"), enabled = !writing)
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("courseImportError")) }
                    if (writing) Text(stringResource(R.string.courses_saving_import), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }) { top, _ -> steps(top) }
        }
    }
    }
}

@Composable
private fun Correction(issue: ImportIssue, source: CourseFields, term: TermSpec, top: Dp, onClose: () -> Unit, onSave: (CourseFields) -> Unit) =
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
    var fields by remember(issue.position) { mutableStateOf(source) }
    val problem = remember(fields, term) { fields.problem(term) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(dockListPadding(top, 0.dp)),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle(stringResource(R.string.courses_correct_entry))
            Text(issue.label)
            issue.fields?.let { raw ->
                Text(stringResource(R.string.courses_raw_entry, raw.week.ifEmpty { "?" }, raw.title.ifEmpty { stringResource(R.string.courses_no_name) },
                    raw.period, raw.room.ifEmpty { stringResource(R.string.courses_no_location) }), Modifier.testTag("fixOriginal"))
            }
            NumberWheelField(stringResource(R.string.courses_week_number), fields.weekNumber(term), 1..term.weeks, true, Modifier.testTag("fixWeek")) {
                fields = fields.copy(week = it.toString())
            }
            ImportField(stringResource(R.string.courses_name), fields.title, "fixTitle") { fields = fields.copy(title = it) }
            ImportField(stringResource(R.string.courses_location), fields.room, "fixRoom") { fields = fields.copy(room = it) }
            PeriodWheelField(fields.periodRange(), Modifier.testTag("fixPeriod")) {
                fields = fields.copy(period = if (it.first == it.last) it.first.toString() else "${it.first}-${it.last}")
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Actions {
                Secondary(stringResource(R.string.courses_cancel), onClick = onClose)
                Primary(stringResource(R.string.courses_save_correction), enabled = problem == null) { onSave(fields) }
            }
    }
}

