package io.github.nakanomk.stacklife.appearance

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.images.*
import io.github.nakanomk.stacklife.planner.privatePhotoId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
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

// BiliPai 5893c2c80739e3759ac64ae5f232cf03a94da7f8, feature/profile/WallpaperImageImport.kt
// (GPL-3.0): copy during picker grant; StackLife normalizes a private still and cleans cancelled imports.
internal class BackgroundStore(context: Context, private val retained: suspend () -> Set<String>) {
    private val root = File(context.noBackupFilesDir, "background-images")
    private val resolver = context.applicationContext.contentResolver
    private val mutex = Mutex()
    private val drafts = HashSet<String>()
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
            if (privatePhotoId.matches(id) && id !in drafts && (staging || id !in keep)) Files.deleteIfExists(file.toPath())
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
                prepareStaticPhoto(resolver, uri, source, pending, PhotoSourcePolicy.PRIMARY_STILL)
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
                if (id !in retained()) Files.deleteIfExists(File(root, id).toPath())
                drafts.remove(id)
            }
            Unit
        }
    }

    internal suspend fun bitmap(id: String, edge: Int): Bitmap = io {
        if (!exists(id)) storageFailure()
        decodePrivatePhoto(File(root, id), edge)
    }
}
