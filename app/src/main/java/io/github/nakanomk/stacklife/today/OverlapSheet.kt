package io.github.nakanomk.stacklife.today

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.PlannerModalBottomSheet
import io.github.nakanomk.stacklife.courses.ScheduledCourse
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.rememberSubmission

@Composable
internal fun MoreEventsButton(group: TimeGridPlacement.Overflow, open: (TimeGridPlacement.Overflow) -> Unit, modifier: Modifier) {
    val label = stringResource(R.string.slot_more_events, group.hidden.size)
    TextButton(onClick = { open(group) }, modifier = modifier.heightIn(min = 48.dp).testTag("overlap:${group.start}")
        .semantics { contentDescription = label }, contentPadding = PaddingValues(2.dp)) { Text("+${group.hidden.size}") }
}

@Composable
internal fun OverlapSheet(placement: TimeGridPlacement, group: TimeGridPlacement.Overflow, plan: DayPlan, model: WorkspaceViewModel,
    edit: (AgendaOccurrence) -> Unit, openCourse: (ScheduledCourse) -> Unit, close: () -> Unit, focusKey: String?) {
    val members = remember(placement, group) { placement.members(group) }
    val submission = rememberSubmission(model)
    PlannerModalBottomSheet(close, canDismiss = !submission.busy) {
        val list = rememberLazyListState(initialFirstVisibleItemIndex = members.indexOfFirst { it.stableKey == focusKey }.coerceAtLeast(0))
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.slot_overlapping, members.size), style = MaterialTheme.typography.titleLarge)
            Text(if (group.start == group.end) clockLabel(group.start, plan.day) else
                "${clockLabel(group.start, plan.day)}–${clockLabel(group.end, plan.day)}", style = MaterialTheme.typography.labelMedium)
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("overlap-list"), state = list,
                contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(members, key = { it.stableKey }) { item ->
                    AgendaEventCard(item, plan.day, plan.revision, !submission.busy, edit, openCourse, { submission.send(it) },
                        Modifier.fillMaxWidth().testTag("overlap-item:${item.stableKey}"), wrap = true, focused = item.stableKey == focusKey)
                }
            }
        }
    }
}
