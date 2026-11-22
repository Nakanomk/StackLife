package io.github.nakanomk.stacklife.courses

import android.database.sqlite.SQLiteException
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.assistant.ChangePreview
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.transitions.PaneMotion
import io.github.nakanomk.stacklife.transitions.PaneOrigins
import io.github.nakanomk.stacklife.transitions.paneOrigin
import io.github.nakanomk.stacklife.workspace.*
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val ManualStep.title: Int get() = when (this) {
    ManualStep.TERM -> R.string.manual_step_term
    ManualStep.COURSE -> R.string.manual_step_course
    ManualStep.WEEKS -> R.string.courses_choose_weeks
    ManualStep.REVIEW -> R.string.courses_review_changes
}
private val ManualStep.tag: String get() = when (this) {
    ManualStep.TERM -> "manual-step-term"
    ManualStep.COURSE -> "manual-step-course"
    ManualStep.WEEKS -> "manual-step-weeks"
    ManualStep.REVIEW -> "manual-review"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualCourseSheet(model: WorkspaceViewModel, form: ManualCourseForm, terms: List<TermSpec>,
    close: () -> Unit, saved: (TermSpec, Int) -> Unit) {
    val draft = form.draft
    val step = draft.step
    val context = LocalContext.current
    val activity = LocalActivity.current
    val origins = remember { PaneOrigins() }
    val scrolls = remember(form) { form.draft.scroll.map(::ScrollState) }
    var error by remember(form) { mutableStateOf<String?>(null) }
    var previewAttempt by remember(form) { mutableIntStateOf(0) }
    LaunchedEffect(error) {
        if (error != null) scrolls[form.draft.step.ordinal].scrollTo(0)
    }
    val interrupted = draft.submitted && !form.busy
    fun clearReview() {
        form.ready?.let { model.discardProposal(it.proposalId) }
        form.ready = null
    }
    fun go(next: ManualStep, remove: Boolean = false) {
        if (form.busy || interrupted) return
        error = null
        if (step == ManualStep.REVIEW) clearReview()
        form.rememberScroll(step, scrolls[step.ordinal].value)
        form.update { it.copy(step = next, removing = remove) }
    }
    fun back() {
        if (form.busy) return
        if (step == ManualStep.TERM || interrupted || form.unreadable) close() else go(draft.previous())
    }

    LaunchedEffect(form) {
        snapshotFlow { form.draft }.collect {
            try { withContext(Dispatchers.IO) { form.checkpoint() }; form.fileError = false }
            catch (_: IOException) { form.fileError = true }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        form.rememberScroll(step, scrolls[step.ordinal].value)
        try { form.checkpoint() } catch (_: IOException) { form.fileError = true }
    }
    DisposableEffect(form) {
        onDispose { if (!form.busy && activity?.isChangingConfigurations != true) clearReview() }
    }
    LaunchedEffect(form) {
        val p = draft.original ?: return@LaunchedEffect
        if (draft.originalWeeks != null || draft.submitted) return@LaunchedEffect
        try {
            val read = model.planner.observe(PlannerQuery.CourseWeeks(checkNotNull(draft.existing).id, MANUAL_SOURCE, p)).first() as CourseWeeksView
            if (draft.openedWeek !in read.weeks) error = context.getString(R.string.manual_missing_class)
            else form.update { it.copy(originalWeeks = read.weeks, weeks = read.weeks) }
        } catch (_: SQLiteException) { error = context.getString(R.string.manual_read_class_failed) }
    }
    LaunchedEffect(form, step, previewAttempt) {
        if (step != ManualStep.REVIEW || form.ready != null || draft.submitted || form.unreadable) return@LaunchedEffect
        var acquired: PreviewResult.Ready? = null
        try {
            val term = draft.term()
            val before = draft.original?.let { ManualSeries(it, checkNotNull(draft.originalWeeks)) }
            val after = if (draft.removing) null else ManualSeries(draft.pattern.copy(
                title = draft.pattern.title.trim(), room = draft.pattern.room.trim()), draft.weeks)
            val command = PlannerCommand.ManualCourse(term.id, before, after, term.takeIf { draft.existing == null })
            val result = withContext(NonCancellable) { model.preview(command).also { acquired = it as? PreviewResult.Ready } }
            currentCoroutineContext().ensureActive()
            when (result) {
                is PreviewResult.Rejected -> error = result.message
                is PreviewResult.Ready -> if (result.changes.isEmpty()) error = context.getString(R.string.manual_no_changes)
                    else { form.ready = result; acquired = null }
            }
        } catch (_: SQLiteException) { error = context.getString(R.string.manual_read_term_failed) }
        finally { acquired?.let { model.discardProposal(it.proposalId) } }
    }
    LaunchedEffect(form, form.receipt) {
        val ticket = form.receipt ?: return@LaunchedEffect
        val outcome = model.result(ticket) { error = it }
        form.ready = null
        form.receipt = null
        when (outcome) {
            SaveOutcome.APPLIED -> if (draft.removing) close() else saved(draft.term(),
                draft.openedWeek.takeIf { it in draft.weeks } ?: draft.weeks.min())
            SaveOutcome.REJECTED -> form.update { it.copy(submitted = false) }
            SaveOutcome.INTERRUPTED -> Unit // No receipt: leave the attempted draft read-only.
        }
    }
    val nested = step != ManualStep.TERM && !interrupted && !form.unreadable
    val originalLoaded = draft.original == null || draft.originalWeeks != null
    val canNext = !form.busy && !interrupted && !form.unreadable && originalLoaded && when (step) {
        ManualStep.TERM -> draft.existing != null || (draft.newTerm.monday != null && draft.newTerm.weeks != null)
        ManualStep.COURSE -> draft.pattern.title.isNotBlank()
        ManualStep.WEEKS -> draft.weeks.isNotEmpty()
        ManualStep.REVIEW -> form.ready != null
    }
    fun advance() {
        if (!canNext || form.draft.step != step) return
        when (step) {
            ManualStep.TERM -> try { draft.term(); go(ManualStep.COURSE) } catch (e: IllegalArgumentException) { error = e.message }
            ManualStep.COURSE -> {
                val problem = courseFieldProblem(draft.pattern.title.trim(), draft.pattern.room.trim())
                if (problem != null) error = problem else go(ManualStep.WEEKS)
            }
            ManualStep.WEEKS -> go(ManualStep.REVIEW)
            ManualStep.REVIEW -> form.submit(model)
        }
    }
    val nextLabel = when (step) {
        ManualStep.REVIEW -> if (draft.removing) R.string.courses_confirm_remove else R.string.ui_save
        ManualStep.WEEKS -> R.string.courses_preview
        else -> R.string.manual_next
    }
    PlannerModalBottomSheet(close, nested = nested, onNestedBack = ::back, canDismiss = !form.busy) {
        val dialogFocus = LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        LaunchedEffect(step) { dialogFocus.clearFocus(force = true); keyboard?.hide() }
        Box(Modifier.fillMaxSize().testTag("manual-course-sheet")) {
            PlannerPopupHost {
                val plane = rememberPlannerBackdrop()
                Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0), topBar = {
                    CompositionLocalProvider(LocalCapsuleBackdrop provides plane) {
                        StackLifeSurface(CapsuleScene.FROST, Modifier.fillMaxWidth().testTag("manual-toolbar"),
                            solid = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(0.dp)) {
                            CenterAlignedTopAppBar(title = {
                                Text(stringResource(R.string.manual_progress, step.ordinal + 1), style = MaterialTheme.typography.labelLarge)
                            }, navigationIcon = {
                                val backAction = LocalPanelBack.current ?: ::back
                                IconButton(onClick = backAction, enabled = !form.busy, modifier = Modifier.testTag(if (nested) "manual-back" else "manual-cancel")) {
                                    Icon(painterResource(if (nested) R.drawable.ic_arrow_back else R.drawable.ic_close),
                                        stringResource(if (nested) R.string.ui_back else R.string.ui_cancel))
                                }
                            }, actions = {
                                val launcher = Modifier.paneOrigin(origins, ManualStep.COURSE.tag).paneOrigin(origins, ManualStep.WEEKS.tag)
                                Box(if (draft.removing) launcher else launcher.paneOrigin(origins, ManualStep.REVIEW.tag)) {
                                    SheetAction(stringResource(nextLabel), when (step) {
                                        ManualStep.REVIEW -> "manual-save"
                                        ManualStep.WEEKS -> "manual-preview"
                                        else -> "manual-next"
                                    }, canNext, ::advance)
                                }
                            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent,
                                scrolledContainerColor = Color.Transparent), windowInsets = WindowInsets(0))
                        }
                    }
                }) { insets ->
                    PaneMotion(step, origins, depth = { it.ordinal }, originKey = { it.tag },
                        modifier = Modifier.fillMaxSize().padding(insets).plannerBackdrop(plane),
                        parent = draft.previous().takeIf { nested && !form.busy }, onBack = ::back) { shown ->
                        val scroll = scrolls[shown.ordinal]
                        val ready = form.ready
                        // Do not clamp a restored review offset to a one-row loading placeholder.
                        val scrollable = shown != ManualStep.REVIEW || ready != null
                        LaunchedEffect(form, shown, scrollable) {
                            if (scrollable) snapshotFlow { scroll.value.takeUnless { scroll.isScrollInProgress } }.collect { offset ->
                                if (offset != null && form.draft.step == shown) form.rememberScroll(shown, offset)
                            }
                        }
                        Column(Modifier.fillMaxSize().testTag(shown.tag).then(if (scrollable) Modifier.verticalScroll(scroll)
                            else Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))
                            .padding(dockListPadding(0.dp, 0.dp)), verticalArrangement = listSpacing) {
                            SectionTitle(stringResource(shown.title))
                            val problem = when {
                                form.unreadable || form.fileError -> stringResource(R.string.manual_draft_error)
                                interrupted -> stringResource(R.string.manual_interrupted)
                                else -> error
                            }
                            if (problem != null) Text(problem, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("manual-error"),
                                color = MaterialTheme.colorScheme.error)
                            if (!form.unreadable && !interrupted) {
                                when (shown) {
                                    ManualStep.TERM -> CalendarFields(form, terms)
                                    ManualStep.COURSE -> {
                                        CourseFields(form)
                                        if (draft.original != null) TextButton({ go(ManualStep.REVIEW, remove = true) },
                                            enabled = originalLoaded, modifier = Modifier.heightIn(min = 48.dp)
                                                .then(if (draft.removing) Modifier.paneOrigin(origins, ManualStep.REVIEW.tag) else Modifier).testTag("manual-remove"),
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                            Text(stringResource(R.string.courses_remove_class))
                                        }
                                    }
                                    ManualStep.WEEKS -> WeekSelection(draft.weekCount, draft.weeks) { weeks -> form.update { it.copy(weeks = weeks) } }
                                    ManualStep.REVIEW -> {
                                        Hint(stringResource(R.string.courses_manual_review_hint))
                                        if (ready != null) {
                                            for (change in ready.changes) key(change.entityId) { ChangePreview(change, Modifier.testTag("manual-change")) }
                                            SheetAction(stringResource(nextLabel), "manual-save-bottom", canNext, ::advance)
                                        } else if (error != null) TextButton({ error = null; previewAttempt++ }) { Text(stringResource(R.string.courses_preview_again)) }
                                        if (form.busy || (ready == null && error == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                }
                            }
                            if (nested) TextButton(close, enabled = !form.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("manual-cancel")) {
                                Text(stringResource(R.string.ui_cancel))
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
private fun CalendarFields(form: ManualCourseForm, terms: List<TermSpec>) {
    val d = form.draft
    if (d.original == null && terms.isNotEmpty()) {
        FormGroup(Modifier.selectableGroup(), frosted = true) {
            for (term in terms) TermChoice(term.dateRangeLabel(), d.existing?.id == term.id, "manual-term:${term.id}") {
                if (d.existing?.id != term.id) form.update { it.copy(existing = term, weeks = setOf(1)) }
            }
            TermChoice(stringResource(R.string.courses_new_term), d.existing == null, "manual-term:new") {
                if (d.existing != null) form.update { it.copy(existing = null, weeks = setOf(1)) }
            }
        }
    } else d.existing?.let { Text(it.dateRangeLabel(), style = MaterialTheme.typography.bodyLarge) }
    if (d.existing == null) {
        TermFields(d.newTerm, true, frosted = true) { term -> form.update {
            it.copy(newTerm = term, weeks = if (term.weeks == it.newTerm.weeks) it.weeks else it.weeks.filterTo(mutableSetOf()) { w -> w <= (term.weeks ?: 0) })
        } }
    }
}

@Composable
private fun TermChoice(label: String, selected: Boolean, tag: String, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = choose)
        .heightIn(min = 48.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CourseFields(form: ManualCourseForm) {
    val p = form.draft.pattern
    FormGroup(frosted = true) {
        ImportField(stringResource(R.string.courses_class_name), p.title, "manual-title") { title -> form.update { it.copy(pattern = it.pattern.copy(title = title)) } }
        ImportField(stringResource(R.string.courses_optional_room), p.room, "manual-room") { room -> form.update { it.copy(pattern = it.pattern.copy(room = room)) } }
    }
    FormGroup(frosted = true) {
        PickRow(stringResource(R.string.courses_weekday), weekday(p.day), "manual-day") { dismiss ->
            for (n in 1..7) PickOption(weekday(n), p.day == n, Modifier.testTag("manual-day:$n")) {
                form.update { it.copy(pattern = it.pattern.copy(day = n)) }; dismiss()
            }
        }
        PeriodWheelField(p.firstPeriod..p.lastPeriod, Modifier.testTag("manual-periods")) { range ->
            form.update { it.copy(pattern = it.pattern.copy(firstPeriod = range.first, lastPeriod = range.last)) }
        }
    }
}

@Composable
private fun PickRow(label: String, value: String, tag: String, options: @Composable (() -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Box {
        PreferenceRow(label, value, Modifier.testTag(tag)) { focus.clearFocus(); menu = true }
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
private fun WeekSelection(count: Int, chosen: Set<Int>, change: (Set<Int>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val chipColors = if (LocalCapsuleBackdrop.current == null) FilterChipDefaults.filterChipColors() else
        FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, selectedContainerColor = colors.primary.copy(alpha = .14f),
            selectedLabelColor = colors.primary)
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
