package io.github.nakanomk.stacklife.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.planner.AppearanceGroup
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.RestorePolicy
import io.github.nakanomk.stacklife.planner.Workspace

@Composable
fun TaskScreen(id: String, state: Workspace, actions: WorkspaceActions, onBack: () -> Unit, shared: SharedModifier) {
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
    var editing by rememberSaveable(id) { mutableStateOf(false) }
    if (editing) {
        TaskEditor(actions, { editing = false }, original = task)
        return
    }
    Page(AssistantPage.Task(id)) {
        LazyColumn(contentPadding = pagePadding, verticalArrangement = listSpacing) {
            item { PageTitle("任务详情", back = back) }
            item {
                Surface(modifier = shared("task:$id").fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (index == 0) "当前优先" else "队列第 ${index + 1} 项", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(task.title, style = MaterialTheme.typography.titleLarge)
                        Text("预计 ${task.minutes} 分钟", style = MaterialTheme.typography.bodyMedium)
                        if (task.scheduledStartMillis != null && task.scheduledEndMillis != null) {
                            Hint("已安排 ${scheduledRange(task.scheduledStartMillis, task.scheduledEndMillis)}")
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTitle("备注")
                    if (task.notes.isBlank()) Hint("尚未填写备注") else Text(task.notes)
                }
            }
            item {
                Actions {
                    Primary("完成任务", enabled = !action.busy) { action.send(PlannerCommand.TaskFinish(id)) }
                    Secondary("编辑任务", enabled = !action.busy) { editing = true }
                    TextButton(modifier = Modifier.heightIn(min = 48.dp), enabled = !action.busy,
                        onClick = { action.send(PlannerCommand.TaskRemove(id)) }) { Text("移出队列") }
                }
            }
        }
    }
}

@Composable
internal fun MissingDetail(title: String, message: String, onBack: () -> Unit) {
    Page {
        LazyColumn(contentPadding = pagePadding, verticalArrangement = listSpacing) {
            item { PageTitle(title, back = onBack) }
            item { Hint(message) }
        }
    }
}

@Composable
internal fun SettingsScreen(restorePolicy: RestorePolicy, actions: WorkspaceActions, onBack: () -> Unit,
    onConnection: () -> Unit, onAppearance: (AppearanceGroup) -> Unit) {
    val action = rememberSubmission(actions)
    Page(AssistantPage.Settings) {
        PageTitle("设置", back = onBack)
        LazyColumn(contentPadding = pagePadding) {
            item { SectionTitle("外观") }
            item { PreferenceRow("色彩") { onAppearance(AppearanceGroup.COLOR) } }
            item { PreferenceRow("布局") { onAppearance(AppearanceGroup.LAYOUT) } }
            item { PreferenceRow("材质与动态") { onAppearance(AppearanceGroup.MATERIAL) } }
            item { HorizontalDivider(); Spacer(Modifier.height(12.dp)); SectionTitle("日程助理") }
            item { PreferenceRow("模型服务配置", onClick = onConnection) }
            item { HorizontalDivider(); Spacer(Modifier.height(12.dp)); SectionTitle("工作台 · 恢复策略") }
            item {
                Column(Modifier.selectableGroup()) {
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
                }
            }
        }
    }
}
