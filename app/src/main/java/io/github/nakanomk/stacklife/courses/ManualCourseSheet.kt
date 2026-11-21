package io.github.nakanomk.stacklife.courses

import android.database.sqlite.SQLiteException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.PlannerDropdownMenu
import io.github.nakanomk.stacklife.appearance.PlannerModalBottomSheet
import io.github.nakanomk.stacklife.appearance.CapsuleScene
import io.github.nakanomk.stacklife.appearance.LocalCapsuleBackdrop
import io.github.nakanomk.stacklife.appearance.PlannerPopupHost
import io.github.nakanomk.stacklife.appearance.StackLifeSurface
import io.github.nakanomk.stacklife.appearance.plannerBackdrop
import io.github.nakanomk.stacklife.appearance.rememberPlannerBackdrop
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Retain the unfinished editor over rotation, not in a Bundle, Room, or an assistant prompt. */
internal class ManualEditorModel : ViewModel() {
    var form by mutableStateOf<ManualCourseForm?>(null)
}

internal class ManualCourseForm(term: TermSpec?, val openedWeek: Int, val original: CoursePattern? = null) {
    var existing by mutableStateOf(term)
    var newTerm by mutableStateOf(TermForm(weeks = "16"))
    var pattern by mutableStateOf(original ?: CoursePattern("", "", 1, 1, 2))
    var weeks by mutableStateOf(setOf(openedWeek))
    var choosingWeeks by mutableStateOf<Set<Int>?>(null)
    val weekCount get() = existing?.weeks ?: newTerm.weeks.trim().toIntOrNull()?.takeIf { it in 1..53 }
    var before by mutableStateOf<ManualSeries?>(null)
    var reviewedTerm: TermSpec? = null
    var revealWeek = openedWeek
    var removing = false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualCourseSheet(model: WorkspaceViewModel, form: ManualCourseForm, terms: List<TermSpec>,
    close: () -> Unit, saved: (TermSpec, Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf<PreviewResult.Ready?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attached by remember { mutableStateOf(true) }
    val submission = rememberSubmission(model, onError = { error = it }) { outcome ->
        ready = null
        if (outcome == SaveOutcome.APPLIED) {
            if (form.removing) close() else form.reviewedTerm?.let { saved(it, form.revealWeek) }
        }
    }
    DisposableEffect(form) {
        attached = true
        onDispose {
            attached = false
            if (!submission.busy) ready?.let { model.discardProposal(it.proposalId) }
        }
    }
    LaunchedEffect(form) {
        val original = form.original ?: return@LaunchedEffect
        if (form.before != null) return@LaunchedEffect
        try {
            val read = model.planner.observe(PlannerQuery.ManualWeeks(checkNotNull(form.existing).id, original)).first() as ManualWeeksView
            if (form.openedWeek !in read.weeks) error = "所选课次已不存在，请关闭并重新选择。" else {
                form.before = ManualSeries(original, read.weeks)
                form.weeks = read.weeks
            }
        } catch (_: SQLiteException) { error = "无法读取课程，请关闭并重新打开。" }
    }
    fun preview(remove: Boolean) {
        if (loading || submission.busy || ready != null || (form.original != null && form.before == null)) return
        val term = try { form.existing ?: form.newTerm.term() } catch (e: IllegalArgumentException) { error = e.message; return }
        val p = form.pattern
        val after = if (remove) null else ManualSeries(p.copy(title = p.title.trim(), room = p.room.trim()), form.weeks)
        val command = PlannerCommand.ManualCourse(term.id, form.before, after, term.takeIf { form.existing == null })
        error = null; loading = true
        scope.launch {
            // Always dispose a proposal that completed after its sheet or configuration went away.
            withContext(NonCancellable) {
                val result = model.preview(command)
                if (attached) {
                    when (result) {
                        is PreviewResult.Rejected -> error = result.message
                        is PreviewResult.Ready -> if (result.changes.isEmpty()) {
                            model.discardProposal(result.proposalId); error = "没有需要保存的更改。"
                        } else {
                            form.removing = remove; form.reviewedTerm = term
                            form.revealWeek = if (form.openedWeek in form.weeks) form.openedWeek else form.weeks.minOrNull() ?: 1
                            ready = result
                        }
                    }
                    loading = false
                } else if (result is PreviewResult.Ready) model.discardProposal(result.proposalId)
            }
        }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !submission.busy })
    val enabled = !loading && !submission.busy && (form.original == null || form.before != null)
    val review = ready
    val weekChoice = form.choosingWeeks
    val scroll = rememberScrollState()
    LaunchedEffect(error) { if (error != null) scroll.scrollTo(0) }
    fun back() {
        if (submission.busy) return
        if (weekChoice != null) { form.choosingWeeks = null; return }
        if (review == null) close() else { model.discardProposal(review.proposalId); ready = null; error = null }
    }
    PlannerModalBottomSheet({ if (!submission.busy) close() }, sheet) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.9f).imePadding().testTag("manual-course-sheet")) {
            // Pickers sample this sheet, not the timetable in the window beneath it.
            PlannerPopupHost {
                val focus = LocalFocusManager.current
                val scrollingPlane = rememberPlannerBackdrop()
                Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0), topBar = {
                    CompositionLocalProvider(LocalCapsuleBackdrop provides scrollingPlane) {
                        StackLifeSurface(CapsuleScene.FROST, Modifier.fillMaxWidth().testTag("manual-toolbar"),
                            solid = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(0.dp)) {
                            CenterAlignedTopAppBar(
                                title = { Text(when { weekChoice != null -> "选择上课周"; review != null -> "核对课程变更"; form.original == null -> "添加课程"; else -> "编辑课程" },
                                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                navigationIcon = {
                                    SheetAction(if (review == null) "取消" else "返回", "manual-cancel", !submission.busy, ::back)
                                }, actions = {
                                    when {
                                        weekChoice != null -> SheetAction("确定", "manual-weeks-done", weekChoice.isNotEmpty()) {
                                            form.weeks = weekChoice; form.choosingWeeks = null
                                        }
                                        review == null -> SheetAction("预览", "manual-preview", enabled) { focus.clearFocus(); preview(false) }
                                        else -> SheetAction(if (form.removing) "确认移除" else "保存课程", "manual-save", !submission.busy) {
                                            submission.submit { model.confirmProposal(review.proposalId) }
                                        }
                                    }
                                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent,
                                    scrolledContainerColor = Color.Transparent), windowInsets = WindowInsets(0))
                        }
                    }
                }) { insets ->
                    val body = Modifier.fillMaxSize().plannerBackdrop(scrollingPlane)
                    val top = insets.calculateTopPadding()
                    if (weekChoice != null) WeekSelection(checkNotNull(form.weekCount), weekChoice, body, top) {
                        form.choosingWeeks = it
                    } else if (review == null) Column(body.verticalScroll(scroll).padding(dockListPadding(top, 0.dp)),
                        verticalArrangement = listSpacing) {
                        error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("manual-error"),
                            color = MaterialTheme.colorScheme.error) }
                        if (loading || submission.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        ManualFields(form, terms, enabled)
                        if (form.original != null) {
                            HorizontalDivider()
                            TextButton(onClick = { focus.clearFocus(); preview(true) }, enabled = enabled,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.align(Alignment.CenterHorizontally).sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .testTag("manual-remove")) { Text("移除课程") }
                        }
                    } else LazyColumn(body.testTag("manual-review"), contentPadding = dockListPadding(top, 0.dp),
                        verticalArrangement = listSpacing) {
                        item { Hint("只更改当前手动课程。与已有时段重叠时会保留双方；确认前不会写入。") }
                        items(review.changes) { change ->
                            FormGroup(Modifier.testTag("manual-change"), frosted = true) {
                                Text(change.field, style = MaterialTheme.typography.titleSmall)
                                change.before?.let { Text("原：$it") }; change.after?.let { Text("新：$it") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag(tag), enabled = enabled,
        colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .08f))) {
        Text(label)
    }
}

@Composable
private fun ManualFields(form: ManualCourseForm, terms: List<TermSpec>, enabled: Boolean) {
    SectionTitle("学期")
    if (form.original == null && terms.isNotEmpty()) {
        FormGroup(frosted = true) {
            PickRow("所属学期", form.existing?.id ?: "新建学期", "manual-term", enabled) { dismiss ->
                for (term in terms) PickOption(term.id, form.existing == term) { form.existing = term; form.weeks = setOf(1); dismiss() }
                PickOption("新建学期", form.existing == null) { form.existing = null; form.weeks = setOf(1); dismiss() }
            }
        }
    }
    val term = form.existing
    if (term == null) {
        Hint("填写校历：首周周一和总周数决定课程的实际日期，作息使用北京时间。")
        TermFields(form.newTerm, enabled, frosted = true) { form.newTerm = it }
    } else Hint("${term.id} · ${term.firstMonday} 起，共 ${term.weeks} 周 · ${if (term.initial == BellSchedule.SUMMER) "夏令" else "冬令"}作息" +
        (term.switchDate?.let { " · $it 起切换" } ?: ""))
    SectionTitle("课程信息")
    val p = form.pattern
    FormGroup(frosted = true) {
        ImportField("课程名称", p.title, "manual-title", enabled) { form.pattern = form.pattern.copy(title = it) }
        ImportField("上课地点（选填）", p.room, "manual-room", enabled) { form.pattern = form.pattern.copy(room = it) }
    }
    SectionTitle("上课时段")
    val focus = LocalFocusManager.current
    val count = form.weekCount
    val displayWeeks = remember(form.weeks) {
        val selected = form.weeks.sorted()
        if (selected.size > 8) "已选 ${selected.size} 周" else "第 ${selected.joinToString("、")} 周"
    }
    val chooseWeeks = enabled && count != null
    FormGroup(frosted = true) {
        PreferenceRow("上课周", if (count == null) "先填写总周数" else displayWeeks,
            Modifier.testTag("manual-weeks").alpha(if (chooseWeeks) 1f else .38f), chooseWeeks) {
            if (count != null) { focus.clearFocus(); form.choosingWeeks = form.weeks.filterTo(mutableSetOf()) { it in 1..count } }
        }
        PickRow("星期", weekday(p.day), "manual-day", enabled) { dismiss ->
            for (n in 1..7) PickOption(weekday(n), p.day == n, Modifier.testTag("manual-day:$n")) { form.pattern = p.copy(day = n); dismiss() }
        }
        PickRow("开始节次", "第 ${p.firstPeriod} 节", "manual-first", enabled) { dismiss ->
            for (n in 1..12) PickOption("第 $n 节", p.firstPeriod == n, Modifier.testTag("manual-first:$n")) {
                form.pattern = p.copy(firstPeriod = n, lastPeriod = maxOf(p.lastPeriod, n)); dismiss()
            }
        }
        PickRow("结束节次", "第 ${p.lastPeriod} 节", "manual-last", enabled) { dismiss ->
            for (n in p.firstPeriod..12) PickOption("第 $n 节", p.lastPeriod == n, Modifier.testTag("manual-last:$n")) {
                form.pattern = p.copy(lastPeriod = n); dismiss()
            }
        }
    }
}

@Composable
private fun PickRow(label: String, value: String, tag: String, enabled: Boolean, options: @Composable (() -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Box {
        PreferenceRow(label, value, Modifier.testTag(tag).alpha(if (enabled) 1f else .38f), enabled) { focus.clearFocus(); menu = true }
        PlannerDropdownMenu(menu, { menu = false }) { options { menu = false } }
    }
}

@Composable
private fun PickOption(label: String, current: Boolean, modifier: Modifier = Modifier, select: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = select, modifier = modifier.semantics { selected = current },
        trailingIcon = { RadioButton(selected = current, onClick = null) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekSelection(count: Int, chosen: Set<Int>, modifier: Modifier, top: Dp, change: (Set<Int>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val chipColors = if (LocalCapsuleBackdrop.current == null) FilterChipDefaults.filterChipColors() else
        FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, selectedContainerColor = colors.primary.copy(alpha = .14f),
            selectedLabelColor = colors.primary)
    Column(modifier.verticalScroll(rememberScrollState()).padding(dockListPadding(top, 0.dp))) {
        FormGroup(frosted = true) {
            Actions {
                TextButton({ change((1..count).toSet()) }, Modifier.heightIn(min = 48.dp)) { Text("全选") }
                TextButton({ change((1..count step 2).toSet()) }, Modifier.heightIn(min = 48.dp)) { Text("单周") }
                TextButton({ change((2..count step 2).toSet()) }, Modifier.heightIn(min = 48.dp), enabled = count >= 2) { Text("双周") }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (n in 1..count) FilterChip(selected = n in chosen, onClick = {
                    change(if (n in chosen) chosen - n else chosen + n)
                }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("manual-week:$n"),
                    colors = chipColors, label = { Text("$n") })
            }
        }
    }
}
