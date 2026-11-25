package io.github.nakanomk.stacklife.assistant

import android.content.Context
import android.graphics.Bitmap
import io.github.nakanomk.stacklife.archive.archiveHex
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
internal const val MAX_CHAT_ATTACHMENTS = 5
internal const val MAX_IMAGE_INPUT_BYTES = MAX_PHOTO_BYTES

internal data class ChatImage(override val id: String, override val mime: String, val width: Int, val height: Int,
    override val bytes: Long) : ChatAttachment

/** Only immutable, normalized UUID files can be passed to the offline viewer or streaming sender. */
internal fun ChatImage.matchesPreparedFile(file: File): Boolean =
    privatePhotoId.matches(id) && file.name == id && bytes in 1..MAX_IMAGE_INPUT_BYTES &&
        PhotoInfo(mime, width, height, bytes).matchesFile(file)

private fun unavailable(): Nothing = throw ChatProblem(uiText("Could not read or store this attachment. Check the selection and available storage.", "无法读取或存储附件，请检查选取权限和存储空间。"))

internal class AttachmentStore(context: Context, private val retainedIds: suspend () -> Set<String>, directoryName: String = "chat-images") {
    private val app = context.applicationContext
    private val root = File(app.noBackupFilesDir, directoryName.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,96}"))) })
    private val mutex = Mutex()
    private var recovered = false
    private val pins = HashMap<String, Int>()
    private val removeAfterRelease = HashSet<String>()
    private val drafts = HashSet<String>() // Includes a prepared attachment between composer handoff and Room commit.

    fun file(id: String): File = File(root, id.also { require(privatePhotoId.matches(it)) })

    private suspend fun sweepLocked() {
        if (!root.isDirectory) {
            if (!root.mkdirs()) unavailable()
            syncDirectory(checkNotNull(root.parentFile))
        }
        val retained = retainedIds() // On a database failure, do not unlink anything or mark recovery complete.
        drafts.removeAll(retained)
        for (item in root.listFiles() ?: unavailable()) {
            val name = item.name
            val orphan = when {
                name.startsWith(".input-") -> privatePhotoId.matches(name.removePrefix(".input-"))
                name.startsWith(".pending-") -> privatePhotoId.matches(name.removePrefix(".pending-"))
                else -> privatePhotoId.matches(name) && name !in retained && name !in drafts && name !in pins
            }
            if (orphan) item.unlink()
        }
        recovered = true
    }

    private suspend fun <T> access(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { mutex.withLock { if (!recovered) sweepLocked(); action() } }
        catch (e: CancellationException) { throw e }
        catch (e: PhotoProblem) { throw ChatProblem(requireNotNull(e.message)) }
        catch (e: ChatProblem) { throw e }
        catch (_: Exception) { unavailable() }
    }

    suspend fun recover() { access { } }
    suspend fun collect() = withContext(Dispatchers.IO) { mutex.withLock { sweepLocked() } }

    suspend fun remove(id: String) = access {
        val target = file(id)
        if (id in retainedIds()) throw ChatProblem(uiText("This attachment still belongs to conversation history.", "此附件仍属于历史消息。"))
        if (id in pins) removeAfterRelease.add(id) else target.unlink()
        drafts.remove(id)
        Unit
    }

