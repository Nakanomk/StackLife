package io.github.nakanomk.stacklife.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.courses.CourseMeeting
import io.github.nakanomk.stacklife.courses.sourceLabel
import io.github.nakanomk.stacklife.i18n.AppLanguage
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.markdown.PlannerMarkdown
import io.github.nakanomk.stacklife.markdown.markdownSummary
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.transitions.CardMetadata
import io.github.nakanomk.stacklife.transitions.CardSummary
import io.github.nakanomk.stacklife.workspace.FormGroup
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Purely local; the only action here is expansion, never application or tool invocation. */
@Composable
internal fun ChangePreview(change: PlannerChange, modifier: Modifier = Modifier) = key(change.entityId) {
    FormGroup(modifier, frosted = true) {
        val before = change.before; val after = change.after
        for (side in 0..1) {
            val isBefore = side == 0
            val snapshot = (if (isBefore) before else after) ?: continue
            val opposite = if (isBefore) after else before
            val label = when {
                opposite == null && isBefore -> uiText("Removed", "移除")
                opposite == null -> uiText("Added", "新增")
                isBefore -> uiText("Before", "修改前")
                else -> uiText("After", "修改后")
            }
            if (!isBefore && before != null) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(label, Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge,
                color = if (isBefore && after == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            when (snapshot) {
                is CountdownSnapshot -> io.github.nakanomk.stacklife.countdown.CountdownCard(snapshot.value, io.github.nakanomk.stacklife.today.deviceDay().date)
                is TaskSnapshot -> TaskPreview(snapshot, opposite as? TaskSnapshot)
                is QueueSnapshot -> QueuePreview(snapshot, opposite as? QueueSnapshot, isBefore)
                is FrameSnapshot -> FramePreview(snapshot)
                is GraphSnapshot -> GraphChangeDiagram(snapshot, opposite as? GraphSnapshot, isBefore)
                is GoalGenerationSnapshot -> {
                    PreviewTitle(snapshot.order.last().title)
                    for ((index, node) in snapshot.order.withIndex()) {
                        Text("${index + 1}. ${node.title}")
                        val s = node.linkState
                        CardMetadata {
                            Text(when {
                                s == null && snapshot.frame -> uiText("New task owned by this frame", "新建任务，归此帧所有")
                                s == null -> uiText("New task appended to the queue", "新建任务，追加到队尾")
                                s.missing -> uiText("Source removed", "来源已移除")
                                s.completed -> uiText("Already satisfied", "已完成，无需新增")
                                s.owner == Owner.FRAME -> uiText("Stays in its frame", "留在原栈帧")
                                s.owner == Owner.QUEUE -> uiText("Keeps its queue position", "保留原队列位置")
                                else -> uiText("Existing occurrence", "引用已有发生项")
                            })
                            if (s?.scheduled == true) Text(uiText("Keeps its schedule", "保留原排程"))
                            if (snapshot.frame && s != null) Text(uiText("Reference only", "仅引用，不可恢复"))
                            (node.link as? GraphLink.Occurrence)?.let { Text(LocalDate.ofEpochDay(it.originalDate).toString()) }
                        }
                    }
                }
                is ScheduleSnapshot -> ScheduleChangeDiagram(snapshot, opposite as? ScheduleSnapshot)
                is SeriesSnapshot -> {
                    PreviewTitle(snapshot.title)
                    Text(io.github.nakanomk.stacklife.workspace.scheduleText(snapshot.schedule))
                    (snapshot.schedule.pattern as? SchedulePattern.Daily)?.segments?.forEach { r ->
                        val first = LocalDate.ofEpochDay(r.fromDay)
                        Text(if (r.endDay == null) uiText("From $first", "从 $first 起") else "$first – ${LocalDate.ofEpochDay(r.endDay)}")
                        CardMetadata { Text(LocalTime.ofSecondOfDay(r.minute * 60L).toString()); Text(r.zone ?: uiText("Device time zone", "跟随设备时区")); Text(uiText("${r.minutes} min","${r.minutes} 分钟")) }
                    }
                    if (snapshot.explicitExceptions > 0) Text(uiText("Explicit exceptions: ${snapshot.explicitExceptions}", "保留的单次例外：${snapshot.explicitExceptions}"))
                }
                is OccurrenceSnapshot -> {
                    PreviewTitle(snapshot.title)
                    (snapshot.key as? OccurrenceKey.Schedule)?.originalDay?.let { Text(uiText("Occurrence of ${LocalDate.ofEpochDay(it)}","原始日期 ${LocalDate.ofEpochDay(it)}")) }
                    snapshot.interval?.let { i ->
                        val zone = ZoneId.of(snapshot.zone)
                        Text(if (i.point) snapshotTime(i.startMillis,zone) else "${snapshotTime(i.startMillis,zone)} – ${snapshotTime(i.endMillis,zone)}")
                    }
                    Completed(snapshot.completed)
                }
                is ReminderSnapshot -> {
                    PreviewTitle(snapshot.title)
                    Text(when (snapshot.subject) {
                        ReminderSubject.CourseDefault -> uiText("Default for classes", "课程默认提醒")
                        is ReminderSubject.Schedule -> uiText("Event schedule", "排程提醒")
                        is ReminderSubject.Occurrence -> uiText("Only this occurrence", "仅本次提醒")
                    })
                    if (snapshot.policy.inherit) Text(uiText("Inherits reminders", "继承默认规则"))
                    if (snapshot.effective.isEmpty()) Text(uiText("No reminders", "不提醒"))
                    snapshot.effective.forEach { r -> CardMetadata {
                        Text(when (val w = r.time) {
                            is ReminderWhen.AtInstant -> snapshotTime(w.instantMillis,ZoneId.systemDefault())
                            is ReminderWhen.BeforeStart -> when {
                                w.leadMillis == 0L -> uiText("At start", "开始时")
                                w.leadMillis % 60_000L == 0L -> uiText("${w.leadMillis / 60_000L} min before", "提前 ${w.leadMillis / 60_000L} 分钟")
                                else -> uiText("${w.leadMillis} ms before", "提前 ${w.leadMillis} 毫秒")
                            }
                        })
                        if (r.id in snapshot.expiredRuleIds) Text(uiText("Expired; will not be sent", "已过期，不补发"))
                    } }
                }
                is CourseSetSnapshot -> CoursePreview(snapshot, (after as? CourseSetSnapshot)?.replacements.orEmpty(), isBefore)
                is AppearanceSnapshot -> {
                    val old = (before as AppearanceSnapshot).value; val new = (after as AppearanceSnapshot).value
                    for (difference in remember(old, new) { appearanceDifferences(old, new) }) {
                        val value = if (isBefore) difference.before else difference.after
                        val boolean = (value as? JsonPrimitive)?.booleanOrNull
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(difference.label(), Modifier.weight(1f))
                            if (boolean != null) Switch(checked = boolean, onCheckedChange = null)
                            else if (difference.key == "seed") Box(Modifier.size(20.dp).background(
                                Color(android.graphics.Color.parseColor(value.toString().trim('"'))), MaterialTheme.shapes.small))
                        }
                        Text(appearanceLabel(value), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is RestorePolicySnapshot -> { PreviewTitle(uiText("Restore policy", "恢复策略")); Text(policyName(snapshot.value)) }
                is KeepRestoredFramesSnapshot -> {
                    PreviewTitle(uiText("Keep restored frames", "恢复后保留栈帧"))
                    Text(if (snapshot.keep) uiText("On", "开启") else uiText("Off", "关闭"))
                }
                is RoutineTimeSnapshot -> { PreviewTitle(snapshot.kind.label()); Text(snapshot.minute?.let {
                    LocalTime.ofSecondOfDay(it * 60L).toString() } ?: uiText("Off", "关闭")) }
                is CourseCompletionSnapshot -> {
                    val c = snapshot.occurrence
                    PreviewTitle(c.title); CardMetadata { Text(sourceLabel(c.source)); Text(c.termId); Text(uiText("Week ${c.week}", "第 ${c.week} 周")); Text(io.github.nakanomk.stacklife.courses.weekday(c.day)); Text("${c.firstPeriod}–${c.lastPeriod}"); if (c.room.isNotEmpty()) Text(c.room) }
                    Completed(snapshot.completed)
                }
                is RoutineCompletionSnapshot -> { PreviewTitle(snapshot.kind.label()); Text(LocalDate.ofEpochDay(snapshot.epochDay).toString()); Completed(snapshot.completed) }
            }
        }
    }
}

@Composable internal fun PreviewTitle(value: String) { Text(value, style = MaterialTheme.typography.bodyLarge) }
@Composable internal fun PreviewFold(label: String, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (open) uiText("Collapse: $label", "收起：$label") else label)
    }
    if (open) content()
}

@Composable private fun Completed(value: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = value, onCheckedChange = null)
        Text(if (value) uiText("Completed", "已完成") else uiText("Not completed", "未完成"), Modifier.padding(start = 8.dp))
    }
}

