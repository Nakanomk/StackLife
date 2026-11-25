package io.github.nakanomk.stacklife.assistant

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import io.github.nakanomk.stacklife.i18n.uiText
import java.util.UUID
import kotlinx.coroutines.*


internal sealed interface ImageSource {
    data class Picker(val uri: Uri) : ImageSource
    data class Saved(val image: ChatImage) : ImageSource
}
internal sealed interface ImagePreparation {
    data object Preparing : ImagePreparation
    data class Ready(val image: ChatImage) : ImagePreparation
    data class Failed(val reason: String) : ImagePreparation
}
internal data class DraftImage(val id: String, val source: ImageSource, val status: ImagePreparation)

/** The editor owns prepared files until the user message and its references commit together. */
internal class ChatDraft(private val store: AttachmentStore, private val scope: CoroutineScope) {
    val field = TextFieldState()
    var origin by mutableStateOf<AssistantPage?>(null)
    val images = mutableStateListOf<DraftImage>()
    var issue by mutableStateOf<String?>(null)
        private set
    private val preparing = mutableMapOf<String, Job>()
    private var disposed = false
    private var awaitingPicker = false

    fun beginPicker(): Boolean {
        if (disposed) return false
        awaitingPicker = true
        return true
    }

    fun receivePicker(uris: List<Uri>, active: Boolean) {
        val requested = awaitingPicker
        awaitingPicker = false
        if (requested && active) add(uris.map { ImageSource.Picker(it) })
    }
    // Keystrokes, composing ranges and caret moves belong to the native field.
    // Its enclosing dock only needs to observe an actual change of send eligibility.
    val sendable by derivedStateOf(structuralEqualityPolicy()) {
        val text = field.text
        text.length <= 8000 && images.all { it.status is ImagePreparation.Ready } &&
            (text.isNotBlank() || images.isNotEmpty())
    }

    fun add(sources: List<ImageSource>) {
        if (disposed || sources.isEmpty()) return
        if (sources.size > MAX_CHAT_IMAGES - images.size) {
            issue = uiText("Select again: at most five images per message. None of this selection was added.", "请重新选择：每条消息最多五张图片，本次选择未添加。")
            return
        }
        issue = null
        for (source in sources) {
            val item = DraftImage(UUID.randomUUID().toString(), source, ImagePreparation.Preparing)
            images.add(item)
            prepare(item)
        }
    }

    fun retry(item: DraftImage) {
        val index = images.indexOf(item)
        if (index < 0 || item.status !is ImagePreparation.Failed) return
        val next = item.copy(status = ImagePreparation.Preparing)
        images[index] = next
        prepare(next)
    }

    private fun prepare(item: DraftImage) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    ImagePreparation.Ready(when (val source = item.source) {
                        is ImageSource.Picker -> store.prepare(source.uri)
                        is ImageSource.Saved -> store.duplicate(source.image)
                    })
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { ImagePreparation.Failed(e.localizedMessage?.take(200) ?: uiText("Could not prepare this image.", "无法准备此图片。")) }
                val index = images.indexOf(item)
                if (index >= 0) images[index] = item.copy(status = result)
                else if (result is ImagePreparation.Ready) delete(result.image.id)
            } finally { preparing.remove(item.id) }
        }
        preparing[item.id] = job
        job.start()
    }

    fun remove(item: DraftImage) {
        preparing.remove(item.id)?.cancel()
        if (images.remove(item)) (item.status as? ImagePreparation.Ready)?.let { delete(it.image.id) }
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
        images.forEach { (it.status as? ImagePreparation.Ready)?.let { ready -> delete(ready.image.id) } }
        preparing.values.toList().forEach { it.cancel() }
        images.clear()
        clearText()
    }

    fun clearText() {
        field.edit { replace(0, length, "") }
        origin = null
        issue = null
    }
}
