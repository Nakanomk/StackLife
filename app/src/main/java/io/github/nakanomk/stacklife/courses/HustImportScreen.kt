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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
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
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.ReadState
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
    val context = LocalContext.current
    val resolver = context.contentResolver
    val state = viewModel<ImportReviewState> {
        ImportReviewState(createSavedStateHandle(), File(context.applicationContext.noBackupFilesDir, "import-reviews"))
    }
    val hub = state.page == ImportPage.HUB
    val origins = remember { PaneOrigins() }
    val decisions = state.decisions
    var proposal by remember { mutableStateOf<PreviewResult.Ready?>(null) }
    var effective by remember { mutableStateOf<ReviewedTimetable?>(null) }
    var previewOrigin by remember { mutableStateOf("diff-top") }
    var confirmedTerm by rememberSaveable { mutableStateOf<String?>(null) }
    fun finish(termId: String) { state.reset(); onImported(termId) }
    val submission = rememberSubmission(model, onError = { state.message = it }) { outcome ->
        val term = confirmedTerm
        confirmedTerm = null
        proposal = null
        effective = null
        if (outcome == SaveOutcome.APPLIED) finish(checkNotNull(term)) else state.page = ImportPage.ENTRIES
    }
    val writing = submission.busy
    val busy = state.busy || (state.page == ImportPage.DIFF && proposal == null && !writing)
    var attached by remember { mutableStateOf(true) }
    DisposableEffect(model) {
        attached = true
        onDispose {
            attached = false
            if (!submission.busy) proposal?.let { model.discardProposal(it.proposalId) }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.receive(isHub = false) {
            resolver.openInputStream(uri)?.use(::readWeekFile) ?: throw SourceProblem(uiText("Cannot open the selected file.", "无法打开所选文件"))
        }
    }
    fun chooseFile() {
        state.message = null
        try { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
        catch (_: ActivityNotFoundException) { state.message = uiText("No system file picker is available on this device.", "设备没有可用的系统文件选择器") }
    }
    fun openChrome() {
        state.message = null
        val chrome = CustomTabsClient.getPackageName(context, chromePackages, true)
        if (chrome == null) {
            state.message = uiText("No supported Chrome browser was found.", "未找到可用的 Chrome 浏览器")
            return
        }
        val tab = CustomTabsIntent.Builder().build()
        tab.intent.setPackage(chrome)
        try { tab.launchUrl(context, Uri.parse(HUB_LOGIN_URL)) }
        catch (_: ActivityNotFoundException) { state.message = uiText("Cannot open Chrome.", "无法打开 Chrome 浏览器") }
        catch (_: SecurityException) { state.message = uiText("The system did not allow Chrome to open.", "系统不允许启动 Chrome 浏览器") }
    }
    fun leavePreview() {
        if (submission.busy) return
        proposal?.let { model.discardProposal(it.proposalId) }
        proposal = null
        effective = null
        state.page = ImportPage.ENTRIES
        state.message = null
    }
    fun cancel() {
        if (submission.busy || state.busy || !attached) return
        attached = false
        leavePreview()
        state.reset()
        onBack()
    }
    val raw = state.rows
    val parsed = state.draft
    val ready = proposal
    val calendar = remember(raw, state.fromHub) { raw?.let { ImportStep.Calendar(it, state.fromHub) } }
    val entries = remember(parsed) { parsed?.let { ImportStep.Entries(it) } }
    val issue = remember(parsed, state.editing) { parsed?.issues?.firstOrNull { it.position == state.editing } }
    val fix = remember(issue, parsed) {
        if (issue == null || parsed == null) null else issue.fields?.let { ImportStep.Fix(issue, it, parsed.term) }
    }
    val reviewed = effective
    val diff = remember(ready, reviewed, previewOrigin) {
        if (ready == null || reviewed == null) null else ImportStep.Diff(reviewed, ready, previewOrigin)
    }
    // A restored difference is rebuilt against today's revision; restoring never executes a command.
    LaunchedEffect(state.page, parsed) {
        if (state.page != ImportPage.DIFF || parsed == null || proposal != null || submission.busy) return@LaunchedEffect
        try {
            val choices = decisions.toMap()
            val result = withContext(Dispatchers.Default) { parsed.resolve(choices) }
            val owner = currentCoroutineContext()
            withContext(NonCancellable) {
                when (val preview = model.previewImport(result.term, result.meetings, result.source)) {
                    is PreviewResult.Ready -> if (owner.isActive && attached && state.page == ImportPage.DIFF) {
                        effective = result; proposal = preview
                    } else model.discardProposal(preview.proposalId)
                    is PreviewResult.Rejected -> if (owner.isActive && attached) { state.message = preview.message; state.page = ImportPage.ENTRIES }
                }
            }
        } catch (e: IllegalArgumentException) { state.message = e.message; state.page = ImportPage.ENTRIES }
    }
    val step = when (state.page) {
        ImportPage.SOURCE -> ImportStep.Source
        ImportPage.HUB -> ImportStep.Hub
        ImportPage.CALENDAR -> calendar
        ImportPage.ENTRIES -> entries
        ImportPage.FIX -> fix
        ImportPage.DIFF -> diff ?: entries
    } ?: ImportStep.Source
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
            is ImportStep.Fix -> { state.editing = null; state.working = null; state.page = ImportPage.ENTRIES }
            is ImportStep.Entries -> { state.page = ImportPage.CALENDAR; state.message = null }
            is ImportStep.Calendar -> state.reset()
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
            ImportStep.Hub -> HubWebView(onRows = { rows -> state.page = ImportPage.SOURCE; state.receive(true) { rows } }, onBack = { if (!state.busy) state.page = ImportPage.SOURCE },
                onFile = { state.page = ImportPage.SOURCE; chooseFile() }, extractOrigin = Modifier.paneOrigin(origins, "hub-calendar"))
            ImportStep.Source -> if (!writing && !busy) {
                ImportPane(composer = { if (!busy && !hub) AssistantComposer(AssistantPage.Terms) }) { bottom ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(dockListPadding(top, bottom))) {
                        SectionTitle(stringResource(R.string.courses_choose_source))
                        FormGroup {
                            PreferenceRow(stringResource(R.string.courses_hub_extract), modifier = Modifier.paneOrigin(origins, "hub")
                                .paneOrigin(origins, "hub-calendar").testTag("importCourseHub"), enabled = !busy) { state.message = null; state.page = ImportPage.HUB }
                            HorizontalDivider()
                            PreferenceRow(stringResource(R.string.courses_browse_chrome), modifier = Modifier.testTag("browseHubChrome"), enabled = !busy, onClick = ::openChrome)
                            HorizontalDivider()
                            PreferenceRow(stringResource(R.string.courses_choose_json), modifier = Modifier.paneOrigin(origins, "file-calendar").testTag("importCourseFile"), enabled = !busy, onClick = ::chooseFile)
                        }
                    }
                }
            } else Unit
            is ImportStep.Calendar -> Column(Modifier.fillMaxSize().verticalScroll(calendarScroll).padding(dockListPadding(top, 0.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.import_choose_timetable))
                val terms by remember(model) { model.terms() }.collectAsState(ReadState.Loading)
                ReadPanel(terms) { result ->
                    val existing = result.terms.firstOrNull { it.id == state.selectedId }
                    val useNew = state.selectedId == state.form.id || (result.terms.isEmpty() && state.selectedId == null)
                    FormGroup(Modifier.selectableGroup()) {
                        result.terms.forEach { spec ->
                            val sources = result.importedSources[spec.id].orEmpty()
                            val sourceNames = if (sources.isEmpty()) sourceLabel(MANUAL_SOURCE) else
                                sources.map { sourceLabel(it) }.joinToString(stringResource(R.string.courses_list_separator))
                            TermChoice(spec.dateRangeLabel(), existing == spec, !busy, "importTerm:${spec.id}", sourceNames) { state.selectedId = spec.id }
                        }
                        TermChoice(stringResource(R.string.courses_new_term), useNew, !busy, "importTerm:new") { state.selectedId = state.form.id }
                    }
                    if (useNew) {
                        val hints = remember(shown.rows) { firstWeekHints(shown.rows) }
                        if (hints.isNotEmpty()) Hint(stringResource(R.string.courses_week_hint, hints.joinToString(stringResource(R.string.courses_list_separator))))
                        TermFields(state.form, !busy) { state.form = it }
                    }
                    val selected = existing?.asForm() ?: state.form.takeIf { useNew }
                    ActionPill(stringResource(R.string.courses_review_entries), enabled = !busy && selected != null, prominent = true,
                        modifier = Modifier.paneOrigin(origins, "entries").testTag("reviewCourseRows"), onClick = { selected?.let(state::review) })
                }
                Secondary(stringResource(R.string.courses_choose_new_source), !busy) { state.reset() }
            }
            is ImportStep.Entries -> {
                val parsed = shown.draft
                val canPreview = !busy && !writing && proposal == null && decisions.size == parsed.issues.size
                fun preview(origin: String) {
                    if (!canPreview || !attached || state.page != ImportPage.ENTRIES) return
                    previewOrigin = origin
                    state.message = null; state.page = ImportPage.DIFF
                }
                val groups = remember(parsed) { parsed.groups() }
                var showSource by remember { mutableStateOf(false) }
                LazyColumn(Modifier.fillMaxSize().testTag("courseIssues"), state = entriesScroll, contentPadding = dockListPadding(top, 0.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(stringResource(R.string.courses_review_source), style = MaterialTheme.typography.titleLarge)
                    Text(parsed.term.dateRangeLabel(), style = MaterialTheme.typography.bodyLarge)
                    Hint(stringResource(R.string.import_valid_count, parsed.validCount))
                    if (parsed.issues.isNotEmpty()) {
                        Hint(stringResource(R.string.import_unresolved, parsed.issues.size - decisions.size))
                        Hint(stringResource(R.string.import_corrected_count, decisions.values.count { it is IssueDecision.Correct }))
                        Hint(stringResource(R.string.import_excluded_count, decisions.values.count { it == IssueDecision.Exclude }))
                    }
                    ActionPill(stringResource(R.string.courses_preview_import), { preview("diff-top") },
                        Modifier.paneOrigin(origins, "diff-top").testTag("previewCourseImportTop"), enabled = canPreview, prominent = true)
                    Secondary(stringResource(R.string.import_choose_timetable), !busy) { state.page = ImportPage.CALENDAR; state.message = null }
                }
                if (parsed.issues.isNotEmpty()) item { SectionTitle(stringResource(R.string.import_flagged)) }
                itemsIndexed(parsed.issues, key = { _, issue -> issue.position }) { index, issue ->
                    val decision = decisions[issue.position]
                    FormGroup {
                        IssueLocation(issue)
                        Text(issue.reason, style = MaterialTheme.typography.bodyMedium)
                        if (decision == IssueDecision.Exclude) Hint(stringResource(R.string.courses_excluded))
                        if (decision is IssueDecision.Correct) {
                            val f = decision.fields
                            Text(f.title, style = MaterialTheme.typography.bodyLarge)
                            if (f.room.isNotBlank()) Hint(stringResource(R.string.courses_room, f.room))
                            Hint(stringResource(R.string.courses_week, f.week.trim().toInt()))
                            f.periodRange()?.let { Hint(stringResource(R.string.import_period_range, it.first, it.last)) }
                        }
                        raw?.let { RawDisclosure(it, issue, "issueRaw-$index") }
                        Actions {
                            if (issue.fields != null) TextButton(enabled = !busy, onClick = {
                                state.editing = issue.position; state.working = (decision as? IssueDecision.Correct)?.fields ?: issue.fields
                                state.page = ImportPage.FIX
                            }, modifier = Modifier.paneOrigin(origins, "issue:${issue.position}").testTag("correctIssue-$index")) { Text(stringResource(R.string.courses_correct)) }
                            if (decision != IssueDecision.Exclude) TextButton(enabled = !busy,
                                onClick = { decisions[issue.position] = IssueDecision.Exclude; state.message = null }, modifier = Modifier.testTag("excludeIssue-$index")) { Text(stringResource(R.string.courses_exclude)) }
                            if (decision != null) TextButton(enabled = !busy, onClick = { decisions.remove(issue.position); state.message = null }) { Text(stringResource(R.string.courses_undo)) }
                        }
                    }
                }
                reviewGroups(groups)
                if (raw != null) {
                    item {
                        TextButton(onClick = { showSource = !showSource }, modifier = Modifier.testTag("allSourceRows")) {
                            Text(stringResource(if (showSource) R.string.import_hide_raw else R.string.import_show_raw))
                        }
                    }
                    if (showSource) items(raw.size) { index ->
                        FormGroup {
                            Text(stringResource(R.string.import_row, index + 1), style = MaterialTheme.typography.bodyLarge)
                            Text(remember(raw, index) { raw[index].toString() }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item {
                    ActionPill(stringResource(R.string.courses_preview_import), { preview("diff-bottom") },
                        Modifier.paneOrigin(origins, "diff-bottom").testTag("previewCourseImport"), enabled = canPreview, prominent = true)
                }
            }
            }
            is ImportStep.Fix -> Correction(shown.issue, state.working ?: shown.fields, shown.term, raw, top, onChange = { state.working = it },
                onClose = ::backStep, onSave = {
                    decisions[shown.issue.position] = IssueDecision.Correct(it); state.message = null; backStep()
                })
            is ImportStep.Diff -> {
                val ready = shown.ready
                ReviewedImport(model, shown.draft, ready, busy || writing, top, ::finish, onEdit = ::leavePreview, onConfirm = {
                    if (!busy && attached && proposal?.proposalId == ready.proposalId && submission.submit { model.confirmProposal(ready.proposalId) }) {
                        confirmedTerm = shown.draft.term.id
                        state.message = null
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
                        ActionPill(stringResource(R.string.courses_cancel_import), ::cancel, Modifier.testTag("cancelCourseImport"), enabled = !writing && !state.busy)
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error,
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
private fun Correction(issue: ImportIssue, fields: CourseFields, term: TermSpec, rows: JsonArray?, top: Dp,
    onChange: (CourseFields) -> Unit, onClose: () -> Unit, onSave: (CourseFields) -> Unit) {
    val problem = remember(fields, term) { fields.problem(term) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(dockListPadding(top, 0.dp)),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle(stringResource(R.string.courses_correct_entry))
            IssueLocation(issue)
            rows?.let { RawDisclosure(it, issue, "fixOriginal") }
            NumberWheelField(stringResource(R.string.courses_week_number), fields.weekNumber(term), 1..term.weeks, true, Modifier.testTag("fixWeek")) {
                onChange(fields.copy(week = it.toString()))
            }
            ImportField(stringResource(R.string.courses_name), fields.title, "fixTitle") { onChange(fields.copy(title = it)) }
            ImportField(stringResource(R.string.courses_location), fields.room, "fixRoom") { onChange(fields.copy(room = it)) }
            PeriodWheelField(fields.periodRange(), Modifier.testTag("fixPeriod")) {
                onChange(fields.copy(period = if (it.first == it.last) it.first.toString() else "${it.first}-${it.last}"))
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Actions {
                Secondary(stringResource(R.string.courses_cancel), onClick = onClose)
                Primary(stringResource(R.string.courses_save_correction), enabled = problem == null) { onSave(fields) }
            }
    }
}

@Composable
private fun TermChoice(label: String, selected: Boolean, enabled: Boolean, tag: String, source: String? = null, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose)
        .heightIn(min = 48.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            source?.let { Hint(it) }
        }
    }
}

@Composable
private fun IssueLocation(issue: ImportIssue) {
    Text(stringResource(R.string.import_row, issue.row), style = MaterialTheme.typography.bodyLarge)
    issue.day?.let { Hint(weekday(it)) }
    issue.item?.let { Hint(stringResource(R.string.import_item, it)) }
}

@Composable
private fun RawDisclosure(rows: JsonArray, issue: ImportIssue, tag: String) {
    var expanded by remember(issue.position) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("$tag-toggle")) {
        Text(stringResource(if (expanded) R.string.import_hide_raw else R.string.import_show_raw))
    }
    if (expanded) {
        issue.fields?.week?.let { Hint(stringResource(R.string.import_raw_week, it)) }
        Text(remember(rows, issue) { rows.sourceValue(issue).toString() }, Modifier.testTag(tag), style = MaterialTheme.typography.bodyMedium)
    }
}

