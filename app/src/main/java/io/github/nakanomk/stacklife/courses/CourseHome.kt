package io.github.nakanomk.stacklife.courses

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.i18n.AppLanguage
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
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
import io.github.nakanomk.stacklife.countdown.AddOccurrenceCountdown
import io.github.nakanomk.stacklife.transitions.LocalFrontEntry
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.planner.Terms
import io.github.nakanomk.stacklife.workspace.ActionPill
import io.github.nakanomk.stacklife.workspace.SectionTitle
import io.github.nakanomk.stacklife.workspace.Hint
import io.github.nakanomk.stacklife.transitions.CardMetadata
import io.github.nakanomk.stacklife.planner.CourseOccurrenceId
import io.github.nakanomk.stacklife.planner.stableKey
import io.github.nakanomk.stacklife.reminders.ReminderRulesEditor
import io.github.nakanomk.stacklife.planner.OccurrenceKey
import io.github.nakanomk.stacklife.planner.ReminderSubject
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nakanomk.stacklife.planner.CourseWeek
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.today.CalendarPager
import io.github.nakanomk.stacklife.today.CalendarSelection
import io.github.nakanomk.stacklife.today.rememberCalendarSelection
import io.github.nakanomk.stacklife.transitions.rememberRouteLaunch
import io.github.nakanomk.stacklife.transitions.routeOrigin
import java.time.Instant
import java.time.format.DateTimeFormatter

private val courseClock = DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ROOT)
internal fun CourseMeeting.periodLabel() = if (firstPeriod == lastPeriod) uiText({ "Period $firstPeriod" }, { "第 $firstPeriod 节" }) else uiText({ "Periods $firstPeriod–$lastPeriod" }, { "第 $firstPeriod–$lastPeriod 节" })

@Composable
private fun RowScope.WeekPicker(term: TermSpec, selection: CalendarSelection) {
    val page by selection.displayPage
    val week = page + 1
    IconButton(onClick = { selection.request(selection.displayPage.value - 1) }, enabled = week > 1, modifier = Modifier.testTag("course-prev")) {
        Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.courses_previous_week))
    }
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.weight(1f, fill = false)) {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("course-week")) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(term.displayLabel(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.courses_week, week), maxLines = 1, style = MaterialTheme.typography.titleSmall)
                    Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(16.dp))
                }
            }
        }
        PlannerDropdownMenu(expanded, { expanded = false }) {
            for (n in 1..term.weeks) DropdownMenuItem(text = { Text(stringResource(R.string.courses_week, n)) }, onClick = { expanded = false; selection.request(n - 1) })
        }
    }
    IconButton(onClick = { selection.request(selection.displayPage.value + 1) }, enabled = week < term.weeks, modifier = Modifier.testTag("course-next")) {
        Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.courses_next_week))
    }
}

/** Each root owns its editor; precomposed pager neighbors must not open a second dialog. */
@Composable
internal fun courseEditor(owner: String): ManualEditorModel {
    val app = LocalContext.current.applicationContext
    return viewModel(key = "manual-course:$owner") { ManualEditorModel(createSavedStateHandle(), app) }
}

@Composable
internal fun CourseEditorHost(model: WorkspaceViewModel, editor: ManualEditorModel, terms: List<TermSpec>, saved: (TermSpec, Int) -> Unit) {
    val onSaved by rememberUpdatedState(saved)
    editor.form?.let { form ->
        key(form) { ManualCourseSheet(model, form, terms, close = editor::close, saved = { term, week ->
            if (editor.form === form) { editor.close(); onSaved(term, week) }
        }) }
    }
}

