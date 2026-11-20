package io.github.nakanomk.stacklife.courses

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.tobiasschuerg.weekview.EventConfig
import de.tobiasschuerg.weekview.WeekLayout
import de.tobiasschuerg.weekview.WeekView
import de.tobiasschuerg.weekview.WeekViewActions
import de.tobiasschuerg.weekview.WeekViewConfig
import de.tobiasschuerg.weekview.model.Event
import de.tobiasschuerg.weekview.model.LocalDateRange
import de.tobiasschuerg.weekview.model.Period
import de.tobiasschuerg.weekview.model.TimeSpan
import de.tobiasschuerg.weekview.model.WeekData
import io.github.nakanomk.stacklife.DockOverlay
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.planner.CourseWeek
import io.github.nakanomk.stacklife.workspace.Page
import io.github.nakanomk.stacklife.workspace.PageTitle
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val campusClock = DateTimeFormatter.ofPattern("HH:mm").withZone(campusZone)
private val campusDate = DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINESE).withZone(campusZone)
private fun CourseMeeting.timeLabel() = "${campusClock.format(Instant.ofEpochMilli(startMillis))}–${campusClock.format(Instant.ofEpochMilli(endMillis))}"
private fun CourseMeeting.periodLabel() = if (firstPeriod == lastPeriod) "第 $firstPeriod 节" else "第 $firstPeriod–$lastPeriod 节"

@Composable
internal fun CourseHome(
    terms: List<TermSpec>, week: CourseWeek?, onTerm: (TermSpec) -> Unit, onWeek: (Int) -> Unit,
    onImport: () -> Unit, onRemove: (TermSpec) -> Unit, onSettings: () -> Unit, navigationBar: @Composable () -> Unit,
) {
    var listMode by rememberSaveable { mutableStateOf(false) }
    val term = week?.term
    var detail by rememberSaveable(week?.classes, term?.id, week?.week) { mutableIntStateOf(-1) }
    week?.classes?.getOrNull(detail)?.let { CourseDetails(it) { detail = -1 } }
    Page {
        PageTitle("课表", settings = onSettings)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (terms.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }, modifier = Modifier.widthIn(max = 180.dp).testTag("course-term")) {
                            Text(term?.id ?: "选择学期", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded, { expanded = false }) {
                            for (choice in terms) DropdownMenuItem(text = { Text(choice.id) }, onClick = { expanded = false; onTerm(choice) })
                        }
                    }
                }
                TextButton(onClick = onImport, modifier = Modifier.testTag("course-import")) { Text("导入课表") }
                if (term != null) TextButton(onClick = { onRemove(term) }, modifier = Modifier.heightIn(min = 48.dp).testTag("course-remove")) { Text("移除学期") }
            }
            if (term != null && week != null) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onWeek(week.week - 1) }, enabled = week.week > 1,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("course-prev")) { Text("上周") }
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("course-week")) { Text("第 ${week.week} 周") }
                        DropdownMenu(expanded, { expanded = false }) {
                            for (n in 1..term.weeks) DropdownMenuItem(text = { Text("第 $n 周") }, onClick = { expanded = false; onWeek(n) })
                        }
                    }
                    TextButton(onClick = { onWeek(week.week + 1) }, enabled = week.week < term.weeks,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("course-next")) { Text("下周") }
                    TextButton(onClick = { listMode = !listMode }, modifier = Modifier.heightIn(min = 48.dp).testTag("course-view-toggle")) {
                        Text(if (listMode) "周网格" else "课程列表")
                    }
                }
            }
        }
        DockOverlay(navigationBar, if (term != null && week != null) AssistantPage.Week(term.id, week.week) else AssistantPage.Terms) { bottom ->
            when {
                terms.isEmpty() || term == null || week == null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottom)) {
                    Text(if (terms.isEmpty()) "还没有课表" else "请选择学期", style = MaterialTheme.typography.titleMedium)
                    if (terms.isEmpty()) Text("从华科 HUB 登录提取，或选择自己导出的周数组文件。确认预览后，课程也会出现在今天。",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                listMode -> LazyColumn(Modifier.fillMaxSize().testTag("course-list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (week.classes.isEmpty()) item { Text("本周没有课程") }
                    itemsIndexed(week.classes) { i, course ->
                        Surface(onClick = { detail = i }, modifier = Modifier.fillMaxWidth().testTag("week-course:$i"),
                            color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(course.meeting.title, style = MaterialTheme.typography.titleMedium)
                                CourseInfo(course)
                            }
                        }
                    }
                }
                else -> Column(Modifier.fillMaxSize().padding(bottom = bottom, start = 4.dp, end = 4.dp)) {
                    if (week.classes.isEmpty()) Text("本周没有课程", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    WeekGrid(term, week) { detail = it }
                }
            }
        }
    }
}

@Composable
private fun WeekGrid(term: TermSpec, week: CourseWeek, show: (Int) -> Unit) {
    val monday = term.firstMonday.plusWeeks(week.week - 1L)
    val mixed = term.switchDate?.let { it > monday && it <= monday.plusDays(6) } == true
    val periods = remember(term, monday) { term.periodsOn(monday) }
    val layout = remember(periods, mixed) {
        WeekLayout.Periods(periods.mapIndexed { i, p -> Period("${i + 1}", TimeSpan(p.start, p.end)) }, showTimes = !mixed)
    }
    val colors = MaterialTheme.colorScheme
    val data = remember(week, monday, periods, colors) {
        val events = week.classes.mapIndexed { i, course ->
            val m = course.meeting
            // WeekView takes one period axis for all seven days. Place by period index, not a
            // winter clock time against summer rows. The list and details always use real instants.
            Event.Single(i.toString(), monday.plusDays(m.day - 1L), m.title, m.title, subTitle = m.room,
                timeSpan = TimeSpan(periods[m.firstPeriod - 1].start, periods[m.lastPeriod - 1].end),
                backgroundColor = if (course.conflicts.isEmpty()) colors.tertiaryContainer else colors.errorContainer,
                textColor = if (course.conflicts.isEmpty()) colors.onTertiaryContainer else colors.onErrorContainer)
        }
        WeekData(LocalDateRange(monday, monday.plusDays(6)), periods.first().start, periods.last().end, events)
    }
    // The library announces each event's grid span regardless of the visible time-label flags.
    // Don't announce synthetic clock times in a changeover week; the adjacent list is accessible.
    val semantics = if (mixed) Modifier.clearAndSetSemantics { contentDescription = "本周切换作息，周网格按节次排列。请使用课程列表朗读实际上课时间。" } else Modifier
    WeekView(data, WeekViewConfig(showCurrentTimeIndicator = false, locale = Locale.CHINESE), modifier = Modifier.fillMaxSize().testTag("week-grid").then(semantics),
        layout = layout, eventConfig = EventConfig(showTimeStart = false, showTimeEnd = false),
        actions = WeekViewActions(onEventClick = { show(it.id.toInt()) }))
}

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
    AlertDialog(modifier = Modifier.testTag("course-detail"), onDismissRequest = close, title = { Text("固定课程") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(course.meeting.title, style = MaterialTheme.typography.titleLarge)
                Text(course.termId, style = MaterialTheme.typography.labelLarge)
                CourseInfo(course)
                AssistantComposer(AssistantPage.Week(course.termId, course.meeting.week))
            }
        }, confirmButton = { TextButton(onClick = close) { Text("关闭") } })
}
