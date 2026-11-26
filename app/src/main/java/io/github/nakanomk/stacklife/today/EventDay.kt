package io.github.nakanomk.stacklife.today

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.scrollBoundaryHaptics
import io.github.nakanomk.stacklife.courses.ScheduledCourse
import io.github.nakanomk.stacklife.planner.AgendaOccurrence
import io.github.nakanomk.stacklife.planner.OccurrenceKey
import io.github.nakanomk.stacklife.planner.DayPlan
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.workspace.rememberSubmission
import io.github.nakanomk.stacklife.planner.TaskIcon
import io.github.nakanomk.stacklife.workspace.symbolLabel
import io.github.nakanomk.stacklife.workspace.symbolResource

@Composable
internal fun EventDay(
    plan: DayPlan, placement: TimeGridPlacement, model: WorkspaceViewModel, editTray: (String) -> Unit, onEdit: (AgendaOccurrence) -> Unit, openCourse: (ScheduledCourse) -> Unit, onMore: (TimeGridPlacement.Overflow) -> Unit, onAdd: () -> Unit,
    positionNow: Boolean, revealKey: OccurrenceKey?, onPositioned: () -> Unit, top: Dp, bottom: Dp, trayOpen: Boolean, toggleTray: () -> Unit,
    pastDay: Boolean,
) {
    val rows = remember(plan.day, placement) { dayEventRows(plan.day, placement) }
    val submission = rememberSubmission(model)
    val list = rememberLazyListState()
    val wheel = dayWheelEnabled()
    LaunchedEffect(revealKey, positionNow, rows) {
        val index = if (revealKey != null) {
            rows.indexOfFirst { when (it) {
                is DayEventRow.Event -> it.entry.key == revealKey
                is DayEventRow.More -> it.group.hidden.any { item -> item.key == revealKey }
                is DayEventRow.Gap -> false
            } }.takeIf { it >= 0 } ?: return@LaunchedEffect
        } else if (positionNow) {
            val now = System.currentTimeMillis()
            rows.indexOfLast { it.start <= now && (now < it.end || it.start == now) }
        } else return@LaunchedEffect
        if (index >= 0) list.scrollToItem(index)
        onPositioned()
    }
    var gapStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var gapEnd by rememberSaveable { mutableLongStateOf(0L) }
    var gapRevision by rememberSaveable { mutableLongStateOf(0L) }
    DayWithTray(plan, null, true, editTray, onAdd, top, bottom, trayOpen, toggleTray, hideForEnd = false) { spine, tail ->
        val scheme = MaterialTheme.colorScheme
        Spacer(Modifier.padding(start = spine - 1.dp, top = top).width(2.dp).fillMaxHeight()
            .testTag("day-rail").background(if (pastDay) scheme.primary else scheme.outlineVariant))
        LazyColumn(Modifier.fillMaxSize().testTag("day-events").scrollBoundaryHaptics(list), state = list,
            contentPadding = PaddingValues(top = top, bottom = tail)) {
            items(rows.size, key = { index -> when (val row = rows[index]) {
                is DayEventRow.Event -> row.entry.stableKey
                is DayEventRow.More -> "more:${row.start}"
                is DayEventRow.Gap -> "gap:${row.start}"
            } }) { index ->
                val row = rows[index]
                key(row) {
                    EventRow(row, plan.day, spine, plan.revision, !submission.busy,
                        Modifier.listWheel(wheel && row !is DayEventRow.Gap, list, index), onEdit, openCourse, onMore, { submission.send(it) }) { gap ->
                        gapStart = gap.start; gapEnd = gap.end; gapRevision = plan.revision
                    }
                }
            }
            item {
                Text(if (rows.isEmpty()) stringResource(R.string.today_skipped_day) else stringResource(R.string.today_end_at, clockLabel(plan.day.endMillis, plan.day)),
                    Modifier.padding(start = spine + 22.dp, top = 8.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    gapStart?.let { start ->
        GapTaskEditor(plan, start, gapEnd, gapRevision, model) { gapStart = null }
    }
}

@Composable
private fun EventRow(row: DayEventRow, day: PlannerQuery.Day, spine: Dp, revision: Long, enabled: Boolean, wheel: Modifier, onEdit: (AgendaOccurrence) -> Unit,
    openCourse: (ScheduledCourse) -> Unit, onMore: (TimeGridPlacement.Overflow) -> Unit, complete: (PlannerCommand) -> Unit, onGap: (DayEventRow.Gap) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val entry = (row as? DayEventRow.Event)?.entry
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(clockLabel(row.start, day), Modifier.width(spine - 16.dp).padding(horizontal = 3.dp),
            textAlign = TextAlign.End, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (entry != null) {
                val course = entry.course != null
                Box(Modifier.fillMaxSize().background(if (course) colors.tertiaryContainer else colors.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (course) R.drawable.ic_calendar_view_week else symbolResource(entry.icon)),
                        if (course || entry.icon == TaskIcon.AUTO) null else symbolLabel(entry.icon),
                        Modifier.size(18.dp), tint = if (course) colors.onTertiaryContainer else colors.onSecondaryContainer)
                }
            } else Spacer(Modifier.size(8.dp).background(colors.outline, CircleShape))
        }
        val body = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp).heightIn(min = 48.dp)
        when (row) {
            is DayEventRow.Event -> AgendaEventCard(row.entry, day, revision, enabled, onEdit, openCourse, complete, body.then(wheel), wrap = true, showTitleIcon = false)
            is DayEventRow.More -> MoreEventsButton(row.group, onMore, body.then(wheel))
            is DayEventRow.Gap -> {
                val canAdd = row.end > row.start
                val label = stringResource(R.string.today_free_duration, elapsedLabel(row.end - row.start))
                val add = if (canAdd) Modifier.clickable(onClickLabel = stringResource(R.string.today_add_in_gap), role = Role.Button) { onGap(row) } else Modifier
                Row(body.testTag("day-gap:${row.start}").then(add).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    if (canAdd) Icon(painterResource(R.drawable.ic_add), stringResource(R.string.today_add_task), Modifier.size(20.dp), tint = colors.primary)
                }
            }
        }
    }
}