@Composable
internal fun CourseHome(
    model: WorkspaceViewModel, catalog: Terms, term: TermSpec?, week: Int?,
    owner: CourseSelection, onSelection: (CourseSelection) -> Unit, onImport: () -> Unit,
    onRemove: (TermSpec, String) -> Unit, onSettings: () -> Unit, currentWeek: (TermSpec) -> Int,
) {
    var listMode by rememberSaveable { mutableStateOf(false) }
    val selection = if (term != null && week != null) key(term.id) {
        rememberCalendarSelection(week - 1, term.weeks) { onSelection(CourseSelection(term.id, it + 1)) }
    } else null
    val editor = courseEditor("timetable")
    // An editor can legitimately create a different term. Redirect after its receipt only
    // if the calendar that launched it still owns the selection (including after rotation).
    var editorOrigin by rememberSaveable(owner) { mutableStateOf<CourseSelection?>(null) }
    fun openEditor(selected: TermSpec?, number: Int, pattern: CoursePattern? = null) {
        editorOrigin = owner
        editor.open(selected, number, pattern)
    }
    val add = { openEditor(term, week ?: 1) }
    val terms = catalog.terms
    CourseEditorHost(model, editor, terms) { saved, number ->
        // This save can change the catalog's fallback term before its receipt arrives.
        // Only a new explicit shell selection or a different editor invalidates ownership.
        if (editorOrigin == owner) {
            onSelection(CourseSelection(saved.id, number))
        }
        editorOrigin = null
    }
    RootPane(contentDrawsTopEdge = term != null && week != null && !listMode, header = {
        RootToolbar(Destination.COURSES, onSettings, leading = {
            if (term != null) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    val sources = catalog.importedSources[term.id].orEmpty()
                    GlassIcon(R.drawable.ic_more_vert, stringResource(R.string.courses_options), { menu = true }, Modifier.testTag("course-options"))
                    PlannerDropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.courses_add)) }, { menu = false; add() }, modifier = Modifier.testTag("course-add"),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_add), null) })
                        DropdownMenuItem({ Text(if (listMode) stringResource(R.string.courses_week_grid) else stringResource(R.string.courses_list)) },
                            { menu = false; listMode = !listMode }, modifier = Modifier.testTag("course-view-toggle"))
                        DropdownMenuItem({ Text(stringResource(R.string.courses_go_current_week)) },
                            { menu = false; selection?.request(currentWeek(term) - 1) }, modifier = Modifier.testTag("course-current-week"),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_today), null) })
                        val importLaunch = rememberRouteLaunch("course-import")
                        DropdownMenuItem({ Text(stringResource(R.string.courses_import)) }, { importLaunch.open { menu = false; onImport() } },
                            modifier = importLaunch.modifier.testTag("course-import"))
                        if (terms.size > 1) {
                            HorizontalDivider()
                            for (choice in terms) DropdownMenuItem({ Text(choice.displayLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis) }, {
                                menu = false; onSelection(CourseSelection(choice.id, currentWeek(choice)))
                            }, enabled = choice.id != term.id)
                        }
                        if (sources.isNotEmpty()) {
                            HorizontalDivider()
                            for (source in sources) {
                                val removal = rememberRouteLaunch("remove-term:${term.id}:$source")
                                DropdownMenuItem({ Text(stringResource(R.string.courses_remove_source, sourceLabel(source))) },
                                    { removal.open { menu = false; onRemove(term, source) } },
                                    modifier = removal.modifier.testTag(if (sources.size == 1) "course-remove" else "course-remove:$source"))
                            }
                        }
                    }
                }
            }
        }) {
            if (term != null && selection != null) WeekPicker(term, selection)
            else TextButton(onClick = onImport, modifier = Modifier.routeOrigin("course-import", CircleShape).testTag("course-import")) { Text(stringResource(R.string.courses_import)) }
        }
    }) { top, bottom ->
        if (term == null || selection == null) {
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
            CalendarPager(selection, Modifier.fillMaxSize()) { index ->
                val query = remember(term.id, index) { PlannerQuery.Week(term.id, index + 1) }
                val data by remember(model, query) { model.week(query) }.collectAsStateWithLifecycle(initialValue = ReadState.Loading)
                CourseWeekPage(model, term, index + 1, data, catalog.importedSources[term.id].orEmpty(), listMode, top, bottom) { selected ->
                    openEditor(term, selected.meeting.week, selected.meeting.pattern())
                }
            }
        }
    }
}

@Composable
private fun CourseWeekPage(model: WorkspaceViewModel, term: TermSpec, number: Int, data: ReadState<CourseWeek>, sources: List<String>,
    listMode: Boolean, top: Dp, bottom: Dp, edit: (ScheduledCourse) -> Unit) {
    // Term metadata and the week have independent Room collectors. Don't relabel an old
    // result with new dates while the matching revision is still on its way.
    val state = if (data is ReadState.Loaded && (data.value.term != term || data.value.week != number)) ReadState.Loading else data
    val week = (state as? ReadState.Loaded)?.value
    // Store an occurrence key, not a list position: after reimport never show a different course.
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDay by rememberSaveable { mutableIntStateOf(0) }
    val chosen = selected?.let { key -> week?.classes?.firstOrNull { it.occurrenceKey() == key } }
    if (week != null && (chosen != null || selectedDay != 0)) {
        // Keep the actual reader's week/query alive if its page scrolls out during handoff.
        val page = LocalPinnableContainer.current
        DisposableEffect(page) {
            val handle = page?.pin()
            onDispose { handle?.release() }
        }
        val day = selectedDay.takeIf { it != 0 }
        val label = day?.let {
            val date = term.firstMonday.plusWeeks(week.week - 1L).plusDays(it - 1L)
            date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(AppLanguage.locale))
        }
        val classes = remember(day, week.classes) { if (day == null) emptyList() else week.classes.filter { it.meeting.day == day } }
        CourseReadPanel(chosen, label, classes, model = model, open = { selected = it.occurrenceKey() },
            edit = { course -> selected = null; selectedDay = 0; edit(course) },
            close = { if (selected != null) selected = null else selectedDay = 0 })
        if (chosen == null && selected != null) SideEffect { selected = null }
    }
    else if (week != null && selected != null) SideEffect { selected = null }
    val open: (ScheduledCourse) -> Unit = { selected = it.occurrenceKey() }
    if (!listMode) {
        WeekGrid(term, number, state, top, bottom, sources, openDay = { selectedDay = it }, open = open)
        return
    }
    if (week == null) {
        ReadPanel(state) {}
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("course-list"),
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
    }
}

