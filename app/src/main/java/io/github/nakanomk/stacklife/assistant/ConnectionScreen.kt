package io.github.nakanomk.stacklife.assistant

import androidx.activity.compose.LocalActivity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    var base by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }; var key by remember { mutableStateOf("") }
    var stored by remember { mutableStateOf<ServiceProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(true) }; var clear by remember { mutableStateOf(false) }
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
    LaunchedEffect(store) { run { stored = store.profile(); base = stored?.baseUrl.orEmpty(); model = stored?.model.orEmpty() } }
    BackHandler(enabled = busy) {}
    Page { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageTitle("模型服务", back = back, backEnabled = !busy)
        Hint("使用支持 Chat Completions 工具调用的 HTTPS 服务。保存配置不会联网；更换服务域必须重填密钥。")
        OutlinedTextField(base, { base = it }, Modifier.fillMaxWidth().testTag("provider-url"), enabled = !busy, singleLine = true,
            label = { Text("服务地址（含 API 前缀）") }, placeholder = { Text("https://…/v1") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth().testTag("provider-model"), enabled = !busy, singleLine = true, label = { Text("模型名称") })
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().testTag("provider-key"), enabled = !busy, singleLine = true,
            label = { Text(if (stored == null) "API Key" else "API Key（留空保留原密钥）") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Hint("密钥由 Android Keystore 加密，只存于本机，不进入对话或系统备份。")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = !busy && base.isNotBlank() && model.isNotBlank() && (key.isNotBlank() || stored != null), onClick = {
            run { store.save(base.trim(), model.trim(), key.takeIf { it.isNotBlank() }); key = ""; back() }
        }, modifier = Modifier.testTag("provider-save")) { Text("保存服务配置") }
        if (stored != null || error != null) TextButton(enabled = !busy, onClick = { clear = true }) { Text("移除本机配置") }
    } }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("移除模型服务？") }, text = { Text("删除本机保存的密钥和连接配置，不影响日程。") },
        confirmButton = { TextButton(onClick = { clear = false; run { store.clear(); key = ""; back() } }) { Text("移除") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
}
