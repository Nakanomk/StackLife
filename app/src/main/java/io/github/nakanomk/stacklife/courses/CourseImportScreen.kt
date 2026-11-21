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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
import io.github.nakanomk.stacklife.assistant.LocalAssistant
import io.github.nakanomk.stacklife.planner.PreviewResult
import io.github.nakanomk.stacklife.transitions.PaneMotion
import io.github.nakanomk.stacklife.transitions.PaneOrigins
import io.github.nakanomk.stacklife.transitions.paneOrigin
import java.io.IOException
import java.time.Instant
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

internal data class TermForm(val id: String = "", val monday: LocalDate? = null, val weeks: Int? = null,
    val initial: BellSchedule = BellSchedule.SUMMER, val switchDate: LocalDate? = null) {
    fun term(): TermSpec {
        val start = monday ?: throw IllegalArgumentException(uiText("Choose the first Monday of the term.", "请选择学期首周周一。"))
        val term = TermSpec(id, start, weeks ?: throw IllegalArgumentException(uiText("Choose the number of weeks in the term.", "请选择学期总周数。")),
            initial, switchDate)
        term.problem()?.let { throw IllegalArgumentException(it) }
        return term
    }
}

private sealed class ImportStep(val key: String, val depth: Int) {
    data object Source : ImportStep("source", 0)
    data object Hub : ImportStep("hub", 1)
    class Calendar(val rows: JsonArray, fromHub: Boolean) : ImportStep(if (fromHub) "hub-calendar" else "file-calendar", 2)
    class Entries(val draft: ImportDraft) : ImportStep("entries", 3)
    class Fix(val issue: ImportIssue, val fields: CourseFields, val term: TermSpec) : ImportStep("issue:${issue.position}", 4)
    class Diff(val draft: ImportDraft, val meetings: List<CourseMeeting>, val ready: PreviewResult.Ready, origin: String) : ImportStep(origin, 4)
}