internal fun ScheduledCourse.occurrenceKey(): String = CourseOccurrenceId.of(termId, source, meeting).stableKey()

private fun ScheduledCourse.displayZone() = calendarFor(source)?.zone ?: java.time.ZoneId.systemDefault()

@Composable
private fun CourseInfo(course: ScheduledCourse, detail: Boolean = false) {
    val m = course.meeting
    val pattern = stringResource(R.string.courses_date_pattern)
    val locale = AppLanguage.locale
    val zone = course.displayZone()
    val campusDate = remember(pattern, locale, zone) { DateTimeFormatter.ofPattern(pattern, locale).withZone(zone) }
    val clock = remember(zone) { courseClock.withZone(zone) }
    if (detail) SectionTitle(stringResource(R.string.courses_class_time))
    Text(campusDate.format(Instant.ofEpochMilli(m.startMillis)), style = MaterialTheme.typography.bodyMedium)
    CardMetadata {
        Text("${clock.format(Instant.ofEpochMilli(m.startMillis))}–${clock.format(Instant.ofEpochMilli(m.endMillis))}")
        Text(m.periodLabel())
        if (detail) Text(zone.id)
    }
    if (m.room.isNotBlank()) {
        if (detail) SectionTitle(stringResource(R.string.course_detail_location))
        Text(m.room, style = MaterialTheme.typography.bodyMedium)
    }
    if (course.conflicts.isNotEmpty()) Hint(stringResource(R.string.courses_conflicts, course.conflicts.joinToString(stringResource(R.string.courses_list_separator))))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseReadPanel(course: ScheduledCourse?, dayLabel: String?, dayCourses: List<ScheduledCourse>,
    model: WorkspaceViewModel? = null, open: ((ScheduledCourse) -> Unit)? = null, edit: ((ScheduledCourse) -> Unit)? = null, close: () -> Unit) {
    // Material sheets have a separate window. Preserve the occurrence selection, but
    // don't let the retained sheet paint over or intercept Back from its child route.
    val state = rememberSaveableStateHolder()
    if (LocalFrontEntry.current) state.SaveableStateProvider("reader") {
        CourseReadingSheet(course, dayLabel, dayCourses, model, open, edit, close)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseReadingSheet(course: ScheduledCourse?, dayLabel: String?, dayCourses: List<ScheduledCourse>,
    model: WorkspaceViewModel?, open: ((ScheduledCourse) -> Unit)?, edit: ((ScheduledCourse) -> Unit)?, close: () -> Unit) {
    val origins = remember { PaneOrigins() }
    val nested = course != null && dayLabel != null
    val dayList = androidx.compose.foundation.lazy.rememberLazyListState()
    PlannerModalBottomSheet(close, nested = nested, onNestedBack = close) {
        val back = LocalPanelBack.current ?: close
        PaneMotion<CourseReading>(course?.let { CourseReading.Detail(it) } ?: CourseReading.Day, origins,
            depth = { if (it == CourseReading.Day) 0 else 1 }, originKey = { if (it is CourseReading.Detail) it.course.occurrenceKey() else "day" },
            parent = CourseReading.Day.takeIf { nested }, onBack = close, modifier = Modifier.fillMaxSize()) { shown ->
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp)
                .then(if (shown is CourseReading.Detail) Modifier.testTag("course-detail") else Modifier)) {
                if (shown is CourseReading.Detail) {
                    val detail = shown.course
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(detail.meeting.title, style = MaterialTheme.typography.titleLarge)
                        Hint(sourceLabel(detail.source))
                        CourseInfo(detail, detail = true)
                        AddOccurrenceCountdown(detail.meeting.title, detail.meeting.startMillis, detail.displayZone())
                        val reader = requireNotNull(model)
                        val weeks by remember(reader, detail) { reader.courseWeeks(PlannerQuery.CourseWeeks(
                            detail.termId, detail.source, detail.meeting.pattern())) }.collectAsStateWithLifecycle(initialValue = ReadState.Loading)
                        ReadPanel(weeks) { repeat ->
                            SectionTitle(stringResource(R.string.course_detail_repeat))
                            Text(stringResource(R.string.courses_weeks_list, repeat.weeks.sorted().joinToString(stringResource(R.string.courses_list_separator))))
                        }
                        SectionTitle(stringResource(R.string.course_detail_reminders))
                        val subject = remember(detail) { ReminderSubject.Occurrence(OccurrenceKey.Course(
                            CourseOccurrenceId.of(detail.termId, detail.source, detail.meeting))) }
                        ReminderRulesEditor(reader, subject)
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
                            ListItem(modifier = Modifier.fillMaxWidth().paneOrigin(origins, row.occurrenceKey()).clickable { open?.invoke(row) },
                                supportingContent = { CardMetadata {
                                    Text(row.meeting.periodLabel())
                                    if (row.meeting.room.isNotBlank()) Text(row.meeting.room)
                                } }) { Text(row.meeting.title) }
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