    /** Snapshot under the same lock as remove/recovery; pin only the immutable files the read actually referenced. */
    suspend fun <T> withArchiveLease(block: suspend (ArchiveLease) -> T): T {
        val lease = ArchiveLease()
        val previouslyOwned = access { retainedIds().also { drafts.removeAll(it) } }
        try { return block(lease) }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                mutex.withLock {
                    lease.closed = true
                    for (id in lease.ids) {
                        val left = pins.getValue(id) - 1
                        if (left == 0) pins.remove(id) else pins[id] = left
                    }
                    try {
                        val keep = retainedIds()
                        // Do not sweep unrelated draft files or interpret an unreadable DB as empty.
                        for (id in previouslyOwned) if (id !in keep) removeAfterRelease.add(id)
                        val iter = removeAfterRelease.iterator()
                        while (iter.hasNext()) {
                            val id = iter.next()
                            if (id !in pins) {
                                if (id !in keep) file(id).unlink()
                                iter.remove()
                            }
                        }
                    } catch (_: Exception) { android.util.Log.w("StackLife", "Deferred attachment cleanup") }
                }
            }
        }
    }

    internal inner class ArchiveLease internal constructor() {
        internal val ids = HashSet<String>()
        internal var closed = false

        private fun pin(id: String) {
            check(!closed)
            if (ids.add(id)) pins[id] = (pins[id] ?: 0) + 1
        }

        suspend fun <T> snapshotFiles(read: suspend () -> T, idsOf: (T) -> Map<String, ChatAttachment>): Pair<T, Map<String, File>> =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    check(!closed)
                    val snapshot = read()
                    snapshot to idsOf(snapshot).mapValues { (id, meta) ->
                        require(id == meta.id)
                        file(id).also { validateArchiveFile(meta, it); pin(id) }
                    }
                }
            }

        suspend fun publish(attachment: ChatAttachment, source: File, sha256: String): String = access {
            check(!closed)
            val id = attachment.id
            val original = file(id)
            validateArchiveFile(attachment, source)
            val reserved = retainedIds()
            if (id !in drafts && regular(original) && original.length() == attachment.bytes && digestFile(original) == sha256) {
                require(digestFile(source) == sha256)
                pin(id)
                return@access id
            }
            var target = id
            while (Files.exists(file(target).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) || target in reserved || target in pins)
                target = UUID.randomUUID().toString()
            pin(target)
            removeAfterRelease.add(target)
            val pending = File(root, ".pending-$target")
            try {
                pending.outputStream().use { out ->
                    require(digestFile(source, out, attachment.bytes) == sha256)
                    out.fd.sync()
                }
                validateArchiveFile(attachment, pending)
                Files.move(pending.toPath(), file(target).toPath(), StandardCopyOption.ATOMIC_MOVE)
                syncDirectory(root)
                target
            } finally { pending.unlink() }
        }
    }

    private fun regular(file: File) = Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)

    fun validateArchiveFile(attachment: ChatAttachment, source: File) {
        val meta = attachment
        val file = source
        require(privatePhotoId.matches(meta.id) && regular(file) && file.length() == meta.bytes)
        when (meta) {
            is ChatDocument -> meta.readValidated(file)
            is ChatImage -> {
                val info = PhotoInfo(meta.mime, meta.width, meta.height, meta.bytes)
                if (meta.bytes !in 1..MAX_IMAGE_INPUT_BYTES || !info.matchesFile(file)) unavailable()
                decodePrivatePhoto(file, 128, info).recycle()
            }
        }
    }

    suspend fun prepare(uri: Uri): ChatImage = publish { pending, id ->
        val source = File(root, ".input-$id")
        try {
            val image = prepareStaticPhoto(app.contentResolver, uri, source, pending, PhotoDestination.CHAT)
            ChatImage(id, image.mime, image.width, image.height, image.bytes)
        } finally { source.unlink() }
    }

    suspend fun prepareDocument(uri: Uri, allowPdf: Boolean): ChatDocument = publish { pending, id ->
        copyDocument(app.contentResolver, uri, pending, id, allowPdf)
    }

    suspend fun duplicate(image: ChatImage): ChatImage = publish { pending, id ->
        val original = file(image.id)
        if (!image.matchesPreparedFile(original)) unavailable()
        original.copyTo(pending)
        image.copy(id = id)
    }

    private suspend fun <T : ChatAttachment> publish(write: suspend (File, String) -> T): T {
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
                    drafts.add(id)
                    result
                } finally { pending.unlink() }
            }
        } catch (e: CancellationException) {
            // withContext can discard a successful result while dispatching it back to a cancelled caller.
            withContext(NonCancellable + Dispatchers.IO) {
                if (published != null) mutex.withLock {
                    if (id !in retainedIds()) {
                        if (id in pins) removeAfterRelease.add(id) else published?.unlink()
                        drafts.remove(id)
                    }
                }
            }
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


private suspend fun digestFile(file: File, output: java.io.OutputStream? = null, limit: Long = file.length()): String {
    val sha = java.security.MessageDigest.getInstance("SHA-256")
    val job = currentCoroutineContext()
    file.inputStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        var bytes = 0L
        while (true) {
            job.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            bytes += n
            require(bytes <= limit)
            sha.update(buffer, 0, n)
            output?.write(buffer, 0, n)
        }
        require(bytes == limit)
    }
    return sha.digest().archiveHex()
}
