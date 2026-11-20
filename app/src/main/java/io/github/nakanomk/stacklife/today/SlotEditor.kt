package io.github.nakanomk.stacklife.today

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

private val endFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm XXX")

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
    Page {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PageTitle(if (original == null) "安排时间" else "调整时段", back = { if (editable) onBack() })
            Text(entry.title, style = MaterialTheme.typography.headlineSmall)
            Text("当地时区 · ${zone.id}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { pickDate = true }, enabled = editable,
                modifier = Modifier.heightIn(min = 48.dp).testTag("slot:date")) { Text("开始日期：$date") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("开始小时（0–23）", hour, editable, Modifier.weight(1f).testTag("slot:hour")) { hour = it }
                NumberField("分钟（0–59）", minute, editable, Modifier.weight(1f).testTag("slot:minute")) { minute = it }
            }
            if (offsets.size > 1) {
                SectionTitle("该时刻出现两次，请选偏移")
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
            NumberField("时长（分钟）", duration, editable, Modifier.fillMaxWidth().testTag("slot:duration")) { duration = it }
            val latestCheck = key(command) {
                var result by remember { mutableStateOf<String?>(null) }
                var checked by remember { mutableStateOf(false) }
                LaunchedEffect(command) { if (command != null) { result = validate(command); checked = true } }
                when {
                    h == null || m == null -> "请输入有效的小时和分钟"
                    offsets.isEmpty() -> "当地时钟跳过了这个时刻，请选择其他时间"
                    minutes == null -> "时长须为正整数分钟"
                    !checked -> "正在核对时段…"
                    else -> result
                }
            }
            if (command != null) {
                val end = command.start.toInstant().plusSeconds(command.durationMinutes * 60L).atZone(zone)
                Text("结束：${endFormat.format(end)}", style = MaterialTheme.typography.bodyLarge)
            }
            if (latestCheck != null) Text(latestCheck, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Actions {
                Primary("保存时段", editable && command != null && latestCheck == null) {
                    if (command != null) { clearing = false; submission.send(command) }
                }
                if (original != null) Secondary("取消安排", editable) {
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
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = modifier)
}
