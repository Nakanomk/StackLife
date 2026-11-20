package io.github.nakanomk.stacklife.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.RootPane
import io.github.nakanomk.stacklife.RootToolbar
import io.github.nakanomk.stacklife.GlassIcon
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.GlassBacking
import io.github.nakanomk.stacklife.AddTaskFab
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.planner.NavStyle
import io.github.nakanomk.stacklife.planner.DayPlan
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.R
import androidx.compose.ui.res.painterResource
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.workspace.WorkspaceActions
import io.github.nakanomk.stacklife.workspace.rememberSubmission
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.first

private val dateHeading = DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINESE)
private val firstDay = LocalDate.of(1, 1, 1)
private val lastDay = LocalDate.of(9999, 12, 31)

@Composable
internal fun TodayRoute(
    model: WorkspaceViewModel, date: LocalDate, zone: ZoneId, onDate: (LocalDate) -> Unit,
    onEdit: (SlotEditorKey) -> Unit, onSettings: () -> Unit, revealStart: Long?, onRevealed: () -> Unit, onAdd: () -> Unit,
) {
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    var trayOpen by rememberSaveable { mutableStateOf(true) }
    if (choosingDate) DateChooser(date, { choosingDate = false; onDate(it) }, { choosingDate = false })
    RootPane(header = {
        DayHeader(date, { choosingDate = true }, onDate, { onDate(LocalDate.now(zone)) }, onSettings)
    }) { top, bottom ->
        CalendarPager((date.toEpochDay() - firstDay.toEpochDay()).toInt(),
            (lastDay.toEpochDay() - firstDay.toEpochDay() + 1).toInt(),
            onSelect = { onDate(firstDay.plusDays(it.toLong())) }, modifier = Modifier.fillMaxSize()) { index ->
            val query = remember(index, zone) { PlannerQuery.Day(firstDay.plusDays(index.toLong()), zone) }
            val state by remember(model, query) { model.day(query) }.collectAsStateWithLifecycle(initialValue = ReadState.Loading)
            ReadPanel(state) { plan ->
                DayBody(plan, model, model::checkSlot, { id -> plan.editorFor(id)?.let(onEdit) }, onAdd,
                    revealStart.takeIf { query.date == date }, onRevealed, top, bottom, trayOpen, { trayOpen = !trayOpen })
            }
        }
    }
}

@Composable
private fun DayBody(
    plan: DayPlan, actions: WorkspaceActions, checkSlot: suspend (PlannerCommand.SlotSet) -> String?,
    onEdit: (String) -> Unit, onAdd: () -> Unit, revealStart: Long?, onRevealed: () -> Unit,
    top: Dp, clearance: Dp, trayOpen: Boolean, toggleTray: () -> Unit,
) {
    val submission = rememberSubmission(actions)
    val density = LocalDensity.current
    val type = MaterialTheme.typography
    val minCard = with(density) {
        (type.titleSmall.lineHeight.toDp() + type.labelMedium.lineHeight.toDp() * 2 + 8.dp).coerceAtLeast(48.dp).roundToPx()
    }
    val minCourse = minCard + with(density) { type.bodySmall.lineHeight.toDp().roundToPx() }
    val minutePx = with(density) { 2.dp.toPx() }
    val scale = remember(plan.day, plan.blocks, plan.courses, plan.conflictingTaskIds, minutePx, minCard, minCourse) {
        DayAxisScale(plan, minutePx, minCard, minCourse)
    }
    val initial = remember {
        val now = System.currentTimeMillis()
        val anchor = when {
            now in plan.day.startMillis until plan.day.endMillis -> now - 60 * MINUTE_MS
            scale.groups.isNotEmpty() -> scale.groups.first().start - 30 * MINUTE_MS
            else -> plan.day.date.atTime(8, 0).atZone(plan.day.zone).toInstant().toEpochMilli()
        }
        scale.yAt(anchor)
    }
    val scroll = rememberScrollState(initial)
    LaunchedEffect(revealStart, scale) {
        val start = revealStart ?: return@LaunchedEffect
        if (plan.blocks.any { it.startMillis == start }) {
            snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
            scroll.scrollTo((scale.yAt(start) - with(density) { 24.dp.roundToPx() }).coerceAtLeast(0))
            onRevealed()
        }
    }
    val drag = remember(scale, scroll) { AxisDrag(scale, scroll) }
    val send: (PlannerCommand.SlotSet) -> Unit = { submission.send(it) }
    DragEffects(drag, checkSlot)
    val floating = LocalAppearance.current.navStyle == NavStyle.FLOATING
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= 480.dp && maxWidth > maxHeight && trayOpen
        val overlay = if (floating && !sideBySide) clearance else 0.dp
        val reserve = clearance - overlay
        val usable = (maxHeight - top - clearance).coerceAtLeast(0.dp)
        val trayLimit = usable * .46f + overlay
        val axis: @Composable (Modifier) -> Unit = {
            GlassBacking(it, background = {
                DayAxis(drag, plan.revision, !submission.busy, onEdit, send, Modifier.fillMaxSize(), top)
            }, foreground = { AddTaskFab(onAdd) })
        }
        val tray: @Composable (Modifier) -> Unit = {
            QueueTray(plan.tray, plan.revision, drag, trayOpen, toggleTray,
                !submission.busy, onEdit, send, it, overlay)
        }
        if (sideBySide) {
            Row(Modifier.fillMaxSize().padding(bottom = reserve)) {
                tray(Modifier.weight(1f).fillMaxHeight().padding(top = top))
                axis(Modifier.weight(1.6f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize().padding(bottom = reserve)) {
                axis(Modifier.fillMaxWidth().weight(1f))
                tray(Modifier.fillMaxWidth().heightIn(max = trayLimit))
            }
        }
        drag.dropNotice?.let { message ->
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(start = 4.dp, end = 4.dp, bottom = clearance),
                dismissAction = { TextButton(onClick = { drag.dropNotice = null }) { Text("知道了") } }) { Text(message) }
        }
    }
}

