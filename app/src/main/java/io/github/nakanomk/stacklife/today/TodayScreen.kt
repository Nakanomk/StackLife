package io.github.nakanomk.stacklife.today

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import io.github.nakanomk.stacklife.appearance.PlannerDropdownMenu
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
import io.github.nakanomk.stacklife.i18n.AppLanguage
import kotlinx.coroutines.flow.first

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
    var eventView by rememberSaveable { mutableStateOf(false) }
    if (choosingDate) DateChooser(date, { choosingDate = false; onDate(it) }, { choosingDate = false })
    RootPane(header = {
        DayHeader(date, { choosingDate = true }, onDate, { onDate(LocalDate.now(zone)) }, onSettings, eventView, { eventView = it })
    }) { top, bottom ->
        CalendarPager((date.toEpochDay() - firstDay.toEpochDay()).toInt(),
            (lastDay.toEpochDay() - firstDay.toEpochDay() + 1).toInt(),
            onSelect = { onDate(firstDay.plusDays(it.toLong())) }, modifier = Modifier.fillMaxSize()) { index ->
            val query = remember(index, zone) { PlannerQuery.Day(firstDay.plusDays(index.toLong()), zone) }
            val state by remember(model, query) { model.day(query) }.collectAsStateWithLifecycle(initialValue = ReadState.Loading)
            ReadPanel(state) { plan ->
                val timeEditor: (String) -> Unit = { id -> plan.editorFor(id)?.let(onEdit) }
                val target = revealStart.takeIf { query.date == date }
                val entries = remember(plan.blocks, plan.courses, plan.conflictingTaskIds) { plan.chronologicalEntries() }
                rememberSaveableStateHolder().SaveableStateProvider(eventView) {
                    if (eventView) EventDay(plan, entries, model, timeEditor, onAdd, target, onRevealed,
                        top, bottom, trayOpen, { trayOpen = !trayOpen })
                    else DayBody(plan, entries, model, model::checkSlot, timeEditor, onAdd,
                        target, onRevealed, top, bottom, trayOpen, { trayOpen = !trayOpen })
                }
            }
        }
    }
}

@Composable
private fun DayBody(
    plan: DayPlan, entries: List<AxisEntry>, actions: WorkspaceActions, checkSlot: suspend (PlannerCommand.SlotSet) -> String?,
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
    val scale = remember(plan.day, entries, minutePx, minCard, minCourse) {
        DayAxisScale(plan.day, entries, minutePx, minCard, minCourse)
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
            DayWithTray(plan, drag, !submission.busy, onEdit, onAdd, top, clearance, trayOpen, toggleTray,
                pickup = { task -> Modifier.slotDrag(SlotSource(task.id, task.minutes), drag, plan.revision, !submission.busy && trayOpen, send) }) { spine, _ ->
                DayAxis(drag, plan.revision, !submission.busy, onEdit, send, Modifier.fillMaxSize(), top, spine, endInGutter = trayOpen)
            }
        }
        drag.dropNotice?.let { message ->
            CompositionLocalProvider(LocalCapsuleBackdrop provides noticeSource) {
                StackLifeSurface(CapsuleScene.PANEL, Modifier.align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = clearance + 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    MaterialTheme.colorScheme.surfaceContainerHigh, elevation = 2.dp, shape = RoundedCornerShape(22.dp)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { drag.dropNotice = null }) { Text(stringResource(R.string.today_dismiss_notice)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(
    date: LocalDate, onPick: () -> Unit, onDate: (LocalDate) -> Unit, onToday: () -> Unit, onSettings: () -> Unit,
    events: Boolean, setEvents: (Boolean) -> Unit,
) {
    val short = LocalDensity.current.fontScale >= 1.35f
    val locale = AppLanguage.locale
    val pattern = stringResource(R.string.today_date_pattern)
    val fullPattern = stringResource(R.string.today_full_date_pattern)
    val dateHeading = remember(pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale) }
    val fullDate = remember(fullPattern, locale) { DateTimeFormatter.ofPattern(fullPattern, locale) }
    val pickDate = stringResource(R.string.today_choose_date, date.format(fullDate))
    var options by remember { mutableStateOf(false) }
    RootToolbar(onSettings, leading = {
        Box {
            val label = if (events) stringResource(R.string.today_events) else stringResource(R.string.today_time)
            val switchView = stringResource(R.string.today_switch_view)
            StackLifeSurface(CapsuleScene.CHROME, Modifier.size(48.dp), MaterialTheme.colorScheme.surfaceContainer, elevation = 1.dp) {
                IconButton(onClick = { options = true }, modifier = Modifier.fillMaxSize().testTag("day-view").semantics {
                    contentDescription = switchView; stateDescription = label
                }) {
                    Icon(painterResource(if (events) R.drawable.ic_view_agenda else R.drawable.ic_schedule), null)
                }
            }
            PlannerDropdownMenu(options, { options = false }) {
                for (mode in listOf(false, true)) {
                    val name = if (mode) stringResource(R.string.today_events) else stringResource(R.string.today_time)
                    DropdownMenuItem(text = { Text(name) },
                        leadingIcon = { Icon(painterResource(if (mode) R.drawable.ic_view_agenda else R.drawable.ic_schedule), null) },
                        trailingIcon = { RadioButton(events == mode, onClick = null) },
                        modifier = Modifier.testTag(if (mode) "day-view-events" else "day-view-time"),
                        onClick = { setEvents(mode); options = false })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.today_go_today)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_today), null) },
                    onClick = { options = false; onToday() })
            }
        }
    }) {
        IconButton(onClick = { onDate(date.minusDays(1)) }, enabled = date > firstDay) {
            Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.today_previous_day))
        }
        TextButton(onClick = onPick, contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.weight(1f, fill = false).testTag("day-date")
                .semantics { contentDescription = pickDate }) {
            Text(if (short) "${date.monthValue}/${date.dayOfMonth}" else date.format(dateHeading),
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { onDate(date.plusDays(1)) }, enabled = date < lastDay) {
            Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.today_next_day))
        }
    }
}
