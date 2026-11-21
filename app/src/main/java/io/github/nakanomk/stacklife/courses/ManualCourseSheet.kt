package io.github.nakanomk.stacklife.courses

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.R
import androidx.compose.ui.res.stringResource
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
import io.github.nakanomk.stacklife.transitions.PaneMotion
import io.github.nakanomk.stacklife.transitions.PaneOrigins
import io.github.nakanomk.stacklife.transitions.paneOrigin
import io.github.nakanomk.stacklife.appearance.LocalPanelBack
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
    var newTerm by mutableStateOf(TermForm(weeks = 16))
    var pattern by mutableStateOf(original ?: CoursePattern("", "", 1, 1, 2))
    var weeks by mutableStateOf(setOf(openedWeek))
    var choosingWeeks by mutableStateOf<Set<Int>?>(null)
    val weekCount get() = existing?.weeks ?: newTerm.weeks
    var before by mutableStateOf<ManualSeries?>(null)
    var reviewedTerm: TermSpec? = null
    var revealWeek = openedWeek
    var removing = false
}

private sealed interface ManualPane {
    data object Fields : ManualPane
    data object Weeks : ManualPane
    data class Review(val ready: PreviewResult.Ready, val removing: Boolean) : ManualPane
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
            if (form.openedWeek !in read.weeks) error = uiText("This class no longer exists. Close the sheet and select a class again.", "所选课次已不存在，请关闭并重新选择。") else {
                form.before = ManualSeries(original, read.weeks)
                form.weeks = read.weeks
            }
        } catch (_: SQLiteException) { error = uiText("Cannot read the class. Close the sheet and open it again.", "无法读取课程，请关闭并重新打开。") }
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
                            model.discardProposal(result.proposalId); error = uiText("There are no changes to save.", "没有需要保存的更改。")
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
    val enabled = !loading && !submission.busy && (form.original == null || form.before != null)
    val review = ready
    val weekChoice = form.choosingWeeks
    val pane = when {
        weekChoice != null -> ManualPane.Weeks
        review != null -> ManualPane.Review(review, form.removing)
        else -> ManualPane.Fields
    }
    val origins = remember { PaneOrigins() }
    val scroll = rememberScrollState()
    LaunchedEffect(error) { if (error != null) scroll.scrollTo(0) }
    fun back() {
        if (submission.busy) return
        if (weekChoice != null) { form.choosingWeeks = null; return }
        if (review == null) close() else { model.discardProposal(review.proposalId); ready = null; error = null }
    }
    PlannerModalBottomSheet(close, nested = pane != ManualPane.Fields, onNestedBack = ::back, canDismiss = !submission.busy) {
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
                                title = { Text(when { weekChoice != null -> stringResource(R.string.courses_choose_weeks); review != null -> stringResource(R.string.courses_review_changes); form.original == null -> stringResource(R.string.courses_add); else -> stringResource(R.string.courses_edit) },
                                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                navigationIcon = {
                                    SheetAction(if (pane == ManualPane.Fields) stringResource(R.string.courses_cancel) else stringResource(R.string.courses_back), "manual-cancel", !submission.busy, onClick = LocalPanelBack.current ?: ::back)
                                }, actions = {
                                    Box(Modifier.paneOrigin(origins, "preview")) { when {
                                        weekChoice != null -> SheetAction(stringResource(R.string.courses_ok), "manual-weeks-done", weekChoice.isNotEmpty()) {
                                            form.weeks = weekChoice; form.choosingWeeks = null
                                        }
                                        review == null -> SheetAction(stringResource(R.string.courses_preview), "manual-preview", enabled) { focus.clearFocus(); preview(false) }
                                        else -> SheetAction(if (form.removing) stringResource(R.string.courses_confirm_remove) else stringResource(R.string.courses_save_class), "manual-save", !submission.busy) {
                                            submission.submit { model.confirmProposal(review.proposalId) }
                                        }
                                    } }
                                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent,
                                    scrolledContainerColor = Color.Transparent), windowInsets = WindowInsets(0))
                        }
                    }
                }) { insets ->
                    val body = Modifier.fillMaxSize()
                    val top = insets.calculateTopPadding()
                    androidx.activity.compose.BackHandler(enabled = submission.busy) { }
                    PaneMotion(pane, origins, depth = { if (it == ManualPane.Fields) 0 else 1 }, originKey = {
                        when (it) {
                            ManualPane.Fields -> "fields"
                            ManualPane.Weeks -> "weeks"
                            is ManualPane.Review -> if (it.removing) "remove" else "preview"
                        }
                    }, modifier = body.plannerBackdrop(scrollingPlane), parent = ManualPane.Fields.takeIf { pane != it && !submission.busy },
                        onBack = ::back) { shown ->
                    if (shown == ManualPane.Weeks) WeekSelection(form.weekCount ?: 0, weekChoice ?: form.weeks, body, top) {
                        form.choosingWeeks = it
                    } else if (shown == ManualPane.Fields) Column(body.verticalScroll(scroll).padding(dockListPadding(top, 0.dp)),
                        verticalArrangement = listSpacing) {
                        error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("manual-error"),
                            color = MaterialTheme.colorScheme.error) }
                        if (loading || submission.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        ManualFields(form, terms, enabled, origins)
                        if (form.original != null) {
                            HorizontalDivider()
                            TextButton(onClick = { focus.clearFocus(); preview(true) }, enabled = enabled,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.align(Alignment.CenterHorizontally).sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .paneOrigin(origins, "remove").testTag("manual-remove")) { Text(stringResource(R.string.courses_remove_class)) }
                        }
                    } else if (shown is ManualPane.Review) LazyColumn(body.testTag("manual-review"), contentPadding = dockListPadding(top, 0.dp),
                        verticalArrangement = listSpacing) {
                        item { Hint(stringResource(R.string.courses_manual_review_hint)) }
                        items(shown.ready.changes) { change ->
                            FormGroup(Modifier.testTag("manual-change"), frosted = true) {
                                Text(change.field, style = MaterialTheme.typography.titleSmall)
                                change.before?.let { Text(stringResource(R.string.courses_before, it)) }; change.after?.let { Text(stringResource(R.string.courses_after, it)) }
                            }
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
private fun ManualFields(form: ManualCourseForm, terms: List<TermSpec>, enabled: Boolean, origins: PaneOrigins) {
    SectionTitle(stringResource(R.string.courses_term))
    if (form.original == null && terms.isNotEmpty()) {
        FormGroup(frosted = true) {
            PickRow(stringResource(R.string.courses_assigned_term), form.existing?.id ?: stringResource(R.string.courses_new_term), "manual-term", enabled) { dismiss ->
                for (term in terms) PickOption(term.id, form.existing == term) { form.existing = term; form.weeks = setOf(1); dismiss() }
                PickOption(stringResource(R.string.courses_new_term), form.existing == null) { form.existing = null; form.weeks = setOf(1); dismiss() }
            }
        }
    }
    val term = form.existing
    if (term == null) {
        Hint(stringResource(R.string.courses_term_setup_hint))
        TermFields(form.newTerm, enabled, frosted = true) { form.newTerm = it }
    } else Hint(stringResource(R.string.courses_term_summary, term.id, term.firstMonday, term.weeks, stringResource(if (term.initial == BellSchedule.SUMMER) R.string.courses_summer else R.string.courses_winter)) +
        (term.switchDate?.let { stringResource(R.string.courses_switch_on, it) } ?: ""))
    SectionTitle(stringResource(R.string.courses_class_info))
    val p = form.pattern
    FormGroup(frosted = true) {
        ImportField(stringResource(R.string.courses_class_name), p.title, "manual-title", enabled) { form.pattern = form.pattern.copy(title = it) }
        ImportField(stringResource(R.string.courses_optional_room), p.room, "manual-room", enabled) { form.pattern = form.pattern.copy(room = it) }
    }
    SectionTitle(stringResource(R.string.courses_class_time))
    val focus = LocalFocusManager.current
    val count = form.weekCount
    val selected = remember(form.weeks) { form.weeks.sorted() }
    val displayWeeks = if (selected.size > 8) stringResource(R.string.courses_weeks_count, selected.size)
        else stringResource(R.string.courses_weeks_list, selected.joinToString(stringResource(R.string.courses_list_separator)))
    val chooseWeeks = enabled && count != null
    FormGroup(frosted = true) {
        PreferenceRow(stringResource(R.string.courses_class_weeks), if (count == null) stringResource(R.string.courses_enter_weeks_first) else displayWeeks,
            Modifier.paneOrigin(origins, "weeks").testTag("manual-weeks").alpha(if (chooseWeeks) 1f else .38f), chooseWeeks) {
            if (count != null) { focus.clearFocus(); form.choosingWeeks = form.weeks.filterTo(mutableSetOf()) { it in 1..count } }
        }
        PickRow(stringResource(R.string.courses_weekday), weekday(p.day), "manual-day", enabled) { dismiss ->
            for (n in 1..7) PickOption(weekday(n), p.day == n, Modifier.testTag("manual-day:$n")) { form.pattern = p.copy(day = n); dismiss() }
        }
        PickRow(stringResource(R.string.courses_first_period), stringResource(R.string.courses_period, p.firstPeriod), "manual-first", enabled) { dismiss ->
            for (n in 1..12) PickOption(stringResource(R.string.courses_period, n), p.firstPeriod == n, Modifier.testTag("manual-first:$n")) {
                form.pattern = p.copy(firstPeriod = n, lastPeriod = maxOf(p.lastPeriod, n)); dismiss()
            }
        }
        PickRow(stringResource(R.string.courses_last_period), stringResource(R.string.courses_period, p.lastPeriod), "manual-last", enabled) { dismiss ->
            for (n in p.firstPeriod..12) PickOption(stringResource(R.string.courses_period, n), p.lastPeriod == n, Modifier.testTag("manual-last:$n")) {
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
                TextButton({ change((1..count).toSet()) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.courses_select_all)) }
                TextButton({ change((1..count step 2).toSet()) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.courses_odd_weeks)) }
                TextButton({ change((2..count step 2).toSet()) }, Modifier.heightIn(min = 48.dp), enabled = count >= 2) { Text(stringResource(R.string.courses_even_weeks)) }
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
