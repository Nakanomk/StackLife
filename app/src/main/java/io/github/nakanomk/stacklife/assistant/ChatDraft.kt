package io.github.nakanomk.stacklife.assistant

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import io.github.nakanomk.stacklife.i18n.uiText
import java.util.UUID
import kotlinx.coroutines.*


internal sealed interface AttachmentSource {
    data class Photo(val uri: Uri) : AttachmentSource
    data class Document(val uri: Uri, val recipient: ServiceProfile?) : AttachmentSource
    data class SavedImage(val image: ChatImage) : AttachmentSource
}
internal sealed interface AttachmentPreparation {
    data object Preparing : AttachmentPreparation
    data class Ready(val attachment: ChatAttachment) : AttachmentPreparation
    data class Failed(val reason: String) : AttachmentPreparation
}
internal data class DraftAttachment(val id: String, val source: AttachmentSource, val status: AttachmentPreparation)

/** The editor owns prepared files until the user message and its references commit together. */
internal class ChatDraft(private val store: AttachmentStore, private val scope: CoroutineScope) {
    val field = TextFieldState()
    var origin by mutableStateOf<AssistantPage?>(null)
    val items = mutableStateListOf<DraftAttachment>()
    var issue by mutableStateOf<String?>(null)
        private set
    private val preparing = mutableMapOf<String, Job>()
    private var disposed = false
    var pickerEpoch by mutableLongStateOf(0L)
        private set
    private var awaitingPicker = false
    private var documentRecipient: ServiceProfile? = null

    fun launchPicker(recipient: ServiceProfile? = null, launch: () -> Unit) {
        if (disposed || awaitingPicker) return
        documentRecipient = recipient
        awaitingPicker = true
        try {
            launch()
        } catch (_: android.content.ActivityNotFoundException) {
            pickerUnavailable()
        } catch (_: SecurityException) {
            pickerUnavailable()
        }
    }

    private fun pickerUnavailable() {
        awaitingPicker = false
        documentRecipient = null
        issue = uiText("Cannot open the system picker on this device.", "无法在此设备上打开系统选择器。")
    }

    fun receivePicker(epoch: Long, uris: List<Uri>, active: Boolean, document: Boolean) {
        if (disposed || epoch != pickerEpoch || !awaitingPicker) return
        awaitingPicker = false
        if (active) add(uris.map { if (document) AttachmentSource.Document(it, documentRecipient) else AttachmentSource.Photo(it) })
        documentRecipient = null
    }
    // Keystrokes, composing ranges and caret moves belong to the native field.
    // Its enclosing dock only needs to observe an actual change of send eligibility.
    val sendable by derivedStateOf(structuralEqualityPolicy()) {
        val text = field.text
        text.length <= 8000 && items.all { it.status is AttachmentPreparation.Ready } &&
            (text.isNotBlank() || items.isNotEmpty())
    }

    fun add(sources: List<AttachmentSource>) {
        if (disposed || sources.isEmpty()) return
        if (sources.size > MAX_CHAT_ATTACHMENTS - items.size) {
            issue = uiText("Select again: at most five attachments per message. None of this selection was added.", "请重新选择：每条消息最多五个附件，本次选择未添加。")
            return
        }
        issue = null
        for (source in sources) {
            val item = DraftAttachment(UUID.randomUUID().toString(), source, AttachmentPreparation.Preparing)
            items.add(item)
            prepare(item)
        }
    }

    fun retry(item: DraftAttachment) {
        val index = items.indexOf(item)
        if (index < 0 || item.status !is AttachmentPreparation.Failed) return
        val next = item.copy(status = AttachmentPreparation.Preparing)
        items[index] = next
        prepare(next)
    }

    private fun prepare(item: DraftAttachment) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    AttachmentPreparation.Ready(when (val source = item.source) {
                        is AttachmentSource.Photo -> store.prepare(source.uri)
                        is AttachmentSource.Document -> store.prepareDocument(source.uri, source.recipient.acceptsPdf)
                        is AttachmentSource.SavedImage -> store.duplicate(source.image)
                    })
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { AttachmentPreparation.Failed(e.localizedMessage?.take(200) ?: uiText("Could not prepare this attachment.", "无法准备此附件。")) }
                val index = items.indexOf(item)
                if (index >= 0) items[index] = item.copy(status = result)
                else if (result is AttachmentPreparation.Ready) delete(result.attachment.id)
            } finally { preparing.remove(item.id) }
        }
        preparing[item.id] = job
        job.start()
    }

    fun remove(item: DraftAttachment) {
        preparing.remove(item.id)?.cancel()
        if (items.remove(item)) (item.status as? AttachmentPreparation.Ready)?.let { delete(it.attachment.id) }
        issue = null
    }

    private fun delete(id: String) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
            try { store.remove(id) }
            catch (_: Exception) { issue = uiText("Could not remove the local file. Unreferenced files are checked again at startup.", "未能移除本地文件，启动时将再次清理未被引用的文件。") }
        } }
    }

    fun dispose() {
        disposed = true
        clear()
    }

    fun clear() {
        awaitingPicker = false
        documentRecipient = null
        pickerEpoch++
        items.forEach { (it.status as? AttachmentPreparation.Ready)?.let { ready -> delete(ready.attachment.id) } }
        preparing.values.toList().forEach { it.cancel() }
        items.clear()
        clearText()
    }

    fun clearText() {
        field.edit { replace(0, length, "") }
        origin = null
        issue = null
    }
}
