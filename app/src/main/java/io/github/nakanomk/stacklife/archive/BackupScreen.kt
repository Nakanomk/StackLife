package io.github.nakanomk.stacklife.archive

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.removePartialArchive
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.workspace.*
import java.io.IOException
import kotlinx.coroutines.*

internal fun BackupPart.label(): String = when (this) {
    BackupPart.APPEARANCE -> uiText("Appearance", "外观")
    BackupPart.TODAY -> uiText("Today: schedules and reminders", "今天：排程与提醒")
    BackupPart.QUEUE -> uiText("Queue", "队列")
    BackupPart.STACK -> uiText("Stack and saved snapshots", "栈与原始快照")
    BackupPart.GRAPHS -> uiText("Flowcharts", "流程图")
    BackupPart.COURSES -> uiText("Timetable", "课表")
    BackupPart.COUNTDOWNS -> uiText("Countdowns", "倒数日")
    BackupPart.HISTORY -> uiText("Conversation history and files", "对话记录与附件")
    BackupPart.PREFERENCES -> uiText("App preferences and widget templates", "应用偏好与小组件配置模板")
    BackupPart.CONNECTION -> uiText("Model connection", "模型连接")
}
internal val backupTitle: String get() = uiText("Backup and restore", "备份与恢复")
private fun secretHint(included: Boolean) = if (included) uiText("Contains a readable API key.", "含可读取的 API Key。")
    else uiText("No API key is included.", "不含 API Key。")
private data class Subset(val source: BackupSelection, val answer: CompletableDeferred<Set<BackupPart>?>)
private data class Review(val source: BackupReview, val answer: CompletableDeferred<Boolean>)

/** Screen-owned SAF grants and pending decisions survive rotation, never process death. No secret or document contents in UI state. */
private class BackupTransferModel(private val context: Context, private val archive: FullBackup) : ViewModel() {
    var subset by mutableStateOf<Subset?>(null); private set
    var review by mutableStateOf<Review?>(null); private set
    var busy by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    private var job: Job? = null

    private fun launch(block: suspend () -> Unit) {
        if (busy) return
        busy = true; message = ""
        job = viewModelScope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = uiText("Could not read or validate this backup. Nothing was restored.", "无法读取或校验备份，未恢复数据。") }
            finally { subset = null; review = null; busy = false; saving = false; job = null }
        }
    }
    fun cancel() { if (!saving) job?.cancel() }
    fun notice(text: String) { message = text }
    fun decide(value: Boolean) { if (review?.answer?.complete(value) == true) saving = value }
    fun export(uri: Uri, parts: Set<BackupPart>, key: Boolean) = launch {
        try {
            archive.export(parts, key) { context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException() }
            message = uiText("Backup written to the selected file.", "备份已写入所选文件。")
        } catch (error: Exception) {
            val deleted = removePartialArchive(context, uri)
            message = context.getString(if (deleted) R.string.transfer_export_failed else R.string.transfer_partial)
            if (error is CancellationException) throw error
        }
    }
    fun restore(uri: Uri) = launch {
        val receipt = archive.restore({ context.contentResolver.openInputStream(uri) ?: throw IOException() }, { selection ->
            withContext(Dispatchers.Main.immediate) {
                val stage = Subset(selection, CompletableDeferred()); subset = stage
                try { stage.answer.await() } finally { subset = null }
            }
        }, { differences -> withContext(Dispatchers.Main.immediate) {
            saving = false
            val stage = Review(differences, CompletableDeferred()); review = stage
            try { stage.answer.await() } finally { review = null }
        } })
        if (receipt != null) message = receipt.label()
    }
}
internal fun BackupReceipt.label() = (listOf(uiText("Selected categories restored (revision $revision).", "已恢复所选分类，本机修订 $revision。")) + warnings).joinToString("\n")