@Composable
internal fun CourseImportScreen(model: WorkspaceViewModel, onBack: () -> Unit, onImported: (String) -> Unit) {
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
    var effective by remember { mutableStateOf(emptyList<CourseMeeting>()) }
    var proposal by remember { mutableStateOf<PreviewResult.Ready?>(null) }
    var previewOrigin by remember { mutableStateOf("diff-top") }
    var confirmedTerm by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val submission = rememberSubmission(model, onError = { message = it }) { outcome ->
        val term = confirmedTerm
        confirmedTerm = null
        proposal = null
        effective = emptyList()
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
        effective = emptyList()
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
    val diff = remember(ready, parsed, effective, previewOrigin) {
        if (ready == null || parsed == null) null else ImportStep.Diff(parsed, effective, ready, previewOrigin)
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
                            val meetings = withContext(Dispatchers.Default) { parsed.resolve(choices) }
                            val owner = currentCoroutineContext()
                            withContext(NonCancellable) {
                                when (val preview = model.previewImport(parsed.term, meetings)) {
                                    is PreviewResult.Ready -> if (owner.isActive && attached) {
                                        effective = meetings; proposal = preview
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
                val parsed = shown.draft
                val ready = shown.ready
                val assistant = LocalAssistant.current
                var staged by remember(ready.proposalId) { mutableStateOf<String?>(null) }
                if (assistant != null) {
                    DisposableEffect(ready.proposalId) {
                        onDispose {
                            staged?.let { id ->
                                model.planner.discardCourseImport(id)
                                if ((assistant.state.value.page as? AssistantPage.CourseDraft)?.draftId == id) assistant.close()
                            }
                        }
                    }
                    val chat by assistant.state.collectAsState()
                    var imported by remember { mutableStateOf(false) }
                    LaunchedEffect(chat.importedDraft, chat.page) {
                        if (staged != null && chat.importedDraft == staged) imported = true
                        if (imported && chat.page == null) onImported(parsed.term.id)
                    }
                }
                ImportDiff(parsed, shown.meetings, ready, busy || writing, top, ask = {
                    val id = staged ?: model.planner.stageCourseImport(parsed.term, shown.meetings).also { staged = it }
                    AssistantPage.CourseDraft(id, parsed.term.id)
                }, onEdit = ::leavePreview, onConfirm = {
                    if (!busy && attached && proposal?.proposalId == ready.proposalId && submission.submit { model.confirmProposal(ready.proposalId) }) {
                        confirmedTerm = parsed.term.id
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
internal fun TermFields(form: TermForm, enabled: Boolean, frosted: Boolean = false, change: (TermForm) -> Unit) =
    // Raw HUB dates and unconfirmed calendar choices stay in the editor, not an Activity Bundle.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
    val campusToday = remember { LocalDate.now(campusZone) }
    val thisMonday = remember(campusToday) { campusToday.minusDays((campusToday.dayOfWeek.value - 1).toLong()) }
    FormGroup(frosted = frosted) {
        ImportField(stringResource(R.string.courses_term_id), form.id, "termId", enabled) { change(form.copy(id = it)) }
        DateWheelField(stringResource(R.string.courses_first_monday), form.monday, thisMonday, enabled, Modifier.testTag("termMonday")) {
            change(form.copy(monday = it))
        }
        NumberWheelField(stringResource(R.string.courses_week_count), form.weeks, 1..53, enabled, Modifier.testTag("termWeeks"),
            initial = 16) { change(form.copy(weeks = it)) }
    }
    Text(stringResource(R.string.courses_initial_bell))
    val initial = rememberUpdatedState(form.initial.ordinal.toFloat())
    ControlGroup(frosted = frosted) {
        SegmentTrack(initial, BellSchedule.entries.size, MaterialTheme.colorScheme.secondaryContainer,
            { change(form.copy(initial = BellSchedule.entries[it])) }, enabled = enabled) { highlighted ->
            Row(Modifier.selectableGroup()) {
                BellSchedule.entries.forEach { bell ->
                    Box(Modifier.clip(CircleShape).selectable(form.initial == bell, enabled = enabled, role = Role.RadioButton) {
                        change(form.copy(initial = bell))
                    }.heightIn(min = 48.dp).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center) { Text(if (bell == BellSchedule.SUMMER) stringResource(R.string.courses_summer) else stringResource(R.string.courses_winter),
                            color = if (enabled && highlighted == bell.ordinal) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                }
            }
        }
    }
    val switchFields: @Composable () -> Unit = {
        DateWheelField(stringResource(R.string.courses_change_date), form.switchDate, form.monday ?: campusToday, enabled,
            Modifier.testTag("termSwitchDate"), onClear = { change(form.copy(switchDate = null)) }) { change(form.copy(switchDate = it)) }
        Text(stringResource(R.string.courses_change_date_hint))
    }
    if (frosted) FormGroup(frosted = true) { switchFields() } else switchFields()
}

@Composable
internal fun ImportField(label: String, value: String, tag: String, enabled: Boolean = true, change: (String) -> Unit) {
    // Don't let framework text-editing state save even a course name to an Activity Bundle.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        TextField(value = value, onValueChange = change, enabled = enabled, colors = lineFieldColors(), label = { Text(label) },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag(tag))
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

private data class CourseGroup(val title: String, val room: String, val day: Int, val first: Int, val last: Int, val start: String, val end: String)

@Composable
private fun ImportDiff(draft: ImportDraft, meetings: List<CourseMeeting>, proposal: PreviewResult.Ready, busy: Boolean, top: Dp,
    ask: () -> AssistantPage.CourseDraft, onEdit: () -> Unit, onConfirm: () -> Unit) {
    val groups = remember(meetings) { meetings.groupBy {
        fun time(millis: Long) = Instant.ofEpochMilli(millis).atZone(campusZone).toLocalTime().toString()
        CourseGroup(it.title, it.room, it.day, it.firstPeriod, it.lastPeriod, time(it.startMillis), time(it.endMillis))
    }.entries.toList() }
    val locations = remember(groups) { groups.groupBy { it.key.title }.mapValues { (_, entries) -> entries.map { it.key.room }.distinct() }.filterValues { it.size > 1 } }
    ImportPane(composer = { if (!busy) AssistantComposer(ask) }) { bottom ->
        LazyColumn(Modifier.fillMaxSize().testTag("courseImportDiff"), contentPadding = dockListPadding(top, bottom),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.courses_confirm_import_diff), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.courses_import_summary, draft.term.id, meetings.size, groups.size))
                ActionPill(stringResource(R.string.courses_confirm_import), onConfirm, Modifier.testTag("confirmCourseImportTop"), enabled = !busy, prominent = true)
                Text(stringResource(R.string.courses_calendar_summary, draft.term.firstMonday, draft.term.weeks, stringResource(if (draft.term.initial == BellSchedule.SUMMER) R.string.courses_summer else R.string.courses_winter)) +
                    (draft.term.switchDate?.let { stringResource(R.string.courses_switch_on, it) } ?: stringResource(R.string.courses_no_switch)))
                if (locations.isNotEmpty()) Text(stringResource(R.string.courses_different_locations), style = MaterialTheme.typography.titleMedium)
            }
            items(locations.entries.toList()) { (title, rooms) ->
                val noLocation = stringResource(R.string.courses_no_location)
                Text(stringResource(R.string.courses_room_list, title, rooms.joinToString(stringResource(R.string.courses_list_separator)) { it.ifEmpty { noLocation } }))
            }
            items(groups) { (g, classes) ->
                Text(stringResource(R.string.courses_group_summary, g.title, g.room.ifEmpty { stringResource(R.string.courses_no_location) }, classes.joinToString(stringResource(R.string.courses_list_separator)) { it.week.toString() }, weekday(g.day), g.first, g.last, g.start, g.end))
            }
            item { Text(stringResource(R.string.courses_database_diff), style = MaterialTheme.typography.titleMedium)
                if (proposal.changes.isEmpty()) Text(stringResource(R.string.courses_unchanged)) }
            items(proposal.changes) { change -> Text(stringResource(R.string.courses_field_change, change.field, change.before ?: stringResource(R.string.courses_none), change.after ?: stringResource(R.string.courses_none))) }
            item {
                Actions {
                    Secondary(stringResource(R.string.courses_back_to_review), !busy, onEdit)
                    ActionPill(stringResource(R.string.courses_confirm_import), onConfirm, Modifier.testTag("confirmCourseImport"), enabled = !busy, prominent = true)
                }
            }
        }
    }
}

@Composable
private fun ImportPane(composer: @Composable () -> Unit, content: @Composable (bottom: Dp) -> Unit) {
    val density = LocalDensity.current
    var height by remember { mutableIntStateOf(0) }
    GlassBacking(Modifier.fillMaxSize(), background = { content(with(density) { height.toDp() }) }) {
        Box(Modifier.align(Alignment.BottomCenter).onSizeChanged { height = it.height }.padding(horizontal = 16.dp, vertical = 4.dp)) {
            composer()
        }
    }
}