@Composable internal fun PreviewNote(notes: String) {
    if (notes.isEmpty()) { Text(uiText("No note", "无笔记"), style = MaterialTheme.typography.bodyMedium); return }
    if (notes.length <= 280) PlannerMarkdown(notes) else {
        CardSummary(remember(notes) { markdownSummary(notes) })
        PreviewFold(uiText("Read full note", "阅读完整笔记")) { PlannerMarkdown(notes) }
    }
}

@Composable private fun TaskPreview(task: TaskSnapshot, other: TaskSnapshot?) {
    PreviewTitle(task.title)
    CardMetadata {
        Text(uiText("${task.minutes} min", "${task.minutes} 分钟"))
        Text(when (task.owner) {
            Owner.QUEUE -> uiText("Queue #${(task.queuePosition ?: 0) + 1}", "队列第 ${(task.queuePosition ?: 0) + 1} 项")
            Owner.FRAME -> uiText("In a saved frame", "在栈帧中")
            Owner.DONE -> uiText("Completed", "已完成"); Owner.REMOVED -> uiText("Removed", "已移出")
        })
    }
    if (task.notes.isNotEmpty() || !other?.notes.isNullOrEmpty()) PreviewNote(task.notes)
}

@Composable private fun QueuePreview(value: QueueSnapshot, other: QueueSnapshot?, before: Boolean) {
    PreviewTitle(uiText("Queue", "队列"))
    val indices = remember(other) { other?.items?.mapIndexed { i, item -> item.id to i }?.toMap().orEmpty() }
    if (value.items.isEmpty()) Text(uiText("Empty queue", "空队列"))
    value.items.forEachIndexed { i, item ->
        val prior = indices[item.id]
        Column {
            Text("${i + 1}. ${item.title}")
            if (prior == null) CardMetadata { Text(if (before) uiText("Leaves the queue", "离开队列") else uiText("Joins the queue", "进入队列")) }
            else if (prior != i && !before) CardMetadata { Text(uiText("Moved from #${prior + 1} to #${i + 1}", "由第 ${prior + 1} 项移到第 ${i + 1} 项")) }
        }
    }
}

