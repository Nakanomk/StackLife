package io.github.nakanomk.stacklife.courses

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.i18n.AppLanguage
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.GlassIcon
import io.github.nakanomk.stacklife.RootPane
import io.github.nakanomk.stacklife.RootToolbar
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.appearance.PlannerDropdownMenu
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.assistant.AssistantComposer
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.planner.Terms
import io.github.nakanomk.stacklife.workspace.ActionPill
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nakanomk.stacklife.planner.CourseWeek
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.today.CalendarPager
import java.time.Instant
import java.time.format.DateTimeFormatter

private val campusClock = DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ROOT).withZone(campusZone)
private fun CourseMeeting.timeLabel() = "${campusClock.format(Instant.ofEpochMilli(startMillis))}–${campusClock.format(Instant.ofEpochMilli(endMillis))}"
internal fun CourseMeeting.periodLabel() = if (firstPeriod == lastPeriod) uiText({ "Period $firstPeriod" }, { "第 $firstPeriod 节" }) else uiText({ "Periods $firstPeriod–$lastPeriod" }, { "第 $firstPeriod–$lastPeriod 节" })

@Composable
private fun RowScope.WeekPicker(term: TermSpec, selected: Int, pager: PagerState, onWeek: (Int) -> Unit) {
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    val week by remember(pager, selected) { derivedStateOf {
        val origin = pager.settledPage
        when {
            dragged -> {
                val travel = pager.currentPage - origin + pager.currentPageOffsetFraction
                (origin + travel.compareTo(0f)).coerceIn(0, pager.pageCount - 1) + 1
            }
            pager.isScrollInProgress && selected == origin + 1 -> pager.targetPage + 1
            else -> selected
        }
    } }
    IconButton(onClick = { onWeek(week - 1) }, enabled = week > 1, modifier = Modifier.testTag("course-prev")) {
        Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.courses_previous_week))
    }
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.weight(1f, fill = false)) {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("course-week")) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(term.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.courses_week, week), maxLines = 1, style = MaterialTheme.typography.titleSmall)
                    Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(16.dp))
                }
            }
        }
        PlannerDropdownMenu(expanded, { expanded = false }) {
            for (n in 1..term.weeks) DropdownMenuItem(text = { Text(stringResource(R.string.courses_week, n)) }, onClick = { expanded = false; onWeek(n) })
        }
    }
    IconButton(onClick = { onWeek(week + 1) }, enabled = week < term.weeks, modifier = Modifier.testTag("course-next")) {
        Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.courses_next_week))
    }
}

