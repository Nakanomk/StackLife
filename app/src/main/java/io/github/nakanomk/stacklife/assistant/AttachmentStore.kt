package io.github.nakanomk.stacklife.assistant

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.images.*
import io.github.nakanomk.stacklife.planner.privatePhotoId
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Bounds-first sampling and preparation thresholds adapted from GPTMobile v0.9.1, FileUtils.kt
// (e3a14c38b59012c88bc78bf327050ecffa44798f, GPL-3.0). Even small inputs are re-encoded here.
internal const val MAX_CHAT_IMAGES = 5
internal const val MAX_IMAGE_INPUT_BYTES = MAX_PHOTO_BYTES

internal data class ChatImage(val id: String, val mime: String, val width: Int, val height: Int, val bytes: Long)

/** Only immutable, normalized UUID files can be passed to the offline viewer or streaming sender. */
internal fun ChatImage.matchesPreparedFile(file: File, forUpload: Boolean = false): Boolean =
    privatePhotoId.matches(id) && file.name == id && bytes in 1..MAX_IMAGE_INPUT_BYTES &&
        PhotoInfo(mime, width, height, bytes).matchesFile(file, forUpload)

private fun unavailable(): Nothing = throw ChatProblem(uiText("Could not read or store this image. Check the selection and available storage.", "无法读取或存储这张图片，请检查选取权限和存储空间。"))

internal class AttachmentStore(context: Context, private val retainedIds: suspend () -> Set<String>, directoryName: String = "chat-images") {
    private val app = context.applicationContext
    private val root = File(app.noBackupFilesDir, directoryName.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,96}"))) })
    private val mutex = Mutex()
    private var recovered = false

    fun file(id: String): File = File(root, id.also { require(privatePhotoId.matches(it)) })

    private suspend fun recoverLocked() {
        if (recovered) return
        if (!root.isDirectory && !root.mkdirs()) unavailable()
        val retained = retainedIds() // On a database failure, do not unlink anything or mark recovery complete.
        for (item in root.listFiles() ?: unavailable()) {
            val name = item.name
            val orphan = when {
                name.startsWith(".input-") -> privatePhotoId.matches(name.removePrefix(".input-"))
                name.startsWith(".pending-") -> privatePhotoId.matches(name.removePrefix(".pending-"))
                else -> privatePhotoId.matches(name) && name !in retained
            }
            if (orphan) item.unlink()
        }
        recovered = true
    }

    private suspend fun <T> access(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { mutex.withLock { recoverLocked(); action() } }
        catch (e: CancellationException) { throw e }
        catch (e: PhotoProblem) { throw ChatProblem(requireNotNull(e.message)) }
        catch (e: ChatProblem) { throw e }
        catch (_: Exception) { unavailable() }
    }

    suspend fun recover() { access { } }

    suspend fun remove(id: String) = access {
        val target = file(id)
        if (id in retainedIds()) throw ChatProblem(uiText("This image still belongs to conversation history.", "这张图片仍属于历史消息。"))
        target.unlink()
    }

    suspend fun prepare(uri: Uri): ChatImage = publish { pending, id ->
        val source = File(root, ".input-$id")
        try {
            val image = prepareStaticPhoto(app.contentResolver, uri, source, pending)
            ChatImage(id, image.mime, image.width, image.height, image.bytes)
        } finally { source.unlink() }
    }

    suspend fun duplicate(image: ChatImage): ChatImage = publish { pending, id ->
        val original = file(image.id)
        if (!image.matchesPreparedFile(original)) unavailable()
        original.copyTo(pending)
        image.copy(id = id)
    }

    private suspend fun publish(write: suspend (File, String) -> ChatImage): ChatImage {
        val id = UUID.randomUUID().toString()
        var published: File? = null
        try {
            return access {
                val pending = File(root, ".pending-$id")
                try {
                    val result = write(pending, id)
                    currentCoroutineContext().ensureActive()
                    val target = file(id)
                    Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    published = target
                    result
                } finally { pending.unlink() }
            }
        } catch (e: CancellationException) {
            // withContext can discard a successful result while dispatching it back to a cancelled caller.
            withContext(NonCancellable + Dispatchers.IO) { published?.unlink() }
            throw e
        }
    }

    suspend fun bitmap(image: ChatImage, maxEdge: Int): Bitmap = access {
        val target = file(image.id)
        if (maxEdge <= 0 || !image.matchesPreparedFile(target)) unavailable()
        decodePrivatePhoto(target, maxEdge, PhotoInfo(image.mime, image.width, image.height, image.bytes))
    }
}

private fun File.unlink() { if (exists() && !delete()) unavailable() }

