package io.github.nakanomk.stacklife.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.planner.TaskInput

private data class Draft(
    val key: Int,
    val title: TextFieldValue = TextFieldValue(),
    val notes: TextFieldValue = TextFieldValue(),
    val minutes: TextFieldValue = TextFieldValue(),
) {
    fun duration(): Int? = if (minutes.text.isBlank()) 30 else minutes.text.toIntOrNull()
}

private fun SaverScope.saveField(field: TextFieldValue): Any = with(TextFieldValue.Saver) { checkNotNull(save(field)) }
private fun Any.restoreField(): TextFieldValue = checkNotNull(TextFieldValue.Saver.restore(this))

private val draftSaver = listSaver<SnapshotStateList<Draft>, Any>(
    save = { rows -> rows.flatMap { listOf(it.key, saveField(it.title), saveField(it.notes), saveField(it.minutes)) } },
    restore = { values -> values.chunked(4).map { Draft(it[0] as Int, it[1].restoreField(), it[2].restoreField(), it[3].restoreField()) }.toMutableStateList() },
)

@Composable
internal fun TaskEditor(
    actions: WorkspaceActions,
    onClose: () -> Unit,
    original: QueueTask? = null,
    createFrame: Boolean = false,
) {
    val action = rememberSubmission(actions) { if (it != SaveOutcome.REJECTED) onClose() }
    val drafts = rememberSaveable(saver = draftSaver) {
        mutableStateListOf(if (original == null) Draft(0) else Draft(0, TextFieldValue(original.title), TextFieldValue(original.notes), TextFieldValue(original.minutes.toString())))
    }
    var nextKey by rememberSaveable { mutableIntStateOf(1) }
    var reason by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val scroll = rememberLazyListState()
    val close = { if (!action.busy) onClose() }
    BackHandler(onBack = close)
    Page {
        LazyColumn(state = scroll, contentPadding = pagePadding, verticalArrangement = listSpacing) {
            item {
                PageTitle(if (createFrame) "新建栈帧" else if (original != null) "编辑任务" else "新增任务", back = close)
            }
            if (createFrame) item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Hint("按想要恢复的顺序添加任务。这份清单直接留在栈中，不改变当前队列。")
                    ReasonField(reason) { reason = it }
                }
            }
            itemsIndexed(drafts, key = { _, draft -> draft.key }) { index, row ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (createFrame) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("任务 ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (drafts.size > 1) TextButton(
                                enabled = !action.busy, modifier = Modifier.heightIn(min = 48.dp), onClick = { drafts.remove(row) },
                            ) { Text("删除此项") }
                        }
                        OutlinedTextField(
                            value = row.title, onValueChange = { drafts[index] = row.copy(title = it) },
                            modifier = Modifier.fillMaxWidth().testTag("title:$index"), label = { Text("标题") }, singleLine = true,
                        )
                        OutlinedTextField(
                            value = row.notes, onValueChange = { drafts[index] = row.copy(notes = it) },
                            modifier = Modifier.fillMaxWidth().testTag("notes:$index"), label = { Text("备注（选填）") },
                            minLines = 2, maxLines = 5,
                        )
                        OutlinedTextField(
                            value = row.minutes, onValueChange = { drafts[index] = row.copy(minutes = it) },
                            modifier = Modifier.fillMaxWidth().testTag("minutes:$index"), label = { Text("预计分钟") },
                            placeholder = { Text("30") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = row.duration() == null,
                            supportingText = { Text(if (row.duration() == null) "请输入有效的整数分钟" else "留空按 30 分钟计算") },
                        )
                    }
                }
            }
            item {
                Actions {
                    if (createFrame) Secondary("继续添加任务", enabled = !action.busy) {
                        drafts.add(Draft(nextKey++))
                        // Two leading rows: page heading and frame reason.
                        scroll.requestScrollToItem(2 + drafts.lastIndex)
                    }
                    Primary(if (createFrame) "保存栈帧" else if (original != null) "保存修改" else "添加到队列",
                        enabled = !action.busy && drafts.all { it.duration() != null },
                    ) {
                        val inputs = drafts.map { TaskInput(it.title.text, it.notes.text, checkNotNull(it.duration())) }
                        val command = when {
                            createFrame -> PlannerCommand.FrameCreate(reason.text, inputs)
                            original != null -> PlannerCommand.TaskEdit(original.id, inputs.single())
                            else -> PlannerCommand.TaskAdd(inputs.single())
                        }
                        action.send(command)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasonField(reason: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
    OutlinedTextField(
        value = reason, onValueChange = onChange, modifier = Modifier.fillMaxWidth().testTag("reason"),
        label = { Text("保存原因") }, placeholder = { Text("为什么把这些任务留到稍后？") }, maxLines = 4,
    )
}

@Composable
internal fun PushEditor(queue: List<QueueTask>, actions: WorkspaceActions, onClose: () -> Unit) {
    val action = rememberSubmission(actions) { if (it != SaveOutcome.REJECTED) onClose() }
    var reason by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var selected by rememberSaveable { mutableStateOf(ArrayList(queue.map { it.id })) }
    val close = { if (!action.busy) onClose() }
    BackHandler(onBack = close)
    Page {
        LazyColumn(Modifier.weight(1f), contentPadding = pagePadding, verticalArrangement = listSpacing) {
            item { PageTitle("压栈", back = close) }
            item { ReasonField(reason) { reason = it } }
            item {
                SectionTitle("保存哪些任务 · 已选 ${selected.size} 项")
                Hint("默认保存整个队列，也可以取消勾选。任务将按此处的顺序保存。")
            }
            items(queue, key = { it.id }) { task ->
                CheckRow(task.title, task.id in selected) { selected = ArrayList(selected.toggle(task.id)) }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Primary("保存到栈", enabled = !action.busy && selected.isNotEmpty()) {
                action.send(PlannerCommand.FramePush(reason.text, selected.toList()))
            }
        }
    }
}
