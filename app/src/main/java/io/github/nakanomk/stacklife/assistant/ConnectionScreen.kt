package io.github.nakanomk.stacklife.assistant

import androidx.activity.compose.LocalActivity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.workspace.*
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
    var editModel by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var clear by remember { mutableStateOf(false) }
    val keyFocus = remember { FocusRequester() }
    val profile = remember(base, model) {
        try { serviceProfile(base, model) } catch (_: IllegalArgumentException) { null }
    }
    val keepKey = stored != null && stored?.origin == profile?.origin
    val scope = rememberCoroutineScope()
    fun run(action: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { error = e.message }
            catch (e: IllegalStateException) { error = e.message }
            catch (_: Exception) { error = "不能访问设备上的服务配置。" }
            finally { busy = false }
        }
    }
    LaunchedEffect(store) {
        run {
            stored = store.profile()
            preset = ServicePreset.forProfile(stored)
            base = stored?.baseUrl ?: preset.baseUrl
            model = stored?.model ?: preset.model
        }
    }
    BackHandler(enabled = busy) {}
    Page {
        PageTitle("模型服务", back = back, backEnabled = !busy)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.selectableGroup()) {
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
                TextField(base, {
                    if (it != base) key = ""
                    base = it
                }, Modifier.fillMaxWidth().testTag("provider-url"), enabled = !busy, singleLine = true,
                    label = { Text("HTTPS 服务地址（含 API 前缀）") }, colors = lineFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Hint("服务需支持 Chat Completions 工具调用。")
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (!editModel) Text(model, style = MaterialTheme.typography.bodyLarge)
                        Hint(base)
                    }
                    TextButton(onClick = { editModel = !editModel }, enabled = !busy && (!editModel || model.isNotBlank()),
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(if (editModel) "收起" else "更改模型") }
                }
            }
            if (preset == ServicePreset.Custom || editModel) {
                TextField(model, { model = it }, Modifier.fillMaxWidth().testTag("provider-model"),
                    enabled = !busy, singleLine = true, colors = lineFieldColors(), label = { Text("模型名称") })
            }
            TextField(key, { key = it }, Modifier.fillMaxWidth().focusRequester(keyFocus).testTag("provider-key"),
                enabled = !busy, singleLine = true, colors = lineFieldColors(),
                label = { Text(if (keepKey) "API Key（留空保留原密钥）" else "API Key") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (preset == ServicePreset.Claude) Hint("使用绑定工作区的 Anthropic API Key。")
            Hint("密钥仅在本机加密保存。选择和保存服务不会联网。")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy && profile != null && (key.isNotBlank() || keepKey), onClick = {
                run { store.save(base, model, key.takeIf { it.isNotBlank() }); key = ""; back() }
            }, modifier = Modifier.heightIn(min = 48.dp).testTag("provider-save")) { Text("保存服务配置") }
            if (stored != null || error != null) {
                TextButton(enabled = !busy, onClick = { clear = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除本机配置") }
            }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("移除模型服务？") }, text = { Text("删除本机保存的密钥和连接配置，不影响日程。") },
        confirmButton = { TextButton(onClick = { clear = false; run { store.clear(); key = ""; back() } }) { Text("移除") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
}
