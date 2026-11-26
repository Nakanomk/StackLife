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
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.R
import androidx.compose.ui.res.painterResource
import io.github.nakanomk.stacklife.workspace.WorkspaceActions
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.workspace.rememberSubmission
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import io.github.nakanomk.stacklife.i18n.AppLanguage
import io.github.nakanomk.stacklife.appearance.DateWheelDialog
import kotlinx.coroutines.flow.first

private val firstDay = LocalDate.of(1, 1, 1)
private val lastDay = LocalDate.of(9999, 12, 31)

@Composable
internal fun TodayRoute(
    model: WorkspaceViewModel, date: LocalDate, zone: ZoneId, onDate: (LocalDate) -> Unit,
    onEdit: (SlotEditorKey) -> Unit, onSettings: () -> Unit, revealKey: OccurrenceKey?, onRevealed: (OccurrenceKey) -> Unit, onAdd: () -> Unit,
    todayVisit: Int? = 0,
    currentDate: LocalDate = LocalDate.now(zone),
) {
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    val floatingTray = LocalAppearance.current.floatingDayTray
    var trayOpen by rememberSaveable(floatingTray) { mutableStateOf(true) }
    var eventView by rememberSaveable { mutableStateOf(false) }
    // Save the consumed visit, not the clock: rotation, plan updates and minute ticks are not visits.
    var positionedVisit by rememberSaveable(date, zone.id) { mutableStateOf<Int?>(null) }
    val currentVisit by rememberUpdatedState(todayVisit)
    if (todayVisit == null) SideEffect { positionedVisit = null }
    val editor = courseEditor("today")
    if (editor.form != null) {
        val terms by remember(model) { model.terms() }.collectAsStateWithLifecycle(initialValue = ReadState.Loading)
        ReadPanel(terms) { all -> CourseEditorHost(model, editor, all.terms) { _, _ -> } }
    }
    fun pageOf(day: LocalDate) = (day.toEpochDay() - firstDay.toEpochDay()).toInt()
    val page = pageOf(date)
    val selection = rememberCalendarSelection(page, pageOf(lastDay) + 1, externalTarget = revealKey) { onDate(firstDay.plusDays(it.toLong())) }
    val choose: (LocalDate) -> Unit = {
        if (it == LocalDate.now(zone)) positionedVisit = null
        selection.request(pageOf(it))
    }
    // At rest both the caption and its accessibility label come from the shell's actual date.
    // Only a live calendar gesture/request supplies a temporary heading.
    val shown = selection.previewPage.value?.let { firstDay.plusDays(it.toLong()) } ?: date
    if (choosingDate) DateWheelDialog(shown, { choosingDate = false; choose(it) }, { choosingDate = false })
    RootPane(header = {
        DayHeader(shown, { choosingDate = true }, { selection.request(selection.displayPage.value + it) },
            { choose(LocalDate.now(zone)) }, onSettings, eventView, { eventView = it })
    }) { top, bottom ->
        CalendarPager(selection, Modifier.fillMaxSize()) { index ->
            val query = remember(index, zone) { PlannerQuery.Day(firstDay.plusDays(index.toLong()), zone) }
            val state by remember(model, query) { model.day(query) }.collectAsStateWithLifecycle(initialValue = ReadState.Loading)
            ReadPanel(state) { plan ->
                val editTray: (String) -> Unit = { id -> plan.tray.find { it.id == id }?.let {
                    onEdit(SlotEditorKey(it.id, it.title, query.date.toEpochDay(), zone.id, it.minutes, plan.revision))
                } }
                val editEvent: (AgendaOccurrence) -> Unit = { item ->
                    val key = item.key as OccurrenceKey.Schedule
                    onEdit(SlotEditorKey(key.scheduleId, item.title, query.date.toEpochDay(), zone.id,
                        ((item.endMillis - item.startMillis) / MINUTE_MS).coerceIn(if (item.point) 0L else 1L, Int.MAX_VALUE.toLong()).toInt(), plan.revision, key, item.role))
                }
                var reading by rememberSaveable(index) { mutableStateOf<String?>(null) }
                val openCourse: (ScheduledCourse) -> Unit = { reading = it.occurrenceKey() }
                val selected = reading?.let { id -> plan.occurrences.firstOrNull { it.course?.occurrenceKey() == id }?.course }
                if (selected != null) {
                    val terms by remember(model) { model.terms() }.collectAsStateWithLifecycle(initialValue = ReadState.Loading)
                    ReadPanel(terms) { all ->
                        val term = all.terms.firstOrNull { it.id == selected.termId }
                        CourseReadPanel(selected, null, emptyList(), model = model, edit = term?.let { t -> { c ->
                            reading = null; editor.open(t, c.meeting.week, c.meeting.pattern())
                        } }, close = { reading = null })
                    }
                } else if (reading != null) SideEffect { reading = null }
                val settled = todayVisit != null && index == page && !selection.pager.isScrollInProgress && selection.pager.currentPage == index
                val target = revealKey?.takeIf { settled }
                val positionNow = settled && revealKey == null && positionedVisit != todayVisit
                val placement = remember(query, plan.occurrences) { TimeGridPlacement(plan.occurrences, query.startMillis, query.endMillis) }
                var moreStart by rememberSaveable { mutableStateOf<Long?>(null) }
                val group = moreStart?.let { start -> placement.more.firstOrNull { it.start == start } }
                val positioned: () -> Unit = {
                    positionedVisit = currentVisit
                    if (target != null) {
                        val hidden = placement.more.firstOrNull { g -> g.hidden.any { it.key == target } }
                        moreStart = hidden?.start
                        if (hidden == null) onRevealed(target)
                    }
                }
                if (group != null) OverlapSheet(placement, group, plan, model, { moreStart = null; editEvent(it) },
                    { moreStart = null; openCourse(it) }, { moreStart = null }, target, onRevealed)
                val more: (TimeGridPlacement.Overflow) -> Unit = { moreStart = it.start }
                rememberSaveableStateHolder().SaveableStateProvider(eventView) {
                    if (eventView) EventDay(plan, placement, model, editTray, editEvent, openCourse, more, onAdd, positionNow, target, positioned,
                        top, bottom, trayOpen, { trayOpen = !trayOpen }, pastDay = query.date < currentDate)
                    else DayBody(plan, placement, model, model::checkSchedule, editTray, editEvent, openCourse, more, onAdd,
                        positionNow, target, positioned, top, bottom, trayOpen, { trayOpen = !trayOpen })
                }
            }
        }
    }
}

