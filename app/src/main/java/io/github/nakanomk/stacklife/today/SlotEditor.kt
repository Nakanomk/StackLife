package io.github.nakanomk.stacklife.today

import io.github.nakanomk.stacklife.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import io.github.nakanomk.stacklife.assistant.AssistantPage

/** A restored editor targets exactly the task and revision the user opened, never a replacement. */
@Serializable
internal data class SlotEditorKey(
    val taskId: String, val title: String, val dateEpochDay: Long, val zoneId: String,
    val startMillis: Long?, val minutes: Int, val revision: Long,
) : NavKey

internal fun DayPlan.editorFor(id: String): SlotEditorKey? {
    val pending = tray.find { it.id == id }
    if (pending != null) return SlotEditorKey(id, pending.title, day.date.toEpochDay(), day.zone.id, null, pending.minutes, revision)
    val block = blocks.find { it.taskId == id && it.state != ScheduledState.DONE } ?: return null
    return SlotEditorKey(id, block.title, day.date.toEpochDay(), day.zone.id, block.startMillis,
        ((block.endMillis - block.startMillis) / 60_000).toInt(), revision)
}

private val endFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm XXX", java.util.Locale.ROOT)

@Composable
internal fun SlotEditor(
    entry: SlotEditorKey, actions: WorkspaceActions, checkSlot: suspend (PlannerCommand.SlotSet) -> String?,
    onBack: () -> Unit, onSaved: (Instant?) -> Unit,
) {
    val zone = remember(entry.zoneId) { ZoneId.of(entry.zoneId) }
    val original = remember(entry) { entry.startMillis?.let { Instant.ofEpochMilli(it).atZone(zone) } }
    val initial = remember(entry) {
        original?.toLocalDateTime() ?: LocalDate.ofEpochDay(entry.dateEpochDay).atTime(9, 0)
    }
    var epochDay by rememberSaveable { mutableLongStateOf(initial.toLocalDate().toEpochDay()) }
    var hour by rememberSaveable { mutableStateOf(initial.hour.toString().padStart(2, '0')) }
    var minute by rememberSaveable { mutableStateOf(initial.minute.toString().padStart(2, '0')) }
    var duration by rememberSaveable { mutableStateOf(entry.minutes.toString()) }
    var offsetChoice by rememberSaveable { mutableStateOf(original?.offset?.id) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var clearing by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.ofEpochDay(epochDay)
    val h = hour.toIntOrNull()?.takeIf { it in 0..23 }
    val m = minute.toIntOrNull()?.takeIf { it in 0..59 }
    val minutes = duration.toIntOrNull()?.takeIf { it > 0 }
    val local = if (h == null || m == null) null else if (date == initial.toLocalDate() && h == initial.hour && m == initial.minute)
        initial else date.atTime(h, m)
    val offsets = remember(local, zone) { local?.let(zone.rules::getValidOffsets).orEmpty() }
    val offset = offsets.find { it.id == offsetChoice } ?: offsets.firstOrNull()
    val command = if (local != null && offset != null && minutes != null)
        PlannerCommand.SlotSet(entry.taskId, local.atOffset(offset), minutes, entry.revision) else null
    val submission = rememberSubmission(actions) { outcome ->
        when (outcome) {
            SaveOutcome.APPLIED -> onSaved(if (clearing) null else command?.start?.toInstant())
            SaveOutcome.INTERRUPTED -> onBack()
            SaveOutcome.REJECTED -> Unit
        }
    }
    BackHandler(enabled = submission.busy) { /* Let the outstanding receipt finish once. */ }
    val editable = !submission.busy
    val validate by rememberUpdatedState(checkSlot)
    EditorPage(if (original == null) stringResource(R.string.today_schedule) else stringResource(R.string.today_adjust_slot), onBack, AssistantPage.Task(entry.taskId), editable) { top, bottom ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(dockListPadding(top, bottom)),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.today_zone, zone.id), style = MaterialTheme.typography.bodySmall)
            PreferenceRow(stringResource(R.string.today_start_date, date), modifier = Modifier.testTag("slot:date"), enabled = editable) { pickDate = true }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(stringResource(R.string.today_start_hour), hour, editable, Modifier.weight(1f).testTag("slot:hour")) { hour = it }
                NumberField(stringResource(R.string.today_start_minute), minute, editable, Modifier.weight(1f).testTag("slot:minute")) { minute = it }
            }
            if (offsets.size > 1) {
                SectionTitle(stringResource(R.string.today_choose_repeated_offset))
                Column(Modifier.selectableGroup()) {
                    for (o in offsets) {
                        Row(Modifier.fillMaxWidth().selectable(o == offset, editable, Role.RadioButton) { offsetChoice = o.id }
                            .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RadioButton(selected = o == offset, onClick = null)
                            Text("UTC${o.id}")
                        }
                    }
                }
            }
            NumberField(stringResource(R.string.today_duration_minutes), duration, editable, Modifier.fillMaxWidth().testTag("slot:duration")) { duration = it }
            val latestCheck = key(command) {
                var result by remember { mutableStateOf<String?>(null) }
                var checked by remember { mutableStateOf(false) }
                LaunchedEffect(command) { if (command != null) { result = validate(command); checked = true } }
                when {
                    h == null || m == null -> stringResource(R.string.today_enter_valid_time)
                    offsets.isEmpty() -> stringResource(R.string.today_skipped_time)
                    minutes == null -> stringResource(R.string.today_positive_minutes)
                    !checked -> stringResource(R.string.today_checking_slot)
                    else -> result
                }
            }
            if (command != null) {
                val end = command.start.toInstant().plusSeconds(command.durationMinutes * 60L).atZone(zone)
                Text(stringResource(R.string.today_end_time, endFormat.format(end)), style = MaterialTheme.typography.bodyLarge)
            }
            if (latestCheck != null) Text(latestCheck, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Actions {
                Primary(stringResource(R.string.today_save_slot), editable && command != null && latestCheck == null) {
                    if (command != null) { clearing = false; submission.send(command) }
                }
                if (original != null) Secondary(stringResource(R.string.today_unschedule), editable) {
                    clearing = true
                    submission.send(PlannerCommand.SlotClear(entry.taskId, entry.revision))
                }
            }
        }
    }
    if (pickDate) DateChooser(date, { epochDay = it.toEpochDay(); pickDate = false }, { pickDate = false })
}

@Composable
private fun NumberField(label: String, value: String, enabled: Boolean, modifier: Modifier, change: (String) -> Unit) {
    TextField(colors = lineFieldColors(), value = value, onValueChange = change, label = { Text(label) }, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = modifier)
}