@Composable
internal fun CourseHome(
    model: WorkspaceViewModel, catalog: Terms, term: TermSpec?, week: Int?,
    onTerm: (TermSpec) -> Unit, onWeek: (TermSpec, Int) -> Unit, onImport: () -> Unit,
    onRemove: (TermSpec) -> Unit, onSettings: () -> Unit,
) {
    var listMode by rememberSaveable { mutableStateOf(false) }
    val pager = if (term != null && week != null) key(term.id) {
        rememberPagerState(initialPage = week - 1) { term.weeks }
    } else null
    val editor: ManualEditorModel = viewModel { ManualEditorModel() }
    val add = { editor.form = ManualCourseForm(term, week ?: 1) }
    val terms = catalog.terms
    editor.form?.let { form ->
        key(form) {
            ManualCourseSheet(model, form, terms, close = { editor.form = null }, saved = { spec, weekNumber ->
                onWeek(spec, weekNumber); editor.form = null
            })
        }
    }
    RootPane(header = {
        RootToolbar(onSettings, leading = {
            if (term != null) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    GlassIcon(R.drawable.ic_more_vert, stringResource(R.string.courses_options), { menu = true }, Modifier.testTag("course-options"))
                    PlannerDropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.courses_add)) }, { menu = false; add() }, modifier = Modifier.testTag("course-add"),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_add), null) })
                        DropdownMenuItem({ Text(if (listMode) stringResource(R.string.courses_week_grid) else stringResource(R.string.courses_list)) },
                            { menu = false; listMode = !listMode }, modifier = Modifier.testTag("course-view-toggle"))
                        DropdownMenuItem({ Text(stringResource(R.string.courses_import)) }, { menu = false; onImport() }, modifier = Modifier.testTag("course-import"))
                        if (terms.size > 1) {
                            HorizontalDivider()
                            for (choice in terms) DropdownMenuItem({ Text(choice.id) }, { menu = false; onTerm(choice) },
                                enabled = choice.id != term.id)
                        }
                        if (term.id in catalog.importedIds) {
                            HorizontalDivider()
                            DropdownMenuItem({ Text(stringResource(R.string.courses_remove_hub)) }, { menu = false; onRemove(term) }, modifier = Modifier.testTag("course-remove"))
                        }
                    }
                }
            }
        }) {
            if (term != null && week != null && pager != null) WeekPicker(term, week, pager) { onWeek(term, it) }
            else TextButton(onClick = onImport, modifier = Modifier.testTag("course-import")) { Text(stringResource(R.string.courses_import)) }
        }
    }) { top, bottom ->
        if (term == null || week == null || pager == null) {
            Column(Modifier.fillMaxSize().padding(top = top, bottom = bottom).verticalScroll(rememberScrollState())
                .padding(16.dp)) {
                Text(stringResource(R.string.courses_no_timetable), style = MaterialTheme.typography.titleMedium)
                ActionPill(stringResource(R.string.courses_add), add, Modifier.padding(top = 12.dp).testTag("course-add"), prominent = true)
                Text(stringResource(R.string.courses_empty_timetable_hint),
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.courses_assistant_hint), Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else key(term.id) {
            CalendarPager(week - 1, term.weeks, { onWeek(term, it + 1) }, Modifier.fillMaxSize(), pager) { index ->
                val query = remember(term.id, index) { PlannerQuery.Week(term.id, index + 1) }
                val data by remember(model, query) { model.week(query) }.collectAsStateWithLifecycle(initialValue = ReadState.Loading)
                ReadPanel(data) { result ->
                    if (result.term != null) CourseWeekPage(result.term, result, listMode, top, bottom) { selected ->
                        editor.form = ManualCourseForm(result.term, selected.meeting.week, selected.meeting.pattern())
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseWeekPage(term: TermSpec, week: CourseWeek, listMode: Boolean, top: Dp, bottom: Dp, edit: (ScheduledCourse) -> Unit) {
    // Store an occurrence key, not a list position: after reimport never show a different course.
    var selected by remember { mutableStateOf<String?>(null) }
    val chosen = selected?.let { key -> week.classes.firstOrNull { it.occurrenceKey() == key } }
    if (chosen != null) CourseDetails(chosen, edit = { selected = null; edit(chosen) }) { selected = null }
    else if (selected != null) SideEffect { selected = null }
    val open: (ScheduledCourse) -> Unit = { selected = it.occurrenceKey() }
    if (listMode) LazyColumn(Modifier.fillMaxSize().testTag("course-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 8.dp, bottom = bottom + 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (week.classes.isEmpty()) item { Text(stringResource(R.string.courses_no_classes_week)) }
        itemsIndexed(week.classes, key = { _, c -> c.occurrenceKey() }) { i, course ->
            Surface(onClick = { open(course) }, modifier = Modifier.fillMaxWidth().testTag("week-course:$i"),
                color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val color = rememberCourseColor(term.id, week.colorIndices.getValue(course.meeting.title))
                    Spacer(Modifier.padding(top = 6.dp).size(12.dp).background(color, CircleShape))
                    Column {
                        Text(course.meeting.title, style = MaterialTheme.typography.titleMedium)
                        CourseInfo(course)
                    }
                }
            }
        }
    } else WeekGrid(term, week, top, bottom, open)
}

private fun ScheduledCourse.occurrenceKey(): String = with(meeting) {
    "$source/$day/$firstPeriod/$lastPeriod/${title.length}:$title$room"
}

@Composable
private fun CourseInfo(course: ScheduledCourse) {
    val m = course.meeting
    val pattern = stringResource(R.string.courses_date_pattern)
    val locale = AppLanguage.locale
    val campusDate = remember(pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale).withZone(campusZone) }
    Text(stringResource(R.string.courses_class_date, m.week, campusDate.format(Instant.ofEpochMilli(m.startMillis)), m.periodLabel()), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.courses_beijing_time, m.timeLabel()), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.courses_room, m.room.ifBlank { stringResource(R.string.courses_not_provided) }), style = MaterialTheme.typography.bodyMedium)
    if (course.conflicts.isNotEmpty()) Text(stringResource(R.string.courses_conflicts, course.conflicts.joinToString(stringResource(R.string.courses_list_separator))), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun CourseDetails(course: ScheduledCourse, edit: (() -> Unit)? = null, close: () -> Unit) {
    PlannerAlertDialog(modifier = Modifier.testTag("course-detail"), onDismissRequest = close, title = { Text(stringResource(R.string.courses_fixed_class)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(course.meeting.title, style = MaterialTheme.typography.titleLarge)
                Text("${course.termId} · ${when (course.source) { MANUAL_SOURCE -> stringResource(R.string.courses_manual_source); HUST_SOURCE -> stringResource(R.string.courses_hub_source); else -> stringResource(R.string.courses_fixed_timetable) }}",
                    style = MaterialTheme.typography.labelLarge)
                CourseInfo(course)
                AssistantComposer(AssistantPage.Week(course.termId, course.meeting.week))
            }
        }, dismissButton = {
            if (edit != null && course.source == MANUAL_SOURCE)
                TextButton(onClick = edit, modifier = Modifier.heightIn(min = 48.dp).testTag("manual-edit")) { Text(stringResource(R.string.courses_edit)) }
        }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.courses_close)) } })
}