@Composable
private fun DayBody(
    plan: DayPlan, placement: TimeGridPlacement, actions: WorkspaceActions, checkSlot: suspend (PlannerCommand) -> String?,
    editTray: (String) -> Unit, onEdit: (AgendaOccurrence) -> Unit, openCourse: (ScheduledCourse) -> Unit, onMore: (TimeGridPlacement.Overflow) -> Unit, onAdd: () -> Unit, positionNow: Boolean, revealKey: OccurrenceKey?, onPositioned: () -> Unit,
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
    val scale = remember(plan.day, placement, minutePx, minCard, minCourse) {
        DayAxisScale(plan.day, placement, minutePx, minCard, minCourse, with(density) { 48.dp.roundToPx() })
    }
    val initial = remember {
        val now = System.currentTimeMillis()
        val anchor = when {
            now in plan.day.startMillis until plan.day.endMillis -> now - 60 * MINUTE_MS
            placement.visible.isNotEmpty() -> placement.visible.first().start - 30 * MINUTE_MS
            else -> plan.day.date.atTime(8, 0).atZone(plan.day.zone).toInstant().toEpochMilli()
        }
        scale.yAt(anchor)
    }
    val scroll = rememberScrollState(initial)
    val atEnd by remember(scroll) { derivedStateOf { scroll.maxValue in 1 until Int.MAX_VALUE && !scroll.canScrollForward } }
    val drag = remember(scale, scroll) { AxisDrag(scale, scroll) }
    LaunchedEffect(revealKey, positionNow, drag) {
        if (revealKey != null) {
            val item = plan.occurrences.firstOrNull { it.key == revealKey } ?: return@LaunchedEffect
            snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
            scroll.scrollTo((scale.yAt(item.startMillis) - with(density) { 24.dp.roundToPx() }).coerceAtLeast(0))
        } else if (positionNow) {
            val now = System.currentTimeMillis()
            if (now in plan.day.startMillis until plan.day.endMillis) {
                snapshotFlow { drag.viewportHeight > 0 && scroll.maxValue != Int.MAX_VALUE }.first { it }
                scroll.scrollTo((scale.yAt(now) - (drag.viewportHeight / 3).toInt()).coerceAtLeast(0))
            }
        } else return@LaunchedEffect
        onPositioned()
    }
    val send: (PlannerCommand) -> Unit = { submission.send(it) }
    DragEffects(drag, checkSlot)
    Box(Modifier.fillMaxSize()) {
        val noticeSource = rememberPlannerBackdrop(drag.dropNotice != null)
        Box(Modifier.fillMaxSize().plannerBackdrop(noticeSource)) {
            DayWithTray(plan, drag, !submission.busy, editTray, onAdd, top, clearance, trayOpen, toggleTray, hideForEnd = atEnd,
                schedule = send) { spine, _ ->
                DayAxis(drag, plan.revision, !submission.busy, onEdit, openCourse, send, onMore, Modifier.fillMaxSize(), top, spine, endInGutter = trayOpen)
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
    date: LocalDate, onPick: () -> Unit, step: (Int) -> Unit, onToday: () -> Unit, onSettings: () -> Unit,
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
    RootToolbar(Destination.TODAY, onSettings, leading = {
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
        IconButton(onClick = { step(-1) }, enabled = date > firstDay) {
            Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.today_previous_day))
        }
        TextButton(onClick = onPick, contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.weight(1f, fill = false).testTag("day-date")
                .semantics { contentDescription = pickDate }) {
            Text(if (short) "${date.monthValue}/${date.dayOfMonth}" else date.format(dateHeading),
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { step(1) }, enabled = date < lastDay) {
            Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.today_next_day))
        }
    }
}
