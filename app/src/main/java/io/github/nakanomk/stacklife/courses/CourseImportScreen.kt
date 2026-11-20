package io.github.nakanomk.stacklife.courses

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.assistant.AssistantComposer
import io.github.nakanomk.stacklife.workspace.PreferenceRow
import io.github.nakanomk.stacklife.workspace.lineFieldColors
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.assistant.LocalAssistant
import io.github.nakanomk.stacklife.planner.PreviewResult
import io.github.nakanomk.stacklife.workspace.SaveOutcome
import io.github.nakanomk.stacklife.workspace.rememberSubmission
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

private data class TermForm(val id: String = "", val monday: String = "", val weeks: String = "",
    val initial: BellSchedule = BellSchedule.SUMMER, val switchDate: String = "") {
    fun term(): TermSpec {
        fun date(text: String): LocalDate = try { LocalDate.parse(text.trim()) } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("日期须为有效的 YYYY-MM-DD")
        }
        val term = TermSpec(id, date(monday), weeks.trim().toIntOrNull() ?: throw IllegalArgumentException("请填写学期总周数"),
            initial, switchDate.takeIf { it.isNotBlank() }?.let(::date))
        term.problem()?.let { throw IllegalArgumentException(it) }
        return term
    }
}

@Composable
internal fun CourseImportScreen(model: WorkspaceViewModel, onBack: () -> Unit, onImported: (String) -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        CourseImportContent(model, onBack, onImported)
    }
}

