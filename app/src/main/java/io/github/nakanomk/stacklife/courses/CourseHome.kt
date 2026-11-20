package io.github.nakanomk.stacklife.courses

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import io.github.nakanomk.stacklife.planner.CourseWeek
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.today.CalendarPager
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val campusClock = DateTimeFormatter.ofPattern("HH:mm").withZone(campusZone)
private val campusDate = DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINESE).withZone(campusZone)
private fun CourseMeeting.timeLabel() = "${campusClock.format(Instant.ofEpochMilli(startMillis))}–${campusClock.format(Instant.ofEpochMilli(endMillis))}"
internal fun CourseMeeting.periodLabel() = if (firstPeriod == lastPeriod) "第 $firstPeriod 节" else "第 $firstPeriod–$lastPeriod 节"

@Composable
private fun RowScope.WeekPicker(term: TermSpec, week: Int, onWeek: (Int) -> Unit) {
    IconButton(onClick = { onWeek(week - 1) }, enabled = week > 1, modifier = Modifier.testTag("course-prev")) {
        Icon(painterResource(R.drawable.ic_chevron_left), "上周")
    }
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.weight(1f, fill = false)) {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("course-week")) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(term.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("第 $week 周", maxLines = 1, style = MaterialTheme.typography.titleSmall)
                    Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(16.dp))
                }
            }
        }
        PlannerDropdownMenu(expanded, { expanded = false }) {
            for (n in 1..term.weeks) DropdownMenuItem(text = { Text("第 $n 周") }, onClick = { expanded = false; onWeek(n) })
        }
    }
    IconButton(onClick = { onWeek(week + 1) }, enabled = week < term.weeks, modifier = Modifier.testTag("course-next")) {
        Icon(painterResource(R.drawable.ic_chevron_right), "下周")
    }
}

@Composable
internal fun CourseHome(
    model: WorkspaceViewModel, terms: List<TermSpec>, term: TermSpec?, week: Int?,
    onTerm: (TermSpec) -> Unit, onWeek: (Int) -> Unit, onImport: () -> Unit,
    onRemove: (TermSpec) -> Unit, onSettings: () -> Unit,
) {
    var listMode by rememberSaveable { mutableStateOf(false) }
    RootPane(header = {
        RootToolbar(onSettings, leading = {
            if (term != null) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    GlassIcon(R.drawable.ic_more_vert, "课表选项", { menu = true }, Modifier.testTag("course-options"))
                    PlannerDropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(if (listMode) "周网格" else "课程列表") },
                            { menu = false; listMode = !listMode }, modifier = Modifier.testTag("course-view-toggle"))
                        DropdownMenuItem({ Text("导入课表") }, { menu = false; onImport() }, modifier = Modifier.testTag("course-import"))
                        if (terms.size > 1) {
                            HorizontalDivider()
                            for (choice in terms) DropdownMenuItem({ Text(choice.id) }, { menu = false; onTerm(choice) },
                                enabled = choice.id != term.id)
                        }
                        HorizontalDivider()
                        DropdownMenuItem({ Text("移除当前学期") }, { menu = false; onRemove(term) }, modifier = Modifier.testTag("course-remove"))
                    }
                }
            }
        }) {
            if (term != null && week != null) WeekPicker(term, week, onWeek)
            else TextButton(onClick = onImport, modifier = Modifier.testTag("course-import")) { Text("导入课表") }
        }
    }) { top, bottom ->
        if (term == null || week == null) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = top + 16.dp, bottom = bottom)) {
                Text("还没有课表", style = MaterialTheme.typography.titleMedium)
                Text("从华科 HUB 登录提取，或选择自己导出的周数组文件。确认预览后，课程也会出现在今天。",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else key(term.id) {
            CalendarPager(week - 1, term.weeks, { onWeek(it + 1) }, Modifier.fillMaxSize()) { index ->
                val query = remember(term.id, index) { PlannerQuery.Week(term.id, index + 1) }
                val data by remember(model, query) { model.week(query) }.collectAsStateWithLifecycle(initialValue = ReadState.Loading)
                ReadPanel(data) { result ->
                    if (result.term != null) CourseWeekPage(result.term, result, listMode, top, bottom)
                }
            }
        }
    }
}

@Composable
private fun CourseWeekPage(term: TermSpec, week: CourseWeek, listMode: Boolean, top: Dp, bottom: Dp) {
    // Store an occurrence key, not a list position: after reimport never show a different course.
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val chosen = selected?.let { key -> week.classes.firstOrNull { it.meeting.occurrenceKey() == key } }
    if (chosen != null) CourseDetails(chosen) { selected = null }
    else if (selected != null) SideEffect { selected = null }
    val open: (ScheduledCourse) -> Unit = { selected = it.meeting.occurrenceKey() }
    if (listMode) LazyColumn(Modifier.fillMaxSize().testTag("course-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 8.dp, bottom = bottom + 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (week.classes.isEmpty()) item { Text("本周没有课程") }
        itemsIndexed(week.classes, key = { _, c -> c.meeting.occurrenceKey() }) { i, course ->
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

private fun CourseMeeting.occurrenceKey(): String =
    "$day/$firstPeriod/$lastPeriod/${title.length}:$title$room"

@Composable
private fun CourseInfo(course: ScheduledCourse) {
    val m = course.meeting
    Text("第 ${m.week} 周 · ${campusDate.format(Instant.ofEpochMilli(m.startMillis))} · ${m.periodLabel()}", style = MaterialTheme.typography.bodyMedium)
    Text("${m.timeLabel()} · 北京时间", style = MaterialTheme.typography.bodyMedium)
    Text("教室：${m.room.ifBlank { "未提供" }}", style = MaterialTheme.typography.bodyMedium)
    if (course.conflicts.isNotEmpty()) Text("时段冲突：${course.conflicts.joinToString("、")}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun CourseDetails(course: ScheduledCourse, close: () -> Unit) {
    PlannerAlertDialog(modifier = Modifier.testTag("course-detail"), onDismissRequest = close, title = { Text("固定课程") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(course.meeting.title, style = MaterialTheme.typography.titleLarge)
                Text(course.termId, style = MaterialTheme.typography.labelLarge)
                CourseInfo(course)
                AssistantComposer(AssistantPage.Week(course.termId, course.meeting.week))
            }
        }, confirmButton = { TextButton(onClick = close) { Text("关闭") } })
}
