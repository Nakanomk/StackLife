package io.github.nakanomk.stacklife.updates

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeApplication
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.appearance.SwitchRow
import io.github.nakanomk.stacklife.assistant.LocalAssistant
import io.github.nakanomk.stacklife.workspace.*

@Composable
private fun updates(): AppUpdates? {
    val app = LocalContext.current.applicationContext as StackLifeApplication
    return if (app.packageName == RELEASE_PACKAGE) app.updates else null
}

@Composable
internal fun UpdateSettings() {
    val updates = updates() ?: return
    val status by updates.status.collectAsStateWithLifecycle()
    FormGroup(frosted = true) {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle(stringResource(R.string.update_title))
            Text(statusLabel(status), style = MaterialTheme.typography.bodyMedium,
                color = if (status.stage == UpdateStage.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SwitchRow(stringResource(R.string.update_auto_check), updates.autoCheck, help = stringResource(R.string.update_check_help)) {
            updates.setChecking(!updates.autoCheck)
        }
        SwitchRow(stringResource(R.string.update_auto_install), updates.autoInstall, enabled = updates.autoCheck,
            help = stringResource(R.string.update_install_help)) { updates.setInstalling(!updates.autoInstall) }
        HorizontalDivider()
        PreferenceRow(stringResource(R.string.update_check_now), modifier = Modifier.testTag("update-check"),
            labelMaxLines = Int.MAX_VALUE) { updates.checkRelease() }
        if (status.release != null) PreferenceRow(stringResource(R.string.update_details),
            labelMaxLines = Int.MAX_VALUE, onClick = updates::show)
    }
}

@Composable
private fun statusLabel(s: UpdateStatus): String = when (s.stage) {
    UpdateStage.AVAILABLE -> stringResource(R.string.update_available, requireNotNull(s.release).version)
    UpdateStage.DOWNLOADING -> stringResource(R.string.update_progress, s.progress)
    else -> stringResource(when (s.stage) {
        UpdateStage.IDLE -> R.string.update_source
        UpdateStage.CHECKING -> R.string.update_checking
        UpdateStage.CURRENT -> R.string.update_current
        UpdateStage.READY -> R.string.update_verified
        UpdateStage.PERMISSION -> R.string.update_permission
        UpdateStage.INSTALLING -> R.string.update_installing
        UpdateStage.CONFIRM -> R.string.update_confirm
        UpdateStage.RECOVER -> R.string.update_recover
        UpdateStage.FAILED -> s.problem
        UpdateStage.UPDATED -> R.string.update_installed
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UpdateHost(atHome: Boolean, inAbout: Boolean, allowPresentation: Boolean) {
    val updates = updates() ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState().value
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(atHome, resumed) { if (atHome && resumed) updates.enterHome() }
    val status by updates.status.collectAsStateWithLifecycle()
    if (!allowPresentation || (!atHome && !inAbout) || !status.dialog) return
    val chat = LocalAssistant.current?.state?.collectAsStateWithLifecycle()
    val chatting by remember(chat) { derivedStateOf { chat?.value?.page != null } }
    if (chatting || WindowInsets.isImeVisible) return
    val context = LocalContext.current
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { updates.returnedFromPermission() }
    fun system(action: () -> Unit) {
        try { action() } catch (_: ActivityNotFoundException) { updates.systemFailed() } catch (_: SecurityException) { updates.systemFailed() }
    }
    LaunchedEffect(resumed, status.stage) {
        if (resumed) when (status.stage) {
            UpdateStage.READY -> updates.installWhenVisible()
            UpdateStage.CONFIRM -> updates.takeConfirmation()?.let { system { context.startActivity(it) } }
            else -> Unit
        }
    }
    PlannerAlertDialog(onDismissRequest = updates::later, title = { Text(stringResource(R.string.update_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(statusLabel(status), Modifier.testTag("update-status"))
            if (status.stage == UpdateStage.CHECKING) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.stage == UpdateStage.DOWNLOADING) LinearProgressIndicator(progress = { status.progress / 100f }, modifier = Modifier.fillMaxWidth())
            if (status.stage == UpdateStage.AVAILABLE) status.release?.let { info ->
                Text(stringResource(R.string.update_size, android.text.format.Formatter.formatShortFileSize(context, info.size)), style = MaterialTheme.typography.labelLarge)
                if (info.notes.isNotBlank()) Text(info.notes, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }, confirmButton = {
        when (status.stage) {
            UpdateStage.AVAILABLE, UpdateStage.RECOVER -> TextButton(onClick = { updates.begin() }) { Text(stringResource(R.string.update_get)) }
            UpdateStage.PERMISSION -> TextButton(onClick = { system { settings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) } }) {
                Text(stringResource(R.string.update_authorize))
            }
            UpdateStage.FAILED -> TextButton(onClick = { if (status.release == null) updates.checkRelease() else updates.begin() }) { Text(stringResource(R.string.update_retry)) }
            else -> Unit
        }
    }, dismissButton = { TextButton(onClick = updates::later) {
        Text(stringResource(when (status.stage) {
            UpdateStage.AVAILABLE, UpdateStage.RECOVER, UpdateStage.PERMISSION -> R.string.update_later
            UpdateStage.DOWNLOADING -> R.string.ui_cancel
            else -> R.string.update_close
        }))
    } })
}
