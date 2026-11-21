package io.github.nakanomk.stacklife.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.planner.AppearanceGroup
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.RestorePolicy
import io.github.nakanomk.stacklife.planner.Workspace
import io.github.nakanomk.stacklife.transitions.title

@Composable
internal fun TaskScreen(id: String, state: Workspace, actions: WorkspaceActions, onBack: () -> Unit,
    shared: SharedModifier, onEdit: () -> Unit) {
    val index = state.queue.indexOfFirst { it.id == id }
    val task = state.queue.getOrNull(index)
    // Room may publish the removal before our receipt arrives. Await it even without a card.
    val action = rememberSubmission(actions) { if (it != SaveOutcome.REJECTED) onBack() }
    val back = { if (!action.busy) onBack() }
    BackHandler(enabled = action.busy) { /* The receipt navigates exactly once. */ }
    if (task == null) {
        MissingDetail("任务详情", "这项任务已不在队列中。", back)
        return
    }
    EditorPage("任务详情", back, AssistantPage.Task(id), backEnabled = !action.busy, footer = {
        Actions {
            Primary("完成任务", enabled = !action.busy) { action.send(PlannerCommand.TaskFinish(id)) }
            Secondary("编辑任务", enabled = !action.busy, onClick = onEdit)
            Secondary("移出队列", enabled = !action.busy) { action.send(PlannerCommand.TaskRemove(id)) }
        }
    }) { top, bottom ->
        LazyColumn(contentPadding = dockListPadding(top, bottom), verticalArrangement = listSpacing) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (index == 0) Text("当前优先", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(task.title, shared.title("task:$id"), style = MaterialTheme.typography.titleLarge)
                    if (index != 0) Text("队列第 ${index + 1} 项", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("预计 ${task.minutes} 分钟", style = MaterialTheme.typography.bodyMedium)
                    if (task.scheduledStartMillis != null && task.scheduledEndMillis != null) {
                        Hint("已安排 ${scheduledRange(task.scheduledStartMillis, task.scheduledEndMillis)}")
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTitle("备注")
                    if (task.notes.isBlank()) Hint("尚未填写备注") else Text(task.notes)
                }
            }
        }
    }
}

@Composable
internal fun MissingDetail(title: String, message: String, onBack: () -> Unit) {
    EditorPage(title, onBack) { top, bottom ->
        LazyColumn(contentPadding = dockListPadding(top, bottom), verticalArrangement = listSpacing) {
            item { Hint(message) }
        }
    }
}

@Composable
internal fun SettingsScreen(restorePolicy: RestorePolicy, actions: WorkspaceActions, onBack: () -> Unit,
    onConnection: () -> Unit, onAppearance: (AppearanceGroup) -> Unit) {
    val action = rememberSubmission(actions)
    EditorPage("设置", onBack, AssistantPage.Settings) { top, bottom ->
            LazyColumn(contentPadding = dockListPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item { SettingsGroup("外观") {
                    PreferenceRow("色彩", leadingIcon = R.drawable.ic_palette) { onAppearance(AppearanceGroup.COLOR) }
                    HorizontalDivider(Modifier.padding(start = 32.dp))
                    PreferenceRow("布局", leadingIcon = R.drawable.ic_apps) { onAppearance(AppearanceGroup.LAYOUT) }
                    HorizontalDivider(Modifier.padding(start = 32.dp))
                    PreferenceRow("材质与动态", leadingIcon = R.drawable.ic_blur_on) { onAppearance(AppearanceGroup.MATERIAL) }
                } }
                item { SettingsGroup("日程助理") {
                    PreferenceRow("模型服务配置", leadingIcon = R.drawable.ic_chat, onClick = onConnection)
                } }
                item { SettingsGroup("工作台 · 恢复策略") { Column(Modifier.selectableGroup()) {
                    for (policy in RestorePolicy.entries) {
                        val selected = restorePolicy == policy
                        val muted = MaterialTheme.colorScheme.onSurfaceVariant
                        Row(Modifier.fillMaxWidth().selectable(selected, enabled = !action.busy, role = Role.RadioButton,
                            onClick = { if (!selected) action.send(PlannerCommand.SetRestorePolicy(policy)) })
                            .heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(selected = selected, onClick = null)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(when (policy) {
                                    RestorePolicy.PREPEND -> "插入队首"
                                    RestorePolicy.APPEND -> "追加队尾"
                                    RestorePolicy.SWAP -> "整帧切换"
                                }, style = MaterialTheme.typography.bodyLarge)
                                Text(when (policy) {
                                    RestorePolicy.PREPEND -> "先做恢复的任务，再继续眼前的队列。"
                                    RestorePolicy.APPEND -> "保持眼前任务优先，把恢复的任务接在后面。"
                                    RestorePolicy.SWAP -> "全部恢复前将非空队列另存为一帧；选择恢复只插队首，不另存。"
                                }, style = MaterialTheme.typography.bodyMedium, color = muted)
                            }
                        }
                    }
                } } }
            }
    }
}

@Composable
private fun SettingsGroup(label: String, rows: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(label)
        FormGroup(content = rows)
    }
}
