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
import io.github.nakanomk.stacklife.planner.DayPlan
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.workspace.rememberSubmission

@Composable
internal fun EventDay(
    plan: DayPlan, entries: List<AxisEntry>, model: WorkspaceViewModel, onEdit: (String) -> Unit, onAdd: () -> Unit,
    revealStart: Long?, onRevealed: () -> Unit, top: Dp, bottom: Dp, trayOpen: Boolean, toggleTray: () -> Unit,
) {
    val rows = remember(plan.day, entries) { dayEventRows(plan.day, entries) }
    val submission = rememberSubmission(model)
    val list = rememberLazyListState()
    LaunchedEffect(revealStart, rows) {
        if (revealStart != null) {
            val index = rows.indexOfFirst { it is DayEventRow.Event && it.entry is AxisEntry.Task && it.entry.startMillis == revealStart }
            if (index >= 0) { list.scrollToItem(index); onRevealed() }
        }
    }
    var gapStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var gapEnd by rememberSaveable { mutableLongStateOf(0L) }
    var gapRevision by rememberSaveable { mutableLongStateOf(0L) }
    DayWithTray(plan, null, true, onEdit, onAdd, top, bottom, trayOpen, toggleTray) { spine, tail ->
        LazyColumn(Modifier.fillMaxSize().padding(top = top).testTag("day-events"), state = list,
            contentPadding = PaddingValues(bottom = tail)) {
            items(rows.size) { index ->
                val row = rows[index]
                key(row) {
                    EventRow(row, plan.day, spine, plan.revision, !submission.busy, onEdit, { submission.send(it) }) { gap ->
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
private fun EventRow(row: DayEventRow, day: PlannerQuery.Day, spine: Dp, revision: Long, enabled: Boolean, onEdit: (String) -> Unit,
    complete: (PlannerCommand) -> Unit, onGap: (DayEventRow.Gap) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val entry = (row as? DayEventRow.Event)?.entry
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(clockLabel(row.start, day), Modifier.width(spine - 16.dp).padding(horizontal = 3.dp),
            textAlign = TextAlign.End, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (entry != null) {
                val course = entry is AxisEntry.Course
                Box(Modifier.fillMaxSize().background(if (course) colors.tertiaryContainer else colors.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (course) R.drawable.ic_calendar_view_week else R.drawable.ic_schedule), null,
                        Modifier.size(18.dp), tint = if (course) colors.onTertiaryContainer else colors.onSecondaryContainer)
                }
            } else Spacer(Modifier.size(8.dp).background(colors.outline, CircleShape))
        }
        val body = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp).heightIn(min = 48.dp)
        when (row) {
            is DayEventRow.Event -> AgendaEventCard(row.entry, day, revision, enabled, onEdit, complete, body, wrap = true)
            is DayEventRow.Gap -> {
                val canAdd = row.end - row.start >= MINUTE_MS
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
