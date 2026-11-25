package io.github.nakanomk.stacklife.assistant

import io.github.nakanomk.stacklife.R
import androidx.activity.compose.LocalActivity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.workspace.*
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ConnectionScreen(store: ConnectionStore, back: () -> Unit) {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        val protected = window?.attributes?.flags?.and(flag) == flag
        window?.addFlags(flag)
        onDispose { if (!protected) window?.clearFlags(flag) }
    }
    var preset by remember { mutableStateOf(ServicePreset.DeepSeek) }
    var base by remember { mutableStateOf(preset.baseUrl) }
    var model by remember { mutableStateOf(preset.model) }
    var key by remember { mutableStateOf("") }
    var stored by remember { mutableStateOf<ServiceProfile?>(null) }
    var epoch by remember(store) { mutableStateOf<Long?>(null) }
    var editModel by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var clear by remember { mutableStateOf(false) }
    val keyFocus = remember { FocusRequester() }
    val profile = remember(base, model) {
        try { serviceProfile(base, model) } catch (_: IllegalArgumentException) { null }
    }
    // An opt-in belongs to this endpoint AND model, never the next value typed into either field.
    var supportsImages by remember(profile, stored) {
        mutableStateOf(profile?.supportsImages == true || (stored?.supportsImages == true &&
            stored?.baseUrl == profile?.baseUrl && stored?.model == profile?.model))
    }
    val keepKey = stored != null && stored?.origin == profile?.origin
    val scope = rememberCoroutineScope()
    val settingsUnavailable = stringResource(R.string.assistant_settings_unavailable)
    fun run(action: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { error = e.message }
            catch (e: IllegalStateException) { error = e.message }
            catch (_: Exception) { error = settingsUnavailable }
            finally { busy = false }
        }
    }
    LaunchedEffect(store) {
        run {
            epoch = store.editEpoch()
            stored = store.profile(epoch)
            preset = ServicePreset.forProfile(stored)
            base = stored?.baseUrl ?: preset.baseUrl
            model = stored?.model ?: preset.model
        }
    }
    BackHandler(enabled = busy) {}
    Page {
        // The header sees only Page's paper. Do not record or scroll any fields behind it, even masked.
        PageTitle(stringResource(R.string.assistant_service_title), back = back, backEnabled = !busy,
            help = stringResource(R.string.assistant_service_privacy, profile?.origin ?: stringResource(R.string.assistant_selected_service)))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(dockListPadding(0.dp, 8.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FormGroup(Modifier.selectableGroup()) {
                ServicePreset.entries.forEach { option ->
                    val selected = preset == option
                    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = !busy, role = Role.RadioButton) {
                        if (!selected) {
                            key = ""
                            preset = option
                            val saved = stored?.takeIf { ServicePreset.forProfile(it) == option }
                            base = saved?.baseUrl ?: option.baseUrl
                            model = saved?.model ?: option.model
                            error = null
                            editModel = false
                        }
                        keyFocus.requestFocus()
                    }.heightIn(min = 48.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, enabled = !busy, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            HorizontalDivider()
            if (preset == ServicePreset.Custom) {
                SettingLabel(stringResource(R.string.assistant_service_address), stringResource(R.string.assistant_service_requirements))
                TextField(base, {
                    if (it != base) key = ""
                    base = it
                }, Modifier.fillMaxWidth().testTag("provider-url"), enabled = !busy, singleLine = true,
                    colors = lineFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (!editModel) Text(model, style = MaterialTheme.typography.bodyLarge)
                        Hint(base)
                    }
                    Secondary(if (editModel) stringResource(R.string.assistant_collapse_model) else stringResource(R.string.assistant_edit_model), enabled = !busy && (!editModel || model.isNotBlank())) {
                        editModel = !editModel
                    }
                }
            }
            if (preset == ServicePreset.Custom || editModel) {
                TextField(model, { model = it }, Modifier.fillMaxWidth().testTag("provider-model"),
                    enabled = !busy, singleLine = true, colors = lineFieldColors(), label = { Text(stringResource(R.string.assistant_model_name)) })
            }
            if (profile != null && !profile.supportsImages) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = supportsImages, enabled = !busy, role = Role.Switch, onValueChange = { supportsImages = it },
                ).testTag("provider-images"), verticalAlignment = Alignment.CenterVertically) {
                    SettingLabel(stringResource(R.string.provider_images_toggle), stringResource(R.string.provider_images_hint), Modifier.weight(1f))
                    Switch(checked = supportsImages, enabled = !busy, onCheckedChange = null)
                }
            }
            val keyLabel = stringResource(if (keepKey) R.string.assistant_keep_key else R.string.assistant_key)
            if (preset == ServicePreset.Claude) SettingLabel(keyLabel, stringResource(R.string.assistant_anthropic_key))
            TextField(key, { key = it }, Modifier.fillMaxWidth().focusRequester(keyFocus).testTag("provider-key"),
                enabled = !busy, singleLine = true, colors = lineFieldColors(),
                label = if (preset == ServicePreset.Claude) null else { { Text(keyLabel) } },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ActionPill(stringResource(R.string.assistant_save_service), enabled = !busy && epoch != null && profile != null && (key.isNotBlank() || keepKey), prominent = true, onClick = {
                run { store.save(base, model, key.takeIf { it.isNotBlank() }, supportsImages, requireNotNull(epoch)); key = ""; back() }
            }, modifier = Modifier.testTag("provider-save"))
            if (stored != null || error != null) {
                Secondary(stringResource(R.string.assistant_remove_service), enabled = !busy && epoch != null) { clear = true }
            }
        }
    }
    if (clear) PlannerAlertDialog(onDismissRequest = { clear = false }, title = { Text(stringResource(R.string.assistant_remove_service_title)) }, text = { Text(stringResource(R.string.assistant_remove_service_body)) },
        confirmButton = { TextButton(onClick = { clear = false; run { store.clear(requireNotNull(epoch)); key = ""; back() } }) { Text(stringResource(R.string.assistant_remove)) } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text(stringResource(R.string.assistant_cancel)) } })
}
