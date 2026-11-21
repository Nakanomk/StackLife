package io.github.nakanomk.stacklife.workspace

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.nakanomk.stacklife.i18n.AppLocales
import io.github.nakanomk.stacklife.i18n.LanguageChoice
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
        MissingDetail(stringResource(R.string.ui_task_details), stringResource(R.string.ui_task_not_queued), back)
        return
    }
    EditorPage(stringResource(R.string.ui_task_details), back, AssistantPage.Task(id), backEnabled = !action.busy, footer = {
        Actions {
            Primary(stringResource(R.string.ui_finish_task), enabled = !action.busy) { action.send(PlannerCommand.TaskFinish(id)) }
            Secondary(stringResource(R.string.ui_edit_task), enabled = !action.busy, onClick = onEdit)
            Secondary(stringResource(R.string.ui_remove_from_queue), enabled = !action.busy) { action.send(PlannerCommand.TaskRemove(id)) }
        }
    }) { top, bottom ->
        LazyColumn(contentPadding = dockListPadding(top, bottom), verticalArrangement = listSpacing) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (index == 0) Text(stringResource(R.string.ui_current_priority), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(task.title, shared.title("task:$id"), style = MaterialTheme.typography.titleLarge)
                    if (index != 0) Text(stringResource(R.string.ui_queue_position, index + 1), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.ui_duration_estimate, task.minutes), style = MaterialTheme.typography.bodyMedium)
                    if (task.scheduledStartMillis != null && task.scheduledEndMillis != null) {
                        Hint(stringResource(R.string.ui_scheduled_range, scheduledRange(task.scheduledStartMillis, task.scheduledEndMillis)))
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTitle(stringResource(R.string.ui_notes))
                    if (task.notes.isBlank()) Hint(stringResource(R.string.ui_empty_notes)) else Text(task.notes)
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
    EditorPage(stringResource(R.string.ui_settings), onBack, AssistantPage.Settings) { top, bottom ->
            LazyColumn(contentPadding = dockListPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item { SettingsGroup(stringResource(R.string.ui_appearance)) {
                    PreferenceRow(stringResource(R.string.ui_colors), leadingIcon = R.drawable.ic_palette) { onAppearance(AppearanceGroup.COLOR) }
                    HorizontalDivider(Modifier.padding(start = 32.dp))
                    PreferenceRow(stringResource(R.string.ui_layout), leadingIcon = R.drawable.ic_apps) { onAppearance(AppearanceGroup.LAYOUT) }
                    HorizontalDivider(Modifier.padding(start = 32.dp))
                    PreferenceRow(stringResource(R.string.ui_material_motion), leadingIcon = R.drawable.ic_blur_on) { onAppearance(AppearanceGroup.MATERIAL) }
                } }
                item { SettingsGroup(stringResource(R.string.pref_general)) { LanguagePreference() } }
                item { SettingsGroup(stringResource(R.string.ui_assistant)) {
                    PreferenceRow(stringResource(R.string.ui_model_connection), leadingIcon = R.drawable.ic_chat, onClick = onConnection)
                } }
                item { SettingsGroup(stringResource(R.string.ui_restore_policy)) { Column(Modifier.selectableGroup()) {
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
                                    RestorePolicy.PREPEND -> stringResource(R.string.ui_prepend)
                                    RestorePolicy.APPEND -> stringResource(R.string.ui_append)
                                    RestorePolicy.SWAP -> stringResource(R.string.ui_swap)
                                }, style = MaterialTheme.typography.bodyLarge)
                                Text(when (policy) {
                                    RestorePolicy.PREPEND -> stringResource(R.string.ui_prepend_hint)
                                    RestorePolicy.APPEND -> stringResource(R.string.ui_append_hint)
                                    RestorePolicy.SWAP -> stringResource(R.string.ui_swap_hint)
                                }, style = MaterialTheme.typography.bodyMedium, color = muted)
                            }
                        }
                    }
                } } }
            }
    }
}

@Composable
private fun LanguagePreference() {
    val activity = LocalActivity.current ?: return
    var selected by remember(activity) { mutableStateOf(AppLocales.choice(activity)) }
    LifecycleResumeEffect(activity) {
        selected = AppLocales.choice(activity)
        onPauseOrDispose {}
    }
    Choice(stringResource(R.string.pref_language), LanguageChoice.entries, selected, { choice ->
        when (choice) {
            LanguageChoice.SYSTEM -> stringResource(R.string.pref_follow_system)
            LanguageChoice.ENGLISH -> "English"
            LanguageChoice.CHINESE -> "中文"
        }
    }, Modifier.testTag("language-setting"), R.drawable.ic_language) { choice ->
        selected = choice
        AppLocales.set(activity, choice)
    }
}

@Composable
private fun SettingsGroup(label: String, rows: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(label)
        FormGroup(content = rows)
    }
}