@Composable
private fun CourseImportContent(model: WorkspaceViewModel, onBack: () -> Unit, onImported: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resolver = context.contentResolver
    var rows by remember { mutableStateOf<JsonArray?>(null) }
    var hub by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(TermForm()) }
    var hints by remember { mutableStateOf(emptyList<String>()) }
    var draft by remember { mutableStateOf<ImportDraft?>(null) }
    val decisions = remember { mutableStateMapOf<String, IssueDecision>() }
    var editing by remember { mutableStateOf<ImportIssue?>(null) }
    var effective by remember { mutableStateOf(emptyList<CourseMeeting>()) }
    var proposal by remember { mutableStateOf<PreviewResult.Ready?>(null) }
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
        hub = false
        if (data.isEmpty()) { message = "原始数组为空，已有课表未更改"; return }
        hints = firstWeekHints(data)
        rows = data
        form = TermForm(monday = hints.singleOrNull().orEmpty())
        message = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val data = withContext(Dispatchers.IO) {
                        resolver.openInputStream(uri)?.use(::readWeekFile) ?: throw SourceProblem("无法打开所选文件")
                    }
                    receive(data)
                } catch (e: SourceProblem) { message = e.message }
                catch (_: SecurityException) { message = "没有读取权限，请重新选择原始文件" }
                catch (_: IOException) { message = "无法读取 UTF-8 文件，请重新选择原始 JSON" }
                finally { busy = false }
            }
        }
    }
    fun chooseFile() {
        message = null
        try { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
        catch (_: ActivityNotFoundException) { message = "设备没有可用的系统文件选择器" }
    }
    fun openChrome() {
        message = null
        val chrome = CustomTabsClient.getPackageName(context, chromePackages, true)
        if (chrome == null) {
            message = "未找到可用的 Chrome 浏览器"
            return
        }
        val tab = CustomTabsIntent.Builder().build()
        tab.intent.setPackage(chrome)
        try { tab.launchUrl(context, Uri.parse(HUB_LOGIN_URL)) }
        catch (_: ActivityNotFoundException) { message = "无法打开 Chrome 浏览器" }
        catch (_: SecurityException) { message = "系统不允许启动 Chrome 浏览器" }
    }
    if (hub) {
        HubWebView(onRows = ::receive, onBack = { hub = false }, onFile = { hub = false; chooseFile() })
        return
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
    BackHandler { cancel() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("导入课表", style = MaterialTheme.typography.titleLarge)
            TextButton(enabled = !writing, onClick = ::cancel, modifier = Modifier.testTag("cancelCourseImport")) { Text("取消导入") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("courseImportError")) }
        if (writing) Text("正在保存已确认的课表…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val raw = rows
        val parsed = draft
        val ready = proposal
        when {
            writing && raw == null -> Unit
            raw == null -> {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    PreferenceRow("从 HUB 网页提取课表", modifier = Modifier.testTag("importCourseHub"), enabled = !busy) { message = null; hub = true }
                    PreferenceRow("用 Chrome 浏览 HUB", modifier = Modifier.testTag("browseHubChrome"), enabled = !busy, onClick = ::openChrome)
                    PreferenceRow("选择原始 JSON 文件", modifier = Modifier.testTag("importCourseFile"), enabled = !busy, onClick = ::chooseFile)
                }
                if (!busy) AssistantComposer(AssistantPage.Terms)
            }
            parsed == null -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("确认学期与校区作息", style = MaterialTheme.typography.titleLarge)
                Text("时间按 Asia/Shanghai。首周日期只作候选，请自行核实校历。")
                if (hints.isNotEmpty()) Text("第一周 KS 候选：${hints.joinToString("、")}")
                ImportField("学期标识", form.id, "termId", !busy) { form = form.copy(id = it) }
                ImportField("首周周一 YYYY-MM-DD", form.monday, "termMonday", !busy) { form = form.copy(monday = it) }
                ImportField("总周数", form.weeks, "termWeeks", !busy) { form = form.copy(weeks = it) }
                Text("首周作息")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BellSchedule.entries.forEach { bell -> FilterChip(selected = form.initial == bell, enabled = !busy,
                        onClick = { form = form.copy(initial = bell) }, label = { Text(if (bell == BellSchedule.SUMMER) "夏令" else "冬令") }) }
                }
                ImportField("切换为另一作息的生效日（选填）", form.switchDate, "termSwitchDate", !busy) { form = form.copy(switchDate = it) }
                Text("填写后从该日开始切换；留空则整个学期不变。")
                Button(enabled = !busy, modifier = Modifier.testTag("reviewCourseRows"), onClick = {
                    if (busy) return@Button
                    busy = true; message = null
                    scope.launch {
                        try {
                            val term = form.term()
                            draft = withContext(Dispatchers.Default) { HustCourseImporter.parse(raw, term) }
                            decisions.clear()
                        } catch (e: IllegalArgumentException) { message = e.message }
                        finally { busy = false }
                    }
                }) { Text("确认学期并核对条目") }
                TextButton(enabled = !busy, onClick = { rows = null; hints = emptyList(); message = null }) { Text("重选来源") }
            }
            ready == null -> LazyColumn(Modifier.testTag("courseIssues"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("核对原始条目", style = MaterialTheme.typography.titleLarge)
                    Text("${parsed.term.id} · 去重后有效 ${parsed.validCount} 次 · 待修正 ${parsed.issues.size - decisions.size} 项")
                    Text("已修正 ${decisions.values.count { it is IssueDecision.Correct }} 项 · 已排除 ${decisions.values.count { it == IssueDecision.Exclude }} 项")
                    TextButton(enabled = !busy, onClick = { draft = null; decisions.clear(); message = null }) { Text("修改学期设置") }
                }
                itemsIndexed(parsed.issues, key = { _, issue -> issue.position }) { index, issue ->
                    val decision = decisions[issue.position]
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(issue.position, style = MaterialTheme.typography.titleSmall)
                            Text(issue.reason)
                            issue.fields?.let { Text("原始：第 ${it.week.ifEmpty { "?" }} 周 · ${it.title.ifEmpty { "无课名" }} · JC「${it.period}」 · ${it.room.ifEmpty { "未提供地点" }}") }
                            when (decision) {
                                null -> Unit
                                IssueDecision.Exclude -> Text("已明确排除此项")
                                is IssueDecision.Correct -> Text("已修正：第 ${decision.fields.week} 周 · ${decision.fields.title} · ${decision.fields.period} 节 · ${decision.fields.room.ifEmpty { "未提供地点" }}")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (issue.fields != null) TextButton(enabled = !busy, onClick = { editing = issue }, modifier = Modifier.testTag("correctIssue-$index")) { Text("修正") }
                                if (decision == null || decision is IssueDecision.Correct) TextButton(enabled = !busy,
                                    onClick = { decisions[issue.position] = IssueDecision.Exclude; message = null }, modifier = Modifier.testTag("excludeIssue-$index")) { Text("排除此项") }
                                if (decision != null) TextButton(enabled = !busy, onClick = { decisions.remove(issue.position); message = null }) { Text("撤销处理") }
                            }
                        }
                    }
                }
                item {
                    Button(enabled = !busy && decisions.size == parsed.issues.size, modifier = Modifier.testTag("previewCourseImport"), onClick = {
                        if (busy) return@Button
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
                    }) { Text("预览导入差异") }
                }
            }
            else -> {
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
                ImportDiff(parsed, effective, ready, busy || writing, ask = {
                    val id = staged ?: model.planner.stageCourseImport(parsed.term, effective).also { staged = it }
                    AssistantPage.CourseDraft(id, parsed.term.id)
                }, onEdit = ::leavePreview, onConfirm = {
                    if (!busy && submission.submit { model.confirmProposal(ready.proposalId) }) {
                        confirmedTerm = parsed.term.id
                        message = null
                    }
                })
            }
        }
    }
    editing?.let { issue ->
        val parsed = draft ?: return@let
        val fields = (decisions[issue.position] as? IssueDecision.Correct)?.fields ?: issue.fields ?: return@let
        Correction(issue.position, fields, parsed.term, onClose = { editing = null }, onSave = {
            decisions[issue.position] = IssueDecision.Correct(it); editing = null; message = null
        })
    }
}