@Composable
private fun DayHeader(
    date: LocalDate, onPick: () -> Unit, onDate: (LocalDate) -> Unit, onToday: () -> Unit, onSettings: () -> Unit,
) {
    val short = LocalDensity.current.fontScale >= 1.35f
    RootToolbar(onSettings, leading = { GlassIcon(R.drawable.ic_today, "回到今天", onToday) }) {
        IconButton(onClick = { onDate(date.minusDays(1)) }, enabled = date > firstDay) {
            Icon(painterResource(R.drawable.ic_chevron_left), "前一天")
        }
        TextButton(onClick = onPick, contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.weight(1f, fill = false).testTag("day-date")
                .semantics { contentDescription = "选择日期，${date.year}年${date.format(dateHeading)}" }) {
            Text(if (short) "${date.monthValue}/${date.dayOfMonth}" else date.format(dateHeading),
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { onDate(date.plusDays(1)) }, enabled = date < lastDay) {
            Icon(painterResource(R.drawable.ic_chevron_right), "后一天")
        }
    }
}

@Composable
private fun QueueTray(
    tasks: List<QueueTask>, revision: Long, drag: AxisDrag, expanded: Boolean, toggle: () -> Unit,
    enabled: Boolean, onEdit: (String) -> Unit, send: (PlannerCommand.SlotSet) -> Unit, modifier: Modifier, bottomClearance: Dp,
) {
    val appearance = LocalAppearance.current
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "收起待排队列" else "展开待排队列",
                role = Role.Button, onClick = toggle).semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("待排队列 · ${tasks.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                    if (expanded) "收起" else "展开", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                LazyColumn(Modifier.fillMaxWidth().testTag("day-tray"), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = bottomClearance + 6.dp),
                    verticalArrangement = Arrangement.spacedBy(if (appearance.compact) 2.dp else 4.dp)) {
                    if (tasks.isEmpty()) item {
                        Text("队列中没有待排任务", Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    items(tasks, key = { it.id }) { task ->
                        Row(Modifier.fillMaxWidth().testTag("tray:${task.id}").clip(MaterialTheme.shapes.medium)
                            .slotDrag(SlotSource(task.id, task.minutes), drag, revision, enabled, send)
                            .clickable(enabled, onClickLabel = "安排时间", role = Role.Button) { onEdit(task.id) }
                            .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (appearance.showNotes && task.notes.isNotBlank())
                                    Text(task.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (appearance.showDuration) "${task.minutes} 分钟 · 长按拖动" else "长按拖动",
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(painterResource(R.drawable.ic_schedule), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (bottomClearance > 0.dp) {
                Spacer(Modifier.height(bottomClearance))
            }
        }
    }
}
