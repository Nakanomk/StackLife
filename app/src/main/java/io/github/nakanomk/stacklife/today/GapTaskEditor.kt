package io.github.nakanomk.stacklife.today

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.planner.DayPlan
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.TaskInput
import io.github.nakanomk.stacklife.workspace.SaveOutcome
import io.github.nakanomk.stacklife.workspace.rememberSubmission
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm")

@Composable
internal fun GapTaskEditor(plan: DayPlan, start: Long, end: Long, revision: Long, model: WorkspaceViewModel, close: () -> Unit) {
    val zone = plan.day.zone
    val initial = remember(start, zone) { Instant.ofEpochMilli(start).atZone(zone) }
    var title by rememberSaveable { mutableStateOf("") }
    var time by rememberSaveable { mutableStateOf(clock.format(initial)) }
    var duration by rememberSaveable { mutableStateOf(minOf(30L, (end - start) / MINUTE_MS).toString()) }
    var offsetId by rememberSaveable { mutableStateOf(initial.offset.id) }
    var rejection by rememberSaveable { mutableStateOf<String?>(null) }
    val submission = rememberSubmission(model, onError = { rejection = it }) { if (it == SaveOutcome.APPLIED) close() }
    val editable = !submission.busy
    // Keep seconds for non-minute-aligned gap boundaries until the displayed start is edited.
    val local = remember(initial, time) {
        if (time == clock.format(initial)) initial.toLocalDateTime() else {
            val h = time.substringBefore(':').toIntOrNull()
            val m = time.substringAfter(':', "").toIntOrNull()
            if (h != null && h in 0..23 && m != null && m in 0..59) initial.toLocalDate().atTime(LocalTime.of(h, m)) else null
        }
    }
    val offsets = remember(local, zone) { local?.let(zone.rules::getValidOffsets).orEmpty() }
    val offset = offsets.find { it.id == offsetId } ?: offsets.firstOrNull()
    val at = if (local != null && offset != null) local.atOffset(offset) else null
    val minutes = duration.toIntOrNull()?.takeIf { it > 0 }
    val atMillis = at?.toInstant()?.toEpochMilli()
    val problem = when {
        revision != plan.revision -> "日程已变更，请关闭并重新选择空闲时段。"
        local == null -> "请输入 24 小时时刻，如 09:30"
        atMillis == null -> "当地时钟跳过了这个时刻，请选择其他时间"
        minutes == null -> "时长须为正整数分钟"
        atMillis < start || atMillis + minutes * MINUTE_MS > end -> "起止时间须在所选空闲时段内"
        else -> null
    }
    PlannerAlertDialog(onDismissRequest = { if (editable) close() }, title = { Text("在空闲时段添加任务") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${clockLabel(start, plan.day)}–${clockLabel(end, plan.day)} · ${elapsedLabel(end - start)}")
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().testTag("gap-title"), singleLine = true,
                enabled = editable, label = { Text("任务名称") })
            OutlinedTextField(time, { time = it }, Modifier.fillMaxWidth().testTag("gap-time"), singleLine = true,
                enabled = editable, label = { Text("开始时间（HH:mm）") })
            if (offsets.size > 1) {
                Text("该时刻出现两次，请选择 UTC 偏移")
                for (choice in offsets) FilterChip(selected = choice == offset, onClick = { offsetId = choice.id },
                    label = { Text("UTC${choice.id}") }, enabled = editable)
            }
            OutlinedTextField(duration, { duration = it }, Modifier.fillMaxWidth().testTag("gap-duration"), singleLine = true,
                enabled = editable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("时长（分钟）") })
            (problem ?: rejection)?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
        }
    }, dismissButton = { TextButton(onClick = close, enabled = editable) { Text("取消") } }, confirmButton = {
        TextButton(enabled = editable && title.isNotBlank() && problem == null, modifier = Modifier.testTag("gap-save"), onClick = {
            if (at != null && minutes != null) {
                rejection = null
                submission.submit { model.startReviewed(listOf(PlannerCommand.TaskAdd(TaskInput(title.trim(), minutes = minutes, ref = "task")),
                    PlannerCommand.SlotSet("@task", at, minutes, revision)), revision) }
            }
        }) { Text("添加并安排") }
    })
}
