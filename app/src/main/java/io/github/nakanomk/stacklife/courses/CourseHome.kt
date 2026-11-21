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
import io.github.nakanomk.stacklife.appearance.PlannerModalBottomSheet
import io.github.nakanomk.stacklife.appearance.LocalPanelBack
import androidx.compose.foundation.clickable
import io.github.nakanomk.stacklife.transitions.PaneMotion
import io.github.nakanomk.stacklife.transitions.PaneOrigins
import io.github.nakanomk.stacklife.transitions.paneOrigin
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
import io.github.nakanomk.stacklife.transitions.rememberRouteLaunch
import io.github.nakanomk.stacklife.transitions.routeOrigin
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
    RootPane(contentDrawsTopEdge = term != null && week != null && !listMode, header = {
        RootToolbar(onSettings, leading = {
            if (term != null) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    val imported = term.id in catalog.importedIds
                    val owner = Modifier.routeOrigin("course-import", CircleShape)
                        .then(if (imported) Modifier.routeOrigin("remove-term:${term.id}", CircleShape) else Modifier)
                    GlassIcon(R.drawable.ic_more_vert, stringResource(R.string.courses_options), { menu = true }, owner.testTag("course-options"))
                    PlannerDropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.courses_add)) }, { menu = false; add() }, modifier = Modifier.testTag("course-add"),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_add), null) })
                        DropdownMenuItem({ Text(if (listMode) stringResource(R.string.courses_week_grid) else stringResource(R.string.courses_list)) },
                            { menu = false; listMode = !listMode }, modifier = Modifier.testTag("course-view-toggle"))
                        val importLaunch = rememberRouteLaunch("course-import")
                        DropdownMenuItem({ Text(stringResource(R.string.courses_import)) }, { importLaunch.open { menu = false; onImport() } },
                            modifier = importLaunch.modifier.testTag("course-import"))
                        if (terms.size > 1) {
                            HorizontalDivider()
                            for (choice in terms) DropdownMenuItem({ Text(choice.id) }, { menu = false; onTerm(choice) },
                                enabled = choice.id != term.id)
                        }
                        if (imported) {
                            HorizontalDivider()
                            val removal = rememberRouteLaunch("remove-term:${term.id}")
                            DropdownMenuItem({ Text(stringResource(R.string.courses_remove_hub)) }, { removal.open { menu = false; onRemove(term) } },
                                modifier = removal.modifier.testTag("course-remove"))
                        }
                    }
                }
            }
        }) {
            if (term != null && week != null && pager != null) WeekPicker(term, week, pager) { onWeek(term, it) }
            else TextButton(onClick = onImport, modifier = Modifier.routeOrigin("course-import", CircleShape).testTag("course-import")) { Text(stringResource(R.string.courses_import)) }
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
    var selectedDay by rememberSaveable { mutableIntStateOf(0) }
    val chosen = selected?.let { key -> week.classes.firstOrNull { it.occurrenceKey() == key } }
    if (chosen != null || selectedDay != 0) {
        val day = selectedDay.takeIf { it != 0 }
        val label = day?.let {
            val date = term.firstMonday.plusWeeks(week.week - 1L).plusDays(it - 1L)
            date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(AppLanguage.locale))
        }
        val classes = remember(day, week.classes) { if (day == null) emptyList() else week.classes.filter { it.meeting.day == day } }
        CourseReadPanel(chosen, label, classes, open = { selected = it.occurrenceKey() },
            edit = { course -> selected = null; selectedDay = 0; edit(course) },
            close = { if (selected != null) selected = null else selectedDay = 0 })
        if (chosen == null && selected != null) SideEffect { selected = null }
    }
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
    } else WeekGrid(term, week, top, bottom, openDay = { selectedDay = it }, open = open)
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
internal fun CourseDetails(course: ScheduledCourse, close: () -> Unit) {
    CourseReadPanel(course, dayLabel = null, dayCourses = emptyList(), close = close)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseReadPanel(course: ScheduledCourse?, dayLabel: String?, dayCourses: List<ScheduledCourse>,
    open: ((ScheduledCourse) -> Unit)? = null, edit: ((ScheduledCourse) -> Unit)? = null, close: () -> Unit) {
    val origins = remember { PaneOrigins() }
    val nested = course != null && dayLabel != null
    val dayList = androidx.compose.foundation.lazy.rememberLazyListState()
    PlannerModalBottomSheet(close, nested = nested, onNestedBack = close) {
        val back = LocalPanelBack.current ?: close
        PaneMotion<CourseReading>(course?.let { CourseReading.Detail(it) } ?: CourseReading.Day, origins,
            depth = { if (it == CourseReading.Day) 0 else 1 }, originKey = { if (it is CourseReading.Detail) it.course.occurrenceKey() else "day" },
            parent = CourseReading.Day.takeIf { nested }, onBack = close, modifier = Modifier.fillMaxWidth()) { shown ->
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 4.dp)
                .then(if (shown is CourseReading.Detail) Modifier.testTag("course-detail") else Modifier)) {
                if (shown is CourseReading.Detail) {
                    val detail = shown.course
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.courses_fixed_class), style = MaterialTheme.typography.titleLarge)
                        Text(detail.meeting.title, style = MaterialTheme.typography.titleLarge)
                        Text("${detail.termId} · ${when (detail.source) { MANUAL_SOURCE -> stringResource(R.string.courses_manual_source); HUST_SOURCE -> stringResource(R.string.courses_hub_source); else -> stringResource(R.string.courses_fixed_timetable) }}",
                            style = MaterialTheme.typography.labelLarge)
                        CourseInfo(detail)
                        AssistantComposer(AssistantPage.Week(detail.termId, detail.meeting.week))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (edit != null && detail.source == MANUAL_SOURCE) {
                            TextButton(onClick = { edit(detail) }, modifier = Modifier.heightIn(min = 48.dp).testTag("manual-edit")) { Text(stringResource(R.string.courses_edit)) }
                        }
                        TextButton(onClick = back) { Text(stringResource(if (nested) R.string.courses_back else R.string.courses_close)) }
                    }
                } else {
                    Text(dayLabel.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    if (dayCourses.isEmpty()) Text(stringResource(R.string.courses_no_classes_day))
                    else LazyColumn(Modifier.weight(1f, fill = false), state = dayList) {
                        itemsIndexed(dayCourses, key = { _, c -> c.occurrenceKey() }) { _, row ->
                            ListItem(headlineContent = { Text(row.meeting.title) }, supportingContent = {
                                Text("${row.meeting.periodLabel()} · ${row.meeting.room.ifBlank { stringResource(R.string.courses_no_room) }}")
                            }, modifier = Modifier.fillMaxWidth().paneOrigin(origins, row.occurrenceKey()).clickable { open?.invoke(row) })
                        }
                    }
                    TextButton(onClick = back, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.courses_close)) }
                }
            }
        }
    }
}

private sealed interface CourseReading {
    data object Day : CourseReading
    data class Detail(val course: ScheduledCourse) : CourseReading
}
