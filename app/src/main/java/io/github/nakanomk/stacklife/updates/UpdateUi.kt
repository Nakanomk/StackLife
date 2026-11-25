package io.github.nakanomk.stacklife.updates

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.R
import java.time.Instant
import io.github.nakanomk.stacklife.appearance.SwitchRow
import io.github.nakanomk.stacklife.workspace.*

/** BiliPai c7f835b34d8323358be57be298f86f29fecb53c8 (GPL-3.0),
 * app/src/main/java/com/android/purebilibili/feature/settings/screen/SettingsScreen.kt:
 * explicit check and device-local channel/automatic preferences. No check is tied to composition. */
@Composable
internal fun UpdateScreen(updates: AppUpdates, onBack: () -> Unit) {
    val status by updates.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionRequest by rememberSaveable { mutableLongStateOf(-1L) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updates.returnedFromPermission(permissionRequest)
        permissionRequest = -1L
    }
    fun system(action: () -> Unit) {
        try { action() } catch (_: ActivityNotFoundException) { updates.systemFailed() } catch (_: SecurityException) { updates.systemFailed() }
    }
    SettingsHelpHost {
        EditorPage(stringResource(R.string.update_title), onBack) { top, bottom ->
            LazyColumn(Modifier.fillMaxSize().testTag("updates-page"), contentPadding = dockListPadding(top, bottom),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { FormGroup(frosted = true) {
                    SectionTitle(stringResource(R.string.update_source))
                    Hint(stringResource(R.string.update_installed_version, updates.installed.name ?: "—", updates.installed.code))
                    Choice(stringResource(R.string.update_channel), UpdateChannel.entries, updates.channel,
                        { stringResource(if (it == UpdateChannel.STABLE) R.string.update_stable else R.string.update_beta) },
                        modifier = Modifier.testTag("update-channel"), help = stringResource(R.string.update_channel_help),
                        change = updates::selectChannel)
                    if (updates.migrated) Hint(stringResource(R.string.update_migrated))
                    SwitchRow(stringResource(R.string.update_auto_check), updates.autoCheck, help = stringResource(R.string.update_check_help)) {
                        updates.setChecking(!updates.autoCheck)
                    }
                    SwitchRow(stringResource(R.string.update_auto_install), updates.autoInstall, enabled = updates.autoCheck,
                        help = stringResource(R.string.update_install_help)) { updates.setInstalling(!updates.autoInstall) }
                } }
                item { FormGroup(frosted = true) {
                    SectionTitle(stringResource(R.string.update_result))
                    if (status.checking) {
                        Text(stringResource(R.string.update_checking))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    val last = status.last
                    if (last == null && !status.checking) Hint(stringResource(R.string.update_not_checked))
                    if (last != null) {
                        if (last.time > 0L) Hint(stringResource(if (last.cached || status.checking) R.string.update_cached_at else R.string.update_checked_at,
                            localTime(last.time)))
                        else if (last.cached) Hint(stringResource(R.string.update_cached))
                        val offer = last.offer
                        Text(when {
                            last.problem != null -> stringResource(last.problem.messageId())
                            offer != null -> stringResource(R.string.update_available, offer.version, offer.versionCode)
                            else -> stringResource(R.string.update_current)
                        }, Modifier.testTag("update-status"),
                            color = if (last.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        if (offer != null) {
                            val time = offer.publishedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                            Hint(stringResource(R.string.update_published, if (time == null) "—" else localTime(time)))
                            Hint(stringResource(R.string.update_size, Formatter.formatShortFileSize(context, offer.size)))
                            if (offer.notes.isNotBlank()) Text(offer.notes, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    when (status.preparation) {
                        Preparation.DOWNLOADING -> {
                            Text(stringResource(R.string.update_progress, status.progress))
                            LinearProgressIndicator(progress = { status.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                        Preparation.SUBMITTING -> Hint(stringResource(R.string.update_installing))
                        Preparation.PERMISSION -> {
                            Hint(stringResource(R.string.update_permission))
                            PreferenceRow(stringResource(R.string.update_authorize)) {
                                permissionRequest = updates.permissionGeneration
                                system { settings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                            }
                        }
                        Preparation.IDLE -> if (last?.offer != null && updates.installation?.sessionId == null && !status.checking) {
                            PreferenceRow(stringResource(R.string.update_get), modifier = Modifier.testTag("update-install")) { updates.begin() }
                        }
                    }
                    if (status.error != 0) Text(stringResource(status.error), color = MaterialTheme.colorScheme.error)
                    PreferenceRow(stringResource(R.string.update_check_now), modifier = Modifier.testTag("update-check"),
                        enabled = !status.checking && status.preparation != Preparation.DOWNLOADING && status.preparation != Preparation.SUBMITTING,
                        onClick = updates::checkRelease)
                } }
                updates.installation?.let { receipt ->
                    item { FormGroup(frosted = true) {
                        SectionTitle(stringResource(R.string.update_install_receipt, receipt.release.version, receipt.release.versionCode))
                        Hint(stringResource(when (receipt.stage) {
                            InstallStage.WAITING -> R.string.update_installing
                            InstallStage.CONFIRM -> R.string.update_confirm
                            InstallStage.RECOVER -> R.string.update_recover
                            InstallStage.UPDATED -> R.string.update_installed
                            InstallStage.FAILED -> receipt.error
                        }))
                        if (receipt.stage == InstallStage.RECOVER && receipt.error != 0) {
                            Text(stringResource(receipt.error), color = MaterialTheme.colorScheme.error)
                        }
                        when (receipt.stage) {
                            InstallStage.CONFIRM -> PreferenceRow(stringResource(R.string.update_open_confirmation)) {
                                updates.takeConfirmation()?.let { system { context.startActivity(it) } }
                            }
                            InstallStage.RECOVER -> PreferenceRow(stringResource(R.string.update_discard_session),
                                onClick = updates::discardUnresolvedSession)
                            else -> Unit
                        }
                    } }
                }
            }
        }
    }
}

@Composable
private fun localTime(time: Long): String = DateUtils.formatDateTime(LocalContext.current, time,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)