private fun policyName(value: RestorePolicy) = when (value) {
    RestorePolicy.PREPEND -> uiText("Prepend in frame order", "按帧内原序插入队首")
    RestorePolicy.APPEND -> uiText("Append in frame order", "按帧内原序追加到队尾")
    RestorePolicy.SWAP -> uiText("Switch frames", "整帧切换")
}
internal fun snapshotTime(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis).atZone(zone)
    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(AppLanguage.locale))

@Composable private fun FramePreview(value: FrameSnapshot) {
    PreviewTitle(frameLabel(value.reason, value.source))
    CardMetadata { Text(snapshotTime(value.savedAtMillis, ZoneId.systemDefault())); Text(when (value.source) {
        FrameSource.PUSH -> uiText("Saved from queue", "从队列保存"); FrameSource.MANUAL -> uiText("Created manually", "手动新建")
        FrameSource.SWAP -> uiText("Saved before switching frames", "切换前另存"); FrameSource.GRAPH -> uiText("Generated from graph", "从图目标生成")
    }) }
    for (restore in value.restores) {
        Text(policyName(restore.policy), style = MaterialTheme.typography.titleSmall)
        Text(when {
            restore.savedQueueFrameId != null -> uiText("Existing queue kept in a separate frame", "原队列已另存一帧")
            restore.policy == RestorePolicy.SWAP && restore.mode == RestoreMode.SELECTED -> uiText("Selected items go first; the current queue is kept", "部分恢复插在队首，原队列保留")
            restore.policy == RestorePolicy.SWAP -> uiText("There was no existing queue to save", "原队列为空，无需另存")
            else -> uiText("Keeps existing queued tasks", "保留队列里已有的任务")
        })
    }
    val restored = remember(value.restores) { value.restores.flatMap { it.taskIds }.toHashSet() }
    for (item in value.items) {
        Text("${item.originalPosition + 1}. ${item.title}")
        CardMetadata { Text(uiText("${item.minutes} min", "${item.minutes} 分钟")); Text(when {
            item.referenceOnly -> uiText("Reference only; not restorable", "仅引用，不可恢复")
            item.taskId in restored -> uiText("Restored to queue", "已恢复到队列"); item.restorable -> uiText("Available to restore", "可恢复")
            else -> uiText("Snapshot only", "仅历史快照") }) }
        if (item.schedule != null) Text(io.github.nakanomk.stacklife.workspace.scheduleText(item.schedule))
        else if (item.startMillis != null && item.endMillis != null) Text("${snapshotTime(item.startMillis, ZoneId.systemDefault())} – ${snapshotTime(item.endMillis, ZoneId.systemDefault())}")
        if (item.notes.isNotEmpty()) CardSummary(remember(item.notes) { markdownSummary(item.notes) })
    }
}

