package io.github.nakanomk.stacklife.workspace

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.today.SlotEditorKey
import io.github.nakanomk.stacklife.reminders.ReminderRulesEditor
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.reminders.ReminderPreference
import io.github.nakanomk.stacklife.transitions.title
import io.github.nakanomk.stacklife.transitions.detailBodyMotion
import io.github.nakanomk.stacklife.transitions.routeOrigin
import io.github.nakanomk.stacklife.notes.CurrentTaskNote
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun TaskScreen(id: String, view: SourceView, actions: WorkspaceActions, onBack: () -> Unit,
    shared: SharedModifier, onEdit: () -> Unit) {
    val source = view.source
    val index = source?.queuePosition
    val task = source?.let { QueueTask(id, it.title, it.notes, it.minutes, it.schedule) }
    // Room may publish the removal before our receipt arrives. Await it even without a card.
    val action = rememberSubmission(actions) { if (it != SaveOutcome.REJECTED) onBack() }
    val back = { if (!action.busy) onBack() }
    BackHandler(enabled = action.busy) { /* The receipt navigates exactly once. */ }
    if (source == null || task == null) {
        MissingDetail(stringResource(R.string.ui_task_details), stringResource(R.string.graph_source_removed), back)
        return
    }
    EditorPage(stringResource(R.string.ui_task_details), back, AssistantPage.Task(id), backEnabled = !action.busy, footer = {
        Actions {
            if (source.owner != Owner.DONE) Primary(stringResource(if (task.schedule?.pattern is SchedulePattern.Daily) R.string.slot_finish_series else R.string.ui_finish_task), enabled = !action.busy) { action.send(PlannerCommand.TaskFinish(id)) }
            ActionPill(stringResource(R.string.ui_edit_task), onEdit, Modifier.routeOrigin("edit:$id", CircleShape), enabled = !action.busy)
            if (source.owner == Owner.QUEUE) Secondary(stringResource(R.string.ui_remove_from_queue), enabled = !action.busy) { action.send(PlannerCommand.TaskRemove(id)) }
        }
    }) { top, bottom ->
        LazyColumn(Modifier.detailBodyMotion(), contentPadding = dockListPadding(top, bottom), verticalArrangement = listSpacing) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (index == 0) Text(stringResource(R.string.ui_current_priority), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(task.title, shared.title("task:$id"), style = MaterialTheme.typography.titleLarge)
                    if (index != null && index != 0) Text(stringResource(R.string.ui_queue_position, index + 1), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (source.owner == Owner.FRAME) Text(stringResource(R.string.graph_stays_frame), style = MaterialTheme.typography.labelMedium)
                    if (source.owner == Owner.DONE) Text(stringResource(R.string.today_done), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.ui_duration_estimate, task.minutes), style = MaterialTheme.typography.bodyMedium)
                    task.schedule?.let { Hint(scheduleText(it)) }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTitle(stringResource(R.string.ui_notes))
                    CurrentTaskNote(task, view.revision, enabled = !action.busy, frameId = source.frameId)
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

internal const val WallpaperSettingsOrigin = "appearance:wallpaper"

@Composable
internal fun SettingsScreen(settings: PolicyView, actions: WorkspaceViewModel, onBack: () -> Unit,
    onConnection: () -> Unit, onAppearance: (AppearanceGroup) -> Unit, onWallpaper: () -> Unit,
    onAbout: () -> Unit, onFeature: (Destination) -> Unit, onSchedule: (SlotEditorKey) -> Unit) {
    val action = rememberSubmission(actions)
    EditorPage(stringResource(R.string.ui_settings), onBack, AssistantPage.Settings) { top, bottom ->
            LazyColumn(contentPadding = dockListPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item { SettingsGroup(stringResource(R.string.agenda_daily_routine)) {
                    RoutineScheduleRows(actions, onSchedule)
                    ReminderPreference()
                    ReminderRulesEditor(actions, ReminderSubject.CourseDefault)
                } }
                item { SettingsGroup(stringResource(R.string.ui_appearance)) {
                    PreferenceRow(stringResource(R.string.ui_colors), modifier = Modifier.routeOrigin("appearance:${AppearanceGroup.COLOR.key}"),
                        leadingIcon = R.drawable.ic_palette) { onAppearance(AppearanceGroup.COLOR) }
                    HorizontalDivider(Modifier.padding(start = 32.dp))
                    PreferenceRow(stringResource(R.string.ui_layout), modifier = Modifier.routeOrigin("appearance:${AppearanceGroup.LAYOUT.key}"),
                        leadingIcon = R.drawable.ic_apps) { onAppearance(AppearanceGroup.LAYOUT) }
                    HorizontalDivider(Modifier.padding(start = 32.dp))
                    PreferenceRow(stringResource(R.string.ui_material_motion), modifier = Modifier.routeOrigin("appearance:${AppearanceGroup.MATERIAL.key}"),
                        leadingIcon = R.drawable.ic_blur_on) { onAppearance(AppearanceGroup.MATERIAL) }
                    HorizontalDivider(Modifier.padding(start = 32.dp))
                    PreferenceRow(stringResource(R.string.wallpaper_title), modifier = Modifier.routeOrigin(WallpaperSettingsOrigin)
                        .testTag("settings-wallpaper"), leadingIcon = R.drawable.ic_wallpaper, onClick = onWallpaper)
                } }
                item { SettingsGroup(stringResource(R.string.pref_general)) {
                    LanguagePreference()
                    HorizontalDivider(Modifier.padding(start = 32.dp))
                    PreferenceRow(stringResource(R.string.about_title), modifier = Modifier.routeOrigin("about").testTag("settings-about"),
                        leadingIcon = R.drawable.ic_info, onClick = onAbout)
                } }
                item { SettingsGroup(stringResource(R.string.ui_assistant)) {
                    PreferenceRow(stringResource(R.string.ui_model_connection), modifier = Modifier.routeOrigin("model"),
                        leadingIcon = R.drawable.ic_chat, onClick = onConnection)
                } }
                item { SettingsGroup(stringResource(R.string.feature_entries)) {
                    for (dest in settings.appearance.order) PreferenceRow(dest.label, if (dest in settings.appearance.visible) null else stringResource(R.string.feature_hidden),
                        Modifier.routeOrigin("feature:${dest.key}").testTag("open-feature:${dest.key}")) { onFeature(dest) }
                } }
                item { SettingsGroup(stringResource(R.string.ui_restore_policy)) { Column(Modifier.selectableGroup()) {
                    for (policy in RestorePolicy.entries) {
                        val selected = settings.policy == policy
                        Row(Modifier.fillMaxWidth().selectable(selected, enabled = !action.busy, role = Role.RadioButton,
                            onClick = { if (!selected) action.send(PlannerCommand.SetRestorePolicy(policy)) })
                            .heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(selected = selected, onClick = null)
                            SettingLabel(stringResource(when (policy) {
                                RestorePolicy.PREPEND -> R.string.ui_prepend
                                RestorePolicy.APPEND -> R.string.ui_append
                                RestorePolicy.SWAP -> R.string.ui_swap
                            }), stringResource(when (policy) {
                                RestorePolicy.PREPEND -> R.string.ui_prepend_hint
                                RestorePolicy.APPEND -> R.string.ui_append_hint
                                RestorePolicy.SWAP -> R.string.ui_swap_hint
                            }))
                        }
                    }
                } } }
            }
    }
}

@Composable
internal fun RoutineScheduleRows(actions: WorkspaceViewModel, onSchedule: (SlotEditorKey) -> Unit) {
    for (kind in RoutineKind.entries) {
        val wake = kind == RoutineKind.WAKE
        val summary by actions.routineSchedules.getValue(kind).collectAsStateWithLifecycle()
        ReadPanel(summary) { view ->
            val title = stringResource(if (wake) R.string.agenda_wake else R.string.agenda_sleep)
            PreferenceRow(title, view.summary?.takeIf { it.enabled }?.let { scheduleText(it) } ?: stringResource(R.string.wheel_unset),
                Modifier.testTag(if (wake) "routine-wake" else "routine-sleep").routeOrigin("slot:${kind.scheduleId}"),
                leadingIcon = R.drawable.ic_schedule, valueMaxLines = 2) {
                // The date is an editor argument, not row content. Resolve it at
                // the tap instead of registering a receiver and midnight timer per row.
                val zone = ZoneId.systemDefault()
                onSchedule(SlotEditorKey(kind.scheduleId, title, LocalDate.now(zone).toEpochDay(), zone.id, 0, view.revision, role = kind))
            }
        }
        HorizontalDivider(Modifier.padding(start = 32.dp))
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
        FormGroup(frosted = true, content = rows)
    }
}