@Composable
internal fun BackupScreen(archive: FullBackup, back: () -> Unit) {
    val context = LocalContext.current
    val transfer = viewModel<BackupTransferModel> { BackupTransferModel(context.applicationContext, archive) }
    var chosen by rememberSaveable { mutableStateOf(BackupPart.entries.map { it.wire }) }
    var includeKey by rememberSaveable { mutableStateOf(true) }
    var warning by rememberSaveable { mutableStateOf(false) }
    var pendingParts by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var pendingKey by rememberSaveable { mutableStateOf(false) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val parts = pendingParts; pendingParts = null
        if (uri != null && parts != null) transfer.export(uri, parts.mapTo(LinkedHashSet(), BackupPart.byWire::getValue), pendingKey)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) transfer.restore(it) }
    fun launchPicker(block: () -> Unit) {
        try { block() } catch (_: android.content.ActivityNotFoundException) { transfer.notice(context.getString(R.string.transfer_no_picker)) }
          catch (_: SecurityException) { transfer.notice(context.getString(R.string.transfer_no_picker)) }
    }
    fun createFile() {
        warning = false; pendingParts = chosen; pendingKey = includeKey && BackupPart.CONNECTION.wire in chosen
        launchPicker { create.launch("stacklife-backup.zip") }
    }
    BackHandler(transfer.busy) { transfer.cancel() }
    EditorPage(backupTitle, back, backEnabled = !transfer.busy) { top, bottom ->
        LazyColumn(Modifier.fillMaxSize().testTag("backup-page"), contentPadding = dockListPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { FormGroup(frosted = true) {
                SectionTitle(uiText("Restore from a file", "从文件恢复"))
                Hint(uiText("Choose categories after checking the file. Unselected categories stay on this device.", "校验文件后可选择要替换的分类。未选分类保留。"))
                Secondary(uiText("Open backup…", "打开备份文件…"), !transfer.busy) { launchPicker { open.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) } }
            } }
            item { FormGroup(frosted = true) {
                SectionTitle(uiText("Export categories", "选择备份分类"))
                CheckRow(uiText("All categories", "全部分类"), chosen.size == BackupPart.entries.size, !transfer.busy) {
                    chosen = if (chosen.size == BackupPart.entries.size) emptyList() else BackupPart.entries.map { it.wire }
                }
                for (part in BackupPart.entries) CheckRow(part.label(), part.wire in chosen, !transfer.busy) {
                    chosen = if (part.wire in chosen) chosen - part.wire else chosen + part.wire
                }
                if (BackupPart.CONNECTION.wire in chosen) {
                    CheckRow(uiText("Include the API key if configured", "包含已配置的 API Key"), includeKey, !transfer.busy) { includeKey = !includeKey }
                    if (includeKey) Hint(uiText("The exported ZIP is not encrypted. Anyone with the file can use a saved key.", "导出的 ZIP 不加密。拿到文件的人可读取并使用已保存的密钥。"))
                }
                Hint(uiText("Up to 128 MiB, 255 data members and 8,192 records per category. No downloads, permissions or pending proposals.", "上限 128 MiB、255 个数据成员、每类 8,192 条记录。不含下载、系统权限或待确认提案。"))
                Primary(uiText("Export selected…", "导出所选分类…"), chosen.isNotEmpty() && !transfer.busy) {
                    if (includeKey && BackupPart.CONNECTION.wire in chosen) warning = true else createFile()
                }
            } }
            if (transfer.message.isNotEmpty()) item { Text(transfer.message) }
        }
    }
    if (warning) AlertDialog(onDismissRequest = { warning = false }, title = { Text(uiText("Save a readable backup?", "保存可读取的备份？")) },
        text = { Text(uiText("This file can contain your API key and personal schedule. Save it in a trusted location. To omit the key, go back and uncheck it.", "文件可能包含可读取的 API Key 和私人日程。只保存到可信位置；若不包含密钥，请返回取消勾选。")) },
        confirmButton = { TextButton(onClick = ::createFile) { Text(uiText("Choose location", "选择保存位置")) } },
        dismissButton = { TextButton(onClick = { warning = false }) { Text(stringResource(R.string.ui_cancel)) } })
    val subset = transfer.subset; val review = transfer.review
    if (subset != null) key(subset) {
        var selected by remember { mutableStateOf(subset.source.parts) }
        AlertDialog(onDismissRequest = { subset.answer.complete(null) }, title = { Text(uiText("Choose categories to restore", "选择恢复分类")) }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(secretHint(subset.source.containsKey)); for (part in BackupPart.entries) if (part in subset.source.parts) CheckRow(part.label(), part in selected) {
                    selected = if (part in selected) selected - part else selected + part
                }
            }
        }, confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { subset.answer.complete(selected) }) { Text(uiText("Review changes", "核对差异")) } },
            dismissButton = { TextButton(onClick = { subset.answer.complete(null) }) { Text(stringResource(R.string.ui_cancel)) } })
    } else if (review != null) AlertDialog(onDismissRequest = { transfer.decide(false) }, title = { Text(uiText("Replace selected categories?", "替换所选分类？")) }, text = {
        val data = review.source
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (data.refreshed) Text(stringResource(R.string.transfer_refreshed))
            data.counts.forEach { (part, n) -> Text("${part.label()}  +${n.added}  ~${n.updated}  −${n.removed}") }
            Hint(uiText("+ added, ~ updated, − removed. ${data.photos} background(s), ${data.attachments} attachment(s).", "+ 新增、~ 更新、− 删除。${data.photos} 张背景，${data.attachments} 个附件。") + " " + secretHint(data.containsKey))
            data.impacts.forEach { Text(it) }; data.conflicts.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = review.source.conflicts.isEmpty() && !transfer.saving, onClick = { transfer.decide(true) }) { Text(stringResource(R.string.transfer_replace)) } },
        dismissButton = { TextButton(onClick = { transfer.decide(false) }) { Text(stringResource(R.string.ui_cancel)) } })
    else if (transfer.busy) AlertDialog(onDismissRequest = transfer::cancel, title = { Text(backupTitle) }, text = { Text(stringResource(R.string.transfer_busy)) },
        confirmButton = {}, dismissButton = { if (!transfer.saving) TextButton(onClick = transfer::cancel) { Text(stringResource(R.string.ui_cancel)) } })
}
