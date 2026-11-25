package io.github.nakanomk.stacklife.appearance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import io.github.nakanomk.stacklife.archive.archiveHex
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.images.*
import io.github.nakanomk.stacklife.planner.privatePhotoId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// BiliPai 5893c2c80739e3759ac64ae5f232cf03a94da7f8, feature/profile/WallpaperImageImport.kt
// (GPL-3.0): copy during picker grant; StackLife normalizes a private still and cleans cancelled imports.
internal class BackgroundStore(context: Context, private val retained: suspend () -> Set<String>) {
    private val root = File(context.noBackupFilesDir, "background-images")
    private val resolver = context.applicationContext.contentResolver
    private val mutex = Mutex()
    private val drafts = HashSet<String>()
    private val archivePins = HashMap<String, Int>()
    private var recovered = false

    /** Also called inside a Planner transaction: no store lock or database callback here. */
    fun exists(id: String): Boolean = privatePhotoId.matches(id) &&
        Files.isRegularFile(File(root, id).toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun storageFailure(): Nothing = throw PhotoProblem(uiText(
        "Could not read or store the background. Check the selection and available storage.", "无法读取或保存背景，请检查所选图片和存储空间。"))

    private suspend fun <T> io(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { action() }
        catch (e: CancellationException) { throw e }
        catch (e: PhotoProblem) { throw e }
        catch (_: IOException) { storageFailure() }
        catch (_: SecurityException) { storageFailure() }
    }

    /** Never delete anything if the full set of durable references could not be read. */
    private suspend fun sweepLocked() {
        val keep = retained()
        if (!root.isDirectory && !root.mkdirs()) storageFailure()
        for (file in root.listFiles() ?: storageFailure()) {
            val name = file.name
            val staging = name.startsWith(".input-") || name.startsWith(".pending-")
            val id = if (staging) name.substringAfter('-') else name
            if (privatePhotoId.matches(id) && id !in drafts && id !in archivePins && (staging || id !in keep))
                Files.deleteIfExists(file.toPath())
        }
        recovered = true
    }

    suspend fun recover() = io { mutex.withLock { if (!recovered) sweepLocked() } }
    suspend fun collect() = io { mutex.withLock { sweepLocked() } }

    suspend fun importImage(uri: Uri): String {
        val id = UUID.randomUUID().toString()
        val source = File(root, ".input-$id")
        val pending = File(root, ".pending-$id")
        val target = File(root, id)
        try {
            return io {
                mutex.withLock {
                    if (!recovered) sweepLocked()
                    drafts.add(id)
                }
                prepareStaticPhoto(resolver, uri, source, pending, PhotoDestination.BACKGROUND)
                currentCoroutineContext().ensureActive()
                Files.delete(source.toPath())
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                id
            }
        } catch (error: Throwable) {
            // Includes prompt cancellation returning from the IO dispatcher: no caller owns id yet.
            withContext(NonCancellable + Dispatchers.IO) {
                mutex.withLock {
                    drafts.remove(id)
                    Files.deleteIfExists(source.toPath()); Files.deleteIfExists(pending.toPath()); Files.deleteIfExists(target.toPath())
                }
            }
            throw error
        }
    }

    suspend fun release(id: String) = io {
        require(privatePhotoId.matches(id))
        mutex.withLock {
            // Retry the same release after a failed reference read, without dropping its lease.
            if (id in drafts) {
                if (id !in archivePins && id !in retained()) Files.deleteIfExists(File(root, id).toPath())
                drafts.remove(id)
            }
            Unit
        }
    }

    /** A scoped lease, never a persistent ownership claim. No Planner transaction waits for this mutex. */
    suspend fun <T> withArchiveLease(block: suspend (ArchiveLease) -> T): T {
        val lease = ArchiveLease()
        try { return block(lease) }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                mutex.withLock {
                    if (lease.ids.isNotEmpty()) {
                        for (id in lease.ids) {
                            val remaining = archivePins.getValue(id) - 1
                            if (remaining == 0) archivePins.remove(id) else archivePins[id] = remaining
                        }
                        // A failed durable reference read defers GC; it never unlinks an unknown owner
                        // or turns a completed Planner transaction into an apparent failed import.
                        try {
                            val keep = retained()
                            for (id in lease.ids) if (id !in keep && id !in drafts && id !in archivePins)
                                Files.deleteIfExists(File(root, id).toPath())
                        }
                        catch (e: Exception) { android.util.Log.w("StackLife", "Archive background cleanup deferred", e) }
                    }
                }
            }
        }
    }

    internal inner class ArchiveLease internal constructor() {
        internal val ids = HashSet<String>()
        private fun pin(id: String) {
            if (ids.add(id)) archivePins[id] = (archivePins[id] ?: 0) + 1
        }

        suspend fun <T> snapshotFiles(read: suspend () -> T, idsOf: (T) -> Set<String>): Pair<T, Map<String, File>> = io {
            mutex.withLock {
                val snapshot = read()
                snapshot to idsOf(snapshot).associateWith { id ->
                    if (!exists(id)) storageFailure()
                    pin(id); File(root, id)
                }
            }
        }

        /** Verify existing prepared-image semantics, publish complete bytes on the same volume,
         * and return the actual identity. Never overwrite another owner's file, even on ID collision. */
        suspend fun publish(id: String, source: File, sha256: String): String = io {
            require(privatePhotoId.matches(id) && source.length() in 1..MAX_PHOTO_BYTES)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            val info = PhotoInfo(bounds.outMimeType ?: storageFailure(), bounds.outWidth, bounds.outHeight, source.length())
            if (!info.matchesFile(source)) storageFailure()
            decodePrivatePhoto(source, 128, info).recycle()
            mutex.withLock {
                if (!recovered) sweepLocked()
                val existing = File(root, id)
                if (exists(id) && existing.length() == source.length() && existing.inputStream().use { streamDigest(it) } == sha256) {
                    pin(id); return@withLock id
                }
                val reserved = retained()
                var target = id
                while (Files.exists(File(root, target).toPath(), LinkOption.NOFOLLOW_LINKS) || target in archivePins || target in drafts || target in reserved)
                    target = UUID.randomUUID().toString()
                pin(target)
                val pending = File(root, ".pending-$target")
                try {
                    source.inputStream().use { input ->
                        pending.outputStream().use { out ->
                            if (streamDigest(input, out) != sha256) storageFailure()
                            out.fd.sync()
                        }
                    }
                    Files.move(pending.toPath(), File(root, target).toPath(), StandardCopyOption.ATOMIC_MOVE)
                    val dir = Os.open(root.path, OsConstants.O_RDONLY, 0)
                    try { Os.fsync(dir) } finally { Os.close(dir) }
                    target
                } finally { Files.deleteIfExists(pending.toPath()) }
            }
        }
    }

    private suspend fun streamDigest(input: java.io.InputStream, out: java.io.OutputStream? = null): String {
        val job = currentCoroutineContext()
        val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(16 * 1024)
        while (true) {
            job.ensureActive(); val n = input.read(buffer); if (n < 0) break
            hash.update(buffer, 0, n); out?.write(buffer, 0, n)
        }
        return hash.digest().archiveHex()
    }

    internal suspend fun bitmap(id: String, edge: Int): Bitmap = io {
        if (!exists(id)) storageFailure()
        decodePrivatePhoto(File(root, id), edge)
    }
}
