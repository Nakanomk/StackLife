package io.github.nakanomk.stacklife.reminders

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeApplication
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.workspace.PreferenceRow

@Composable
internal fun ReminderPreference() {
    val activity = LocalActivity.current ?: return
    val reminders = (activity.application as StackLifeApplication).reminders
    val failed by reminders.schedulingFailed.collectAsStateWithLifecycle()
    val prefs = remember(activity) { activity.getSharedPreferences("reminder_permission", Context.MODE_PRIVATE) }
    var allowed by remember { mutableStateOf(reminders.canPost()) }
    var exact by remember { mutableStateOf(reminders.canBeExact()) }
    var open by rememberSaveable { mutableStateOf(false) }
    var unavailable by rememberSaveable { mutableStateOf(false) }
    var asked by remember { mutableStateOf(prefs.getBoolean("requested", false)) }
    fun update() { allowed = reminders.canPost(); exact = reminders.canBeExact() }
    LifecycleResumeEffect(activity) { update(); onPauseOrDispose {} }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        prefs.edit().putBoolean("requested", true).apply()
        update()
        reminders.refreshAsync()
    }
    fun launch(intent: Intent) {
        try { activity.startActivity(intent); unavailable = false }
        catch (_: ActivityNotFoundException) { unavailable = true }
    }
    val status = when {
        failed -> R.string.reminder_failed
        !allowed -> R.string.reminder_off
        !exact -> R.string.reminder_inexact
        else -> R.string.reminder_on
    }
    PreferenceRow(stringResource(R.string.reminder_channel), stringResource(status), leadingIcon = R.drawable.ic_schedule,
        help = stringResource(R.string.reminder_explanation)) {
        update(); open = true; unavailable = false
    }
    if (!open) return
    PlannerAlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.reminder_channel)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (failed) TextButton(onClick = { reminders.refreshAsync() }) { Text(stringResource(R.string.reminder_retry)) }
            if (!allowed) Text(stringResource(R.string.reminder_blocked_hint))
            if (!exact) Text(stringResource(R.string.reminder_inexact_hint))
            val runtime = Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED && (!asked || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
            TextButton(onClick = {
                if (runtime) request.launch(Manifest.permission.POST_NOTIFICATIONS) else {
                    val enabled = checkNotNull(activity.getSystemService(NotificationManager::class.java)).areNotificationsEnabled()
                    val page = if (enabled) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    launch(Intent(page).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, REMINDER_CHANNEL))
                }
            }) { Text(stringResource(if (runtime) R.string.reminder_allow else R.string.reminder_system_notifications)) }
            if (Build.VERSION.SDK_INT >= 31 && !exact) TextButton(onClick = {
                launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", activity.packageName, null)))
            }) { Text(stringResource(R.string.reminder_allow_exact)) }
            if (unavailable) Text(stringResource(R.string.reminder_settings_missing))
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.ui_done)) } })
}
