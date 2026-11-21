package io.github.nakanomk.stacklife.today

import io.github.nakanomk.stacklife.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.appearance.ClockWheelField
import io.github.nakanomk.stacklife.appearance.DurationWheelField
import io.github.nakanomk.stacklife.planner.DayPlan
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.TaskInput
import io.github.nakanomk.stacklife.workspace.SaveOutcome
import io.github.nakanomk.stacklife.workspace.rememberSubmission
import java.time.Instant
import java.time.LocalTime

@Composable
internal fun GapTaskEditor(plan: DayPlan, start: Long, end: Long, revision: Long, model: WorkspaceViewModel, close: () -> Unit) {
    val zone = plan.day.zone
    val initial = remember(start, zone) { Instant.ofEpochMilli(start).atZone(zone) }
    var title by rememberSaveable { mutableStateOf("") }
    var minuteOfDay by rememberSaveable { mutableIntStateOf(initial.hour * 60 + initial.minute) }
    var minutes by rememberSaveable { mutableIntStateOf(minOf(30L, (end - start) / MINUTE_MS).toInt()) }
    var offsetId by rememberSaveable { mutableStateOf(initial.offset.id) }
    var rejection by rememberSaveable { mutableStateOf<String?>(null) }
    val submission = rememberSubmission(model, onError = { rejection = it }) { if (it == SaveOutcome.APPLIED) close() }
    val editable = !submission.busy
    // Keep seconds for non-minute-aligned gap boundaries until the displayed start is edited.
    val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    val local = if (time.hour == initial.hour && time.minute == initial.minute) initial.toLocalDateTime()
        else initial.toLocalDate().atTime(time)
    val offsets = remember(local, zone) { zone.rules.getValidOffsets(local) }
    val offset = offsets.find { it.id == offsetId } ?: offsets.firstOrNull()
    val at = offset?.let { local.atOffset(it) }
    val atMillis = at?.toInstant()?.toEpochMilli()
    val problem = when {
        revision != plan.revision -> stringResource(R.string.today_stale_gap)
        atMillis == null -> stringResource(R.string.today_skipped_time)
        minutes <= 0 -> stringResource(R.string.today_positive_minutes)
        atMillis < start || atMillis + minutes * MINUTE_MS > end -> stringResource(R.string.today_outside_gap)
        else -> null
    }
    PlannerAlertDialog(onDismissRequest = { if (editable) close() }, title = { Text(stringResource(R.string.today_gap_title)) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${clockLabel(start, plan.day)}–${clockLabel(end, plan.day)} · ${elapsedLabel(end - start)}")
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().testTag("gap-title"), singleLine = true,
                enabled = editable, label = { Text(stringResource(R.string.today_task_name)) })
            ClockWheelField(time, editable, Modifier.testTag("gap-time")) { minuteOfDay = it.hour * 60 + it.minute }
            if (offsets.size > 1) {
                Text(stringResource(R.string.today_choose_offset))
                for (choice in offsets) FilterChip(selected = choice == offset, onClick = { offsetId = choice.id },
                    label = { Text("UTC${choice.id}") }, enabled = editable)
            }
            DurationWheelField(stringResource(R.string.today_duration_minutes), minutes, editable, Modifier.testTag("gap-duration")) { minutes = it }
            (problem ?: rejection)?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
        }
    }, dismissButton = { TextButton(onClick = close, enabled = editable) { Text(stringResource(R.string.today_cancel)) } }, confirmButton = {
        TextButton(enabled = editable && title.isNotBlank() && problem == null, modifier = Modifier.testTag("gap-save"), onClick = {
            if (at != null && minutes > 0) {
                rejection = null
                submission.submit { model.startReviewed(listOf(PlannerCommand.TaskAdd(TaskInput(title.trim(), minutes = minutes, ref = "task")),
                    PlannerCommand.SlotSet("@task", at, minutes, revision)), revision) }
            }
        }) { Text(stringResource(R.string.today_add_and_schedule)) }
    })
}
