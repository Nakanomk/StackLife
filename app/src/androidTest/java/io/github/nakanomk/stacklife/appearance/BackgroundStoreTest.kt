package io.github.nakanomk.stacklife.appearance

import android.content.ContentValues
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.os.FileObserver
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun fixture(block: suspend (ContextWrapper, File) -> Unit) {
        val isolated = File(context.cacheDir, "wallpaper-${UUID.randomUUID()}").apply { mkdir() }
        val app = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = isolated }
        try { block(app, File(isolated, "background-images")) } finally { isolated.deleteRecursively() }
    }

    @RequiresApi(29)
    private suspend fun selected(bytes: ByteArray, block: suspend (Uri) -> Unit) {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "wallpaper-${UUID.randomUUID()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try { requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }; block(uri) }
        finally { resolver.delete(uri, null, null) }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun aDraftSurvivesCollectionAndOnlyTheLastCommittedOwnerReleasesItsUprightFile() = runBlocking { fixture { app, dir ->
        val owners = mutableMapOf<String, String>()
        val store = BackgroundStore(app) { owners.values.toSet() }
        val source = File(app.noBackupFilesDir, "source.jpg")
        val picture = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        try { picture.eraseColor(Color.RED); source.outputStream().use { assertTrue(picture.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
        finally { picture.recycle() }
        ExifInterface(source.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); setAttribute(ExifInterface.TAG_MAKE, "fixture"); saveAttributes() }
        lateinit var id: String
        selected(source.readBytes()) { uri ->
            id = store.importImage(uri)
            val cancelled = store.importImage(uri)
            store.collect(); assertTrue(store.exists(id)); assertTrue(store.exists(cancelled))
            store.release(cancelled); assertFalse(store.exists(cancelled))
        }
        val image = store.bitmap(id, 64)
        try { assertEquals(8, image.width); assertEquals(16, image.height) } finally { image.recycle() }
        assertNull(ExifInterface(File(dir, id).path).getAttribute(ExifInterface.TAG_MAKE))
        owners["countdown"] = id; owners["page"] = id; owners["history"] = id
        store.release(id); assertTrue(store.exists(id))
        val restarted = BackgroundStore(app) { owners.values.toSet() }; restarted.recover()
        owners.remove("countdown"); restarted.collect(); owners.remove("page"); restarted.collect(); assertTrue(restarted.exists(id))
        owners.remove("history"); restarted.collect(); assertFalse(restarted.exists(id)); assertTrue(dir.list()!!.isEmpty())
    } }

    @Test fun failedReferenceReadNeverReclaimsAndNonCanonicalIdsNeverResolve() = runBlocking { fixture { app, dir ->
        dir.mkdirs()
        val live = File(dir, UUID.randomUUID().toString()).apply { writeText("durable") }
        val partial = File(dir, ".pending-${UUID.randomUUID()}").apply { writeText("partial") }
        val orphan = File(dir, UUID.randomUUID().toString()).apply { writeText("orphan") }
        val link = File(dir, UUID.randomUUID().toString()); Files.createSymbolicLink(link.toPath(), live.toPath())
        var fail = true
        val store = BackgroundStore(app) { if (fail) error("database unavailable") else setOf(live.name) }
        assertTrue(runCatching { store.recover() }.isFailure); assertTrue(partial.exists()); assertTrue(orphan.exists())
        assertFalse(store.exists(link.name)); assertFalse(store.exists("../${live.name}")); assertFalse(store.exists(live.name.uppercase()))
        fail = false; store.recover(); assertTrue(live.exists()); assertFalse(partial.exists()); assertFalse(orphan.exists())
        assertTrue(runCatching { store.bitmap(live.name, 64) }.isFailure)
        assertTrue(runCatching { store.importImage(Uri.fromFile(live)) }.isFailure)
        assertTrue(runCatching { store.release("../escape") }.isFailure)
        assertEquals(listOf(live.name), dir.list()!!.toList())
    } }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun cancellationReturningFromIoUnlinksEvenAnAlreadyPublishedPhoto() = runBlocking { fixture { app, dir ->
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        val bytes = try { ByteArrayOutputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)); it.toByteArray() } }
        finally { bitmap.recycle() }
        dir.mkdirs()
        val entry = CompletableDeferred<Unit>(); val ready = CompletableDeferred<Unit>()
        val store = BackgroundStore(app) { entry.complete(Unit); ready.await(); emptySet() }
        val published = CompletableDeferred<String>()
        val observer = object : FileObserver(dir, FileObserver.MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && !path.startsWith('.')) published.complete(path)
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val resume = CountDownLatch(1)
        executor.asCoroutineDispatcher().use { caller ->
            observer.startWatching()
            try {
                selected(bytes) { uri -> coroutineScope {
                    val result = async(caller) { store.importImage(uri) }
                    withTimeout(10_000) { entry.await() }
                    val stalled = CountDownLatch(1)
                    executor.execute { stalled.countDown(); resume.await(12, TimeUnit.SECONDS) }
                    try {
                        assertTrue(stalled.await(10, TimeUnit.SECONDS)); ready.complete(Unit)
                        val id = withTimeout(10_000) { published.await() }
                        assertTrue(store.exists(id)); result.cancel()
                    } finally { resume.countDown(); ready.complete(Unit) }
                    result.join(); assertTrue(result.isCancelled)
                } }
            } finally { observer.stopWatching(); resume.countDown() }
        }
        store.collect(); assertTrue(dir.list()!!.isEmpty())
    } }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun malformedSelectionLeavesNoPendingOrPublishedBackground() = runBlocking { fixture { app, dir ->
        val store = BackgroundStore(app) { emptySet() }
        selected("<svg/>".toByteArray()) { uri -> assertTrue(runCatching { store.importImage(uri) }.isFailure) }
        assertTrue(dir.list()!!.isEmpty())
    } }
}
