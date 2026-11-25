package io.github.nakanomk.stacklife.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.i18n.AppLocales
import io.github.nakanomk.stacklife.i18n.LanguageChoice
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.reminders.ReminderPreference
import io.github.nakanomk.stacklife.reminders.ReminderRulesEditor
import io.github.nakanomk.stacklife.today.SlotEditorKey
import io.github.nakanomk.stacklife.transitions.routeOrigin
import io.github.nakanomk.stacklife.workspace.Choice
import io.github.nakanomk.stacklife.workspace.EditorPage
import io.github.nakanomk.stacklife.workspace.FormGroup
import io.github.nakanomk.stacklife.workspace.PreferenceRow
import io.github.nakanomk.stacklife.workspace.SectionTitle
import io.github.nakanomk.stacklife.workspace.SettingLabel
import io.github.nakanomk.stacklife.workspace.dockListPadding
import io.github.nakanomk.stacklife.workspace.rememberSubmission
import io.github.nakanomk.stacklife.workspace.scheduleText
import java.time.LocalDate
import java.time.ZoneId

internal const val WallpaperSettingsOrigin = "appearance:wallpaper"

@Composable
internal fun SettingsScreen(settings: PolicyView, actions: WorkspaceViewModel, onBack: () -> Unit,
    onConnection: () -> Unit, onAppearance: (AppearanceGroup) -> Unit, onWallpaper: () -> Unit,
    onAbout: () -> Unit, onUpdates: (() -> Unit)?, onBackup: (() -> Unit)?, assistantPreference: @Composable () -> Unit,
    onFeature: (Destination) -> Unit, onSchedule: (SlotEditorKey) -> Unit) {
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
                    if (onUpdates != null) {
                        HorizontalDivider(Modifier.padding(start = 32.dp))
                        PreferenceRow(stringResource(R.string.update_title), modifier = Modifier.routeOrigin("updates").testTag("settings-updates"),
                            leadingIcon = R.drawable.ic_system_update_alt, onClick = onUpdates)
                    }
                    if (onBackup != null) {
                        HorizontalDivider(Modifier.padding(start = 32.dp))
                        PreferenceRow(io.github.nakanomk.stacklife.archive.backupTitle, modifier = Modifier.routeOrigin("backup").testTag("settings-backup"),
                            leadingIcon = R.drawable.ic_apps, onClick = onBackup)
                    }
                } }
                item { SettingsGroup(stringResource(R.string.ui_assistant)) {
                    assistantPreference()
                    HorizontalDivider(Modifier.padding(start = 32.dp))
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
                }
                    HorizontalDivider()
                    io.github.nakanomk.stacklife.appearance.SwitchRow(io.github.nakanomk.stacklife.i18n.uiText("Keep restored frames", "恢复后保留栈帧"),
                        settings.keepRestoredFrames, enabled = !action.busy,
                        help = io.github.nakanomk.stacklife.i18n.uiText("Keep the historical snapshot after the last restorable task leaves.", "取出最后一个可恢复任务后，保留原帧快照。")) {
                        action.send(PlannerCommand.KeepRestoredFrames(!settings.keepRestoredFrames))
                    }
                } }
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