@Composable private fun CoursePreview(value: CourseSetSnapshot, links: List<CourseReplacement>, before: Boolean) {
    val zone = remember(value.zone) { ZoneId.of(value.zone) }
    PreviewTitle(sourceLabel(value.source))
    val start = LocalDate.ofEpochDay(value.firstMonday)
    CardMetadata { Text("$start – ${start.plusWeeks(value.weeks.toLong()).minusDays(1)}"); Text(uiText("${value.weeks} weeks", "${value.weeks} 周")) }
    CardMetadata {
        Text(when (value.initial) {
            io.github.nakanomk.stacklife.courses.BellSchedule.SUMMER -> uiText("Summer bell schedule", "夏季作息")
            io.github.nakanomk.stacklife.courses.BellSchedule.WINTER -> uiText("Winter bell schedule", "冬季作息")
        }); value.switchDay?.let { Text(uiText("Switch on ${LocalDate.ofEpochDay(it)}", "${LocalDate.ofEpochDay(it)} 切换")) }
    }
    val changed = remember(links, before) { links.mapTo(HashSet()) { if (before) it.old else it.new } }
    val byWeek = remember(value.meetings) { value.meetings.sortedWith(compareBy(CourseMeeting::startMillis, CourseMeeting::endMillis, CourseMeeting::title, CourseMeeting::room)).groupBy { it.week } }
    for ((week, meetings) in byWeek) {
        val heading = uiText("Week $week: ${meetings.size} classes", "第 $week 周：${meetings.size} 课次")
        val rows: @Composable () -> Unit = {
            for (m in meetings) {
                val updating = CourseOccurrenceId.of(value.termId, value.source, m) in changed
                Text(m.title, style = MaterialTheme.typography.bodyLarge)
                CardMetadata { Text(if (updating) uiText("Modified class", "修改课次") else if (before) uiText("Removed class", "移除课次") else uiText("Added class", "新增课次")); if (m.room.isNotBlank()) Text(m.room) }
                Text("${snapshotTime(m.startMillis, zone)} – ${Instant.ofEpochMilli(m.endMillis).atZone(zone).toLocalTime()}", style = MaterialTheme.typography.bodyMedium)
                Text(uiText("Periods ${m.firstPeriod}–${m.lastPeriod}", "第 ${m.firstPeriod}–${m.lastPeriod} 节"), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (value.meetings.size > 8) PreviewFold(heading, content = rows)
        else { Text(heading, style = MaterialTheme.typography.titleSmall); rows() }
    }
    if (value.cancelledReminders > 0) Text(uiText("Removed occurrences lose their reminders: ${value.cancelledReminders}", "移除的 ${value.cancelledReminders} 个课次将取消提醒"))
    if (value.conflictCount > 0) PreviewFold(uiText("Overlapping pairs: ${value.conflictCount}", "重叠：${value.conflictCount} 对")) {
        if (value.conflictCount > value.conflicts.size) Text(uiText("First ${value.conflicts.size} pairs", "以下为前 ${value.conflicts.size} 对"))
        for (c in value.conflicts) {
            Text(c.occurrence.title); Text("${snapshotTime(c.startMillis, zone)} – ${snapshotTime(c.endMillis, zone)}", style = MaterialTheme.typography.bodyMedium)
            Text(uiText("Overlaps ${c.other.title}", "与「${c.other.title}」重叠")); Text("${snapshotTime(c.other.startMillis, zone)} – ${snapshotTime(c.other.endMillis, zone)}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