@Composable
private fun ImportField(label: String, value: String, tag: String, enabled: Boolean = true, change: (String) -> Unit) {
    // Don't let framework text-editing state save even a course name to an Activity Bundle.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        TextField(value = value, onValueChange = change, enabled = enabled, colors = lineFieldColors(), label = { Text(label) },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag(tag))
    }
}

@Composable
private fun Correction(position: String, source: CourseFields, term: TermSpec, onClose: () -> Unit, onSave: (CourseFields) -> Unit) {
    var fields by remember(position) { mutableStateOf(source) }
    AlertDialog(onDismissRequest = onClose, title = { Text("修正课程项") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(position)
            ImportField("周次", fields.week, "fixWeek") { fields = fields.copy(week = it) }
            ImportField("课名", fields.title, "fixTitle") { fields = fields.copy(title = it) }
            ImportField("地点（可空）", fields.room, "fixRoom") { fields = fields.copy(room = it) }
            ImportField("节次", fields.period, "fixPeriod") { fields = fields.copy(period = it) }
            fields.problem(term)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = fields.problem(term) == null, onClick = { onSave(fields) }) { Text("保存修正") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

private data class CourseGroup(val title: String, val room: String, val day: Int, val first: Int, val last: Int, val start: String, val end: String)

@Composable
private fun ImportDiff(draft: ImportDraft, meetings: List<CourseMeeting>, proposal: PreviewResult.Ready, busy: Boolean,
    ask: () -> AssistantPage.CourseDraft, onEdit: () -> Unit, onConfirm: () -> Unit) {
    val groups = remember(meetings) { meetings.groupBy {
        fun time(millis: Long) = Instant.ofEpochMilli(millis).atZone(campusZone).toLocalTime().toString()
        CourseGroup(it.title, it.room, it.day, it.firstPeriod, it.lastPeriod, time(it.startMillis), time(it.endMillis))
    }.entries.toList() }
    val locations = remember(groups) { groups.groupBy { it.key.title }.mapValues { (_, entries) -> entries.map { it.key.room }.distinct() }.filterValues { it.size > 1 } }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).testTag("courseImportDiff"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("确认导入差异", style = MaterialTheme.typography.titleLarge)
                Text("只替换 ${draft.term.id} 的 HUB 课表；已有任务保留。最终 ${meetings.size} 次课程，${groups.size} 个时段分组。")
                Text("首周 ${draft.term.firstMonday} · ${draft.term.weeks} 周 · ${if (draft.term.initial == BellSchedule.SUMMER) "夏令" else "冬令"}起始" +
                    (draft.term.switchDate?.let { " · $it 起切换" } ?: " · 全期不切换"))
                if (locations.isNotEmpty()) Text("同名不同地点", style = MaterialTheme.typography.titleMedium)
            }
            items(locations.entries.toList()) { (title, rooms) -> Text("$title：${rooms.joinToString("、") { it.ifEmpty { "未提供地点" } }}") }
            items(groups) { (g, classes) ->
                Text("${g.title} · ${g.room.ifEmpty { "未提供地点" }}\n第 ${classes.joinToString("、") { it.week.toString() }} 周 · ${weekday(g.day)} · ${g.first}–${g.last} 节 · ${g.start}–${g.end}")
            }
            item { Text("数据库差异与冲突", style = MaterialTheme.typography.titleMedium)
                if (proposal.changes.isEmpty()) Text("与当前课表一致，无新增或移除。") }
            items(proposal.changes) { change -> Text("${change.field}：${change.before ?: "无"} → ${change.after ?: "无"}") }
            item {
                TextButton(enabled = !busy, onClick = onEdit) { Text("返回核对") }
                Button(enabled = !busy, onClick = onConfirm, modifier = Modifier.testTag("confirmCourseImport")) { Text("确认导入") }
            }
        }
        if (!busy) AssistantComposer(ask)
    }
}
