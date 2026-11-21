package io.github.nakanomk.stacklife.today

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.RootPane
import io.github.nakanomk.stacklife.RootToolbar
import io.github.nakanomk.stacklife.GlassIcon
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.CapsuleScene
import io.github.nakanomk.stacklife.appearance.LocalCapsuleBackdrop
import io.github.nakanomk.stacklife.appearance.StackLifeSurface
import io.github.nakanomk.stacklife.appearance.plannerBackdrop
import io.github.nakanomk.stacklife.appearance.rememberPlannerBackdrop
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.planner.DayPlan
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.R
import androidx.compose.ui.res.painterResource
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
    val floatingTray = LocalAppearance.current.floatingDayTray
    var trayOpen by rememberSaveable(floatingTray) { mutableStateOf(true) }
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
    Box(Modifier.fillMaxSize()) {
        val noticeSource = rememberPlannerBackdrop(drag.dropNotice != null)
        Box(Modifier.fillMaxSize().plannerBackdrop(noticeSource)) {
            DayWithTray(plan, drag, !submission.busy, onEdit, onAdd, send, top, clearance, trayOpen, toggleTray)
        }
        drag.dropNotice?.let { message ->
            CompositionLocalProvider(LocalCapsuleBackdrop provides noticeSource) {
                StackLifeSurface(CapsuleScene.PANEL, Modifier.align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = clearance + 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    MaterialTheme.colorScheme.surfaceContainerHigh, elevation = 2.dp, shape = RoundedCornerShape(22.dp)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { drag.dropNotice = null }) { Text("知道了") }
                    }
                }
            }
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
