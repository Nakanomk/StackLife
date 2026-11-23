package io.github.nakanomk.stacklife.appearance

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import android.media.ExifInterface
import android.net.Uri
import android.os.FileObserver
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.images.MAX_PHOTO_BYTES
import io.github.nakanomk.stacklife.images.PhotoInfo
import io.github.nakanomk.stacklife.images.PhotoProblem
import io.github.nakanomk.stacklife.images.PhotoDestination.BACKGROUND
import io.github.nakanomk.stacklife.images.matchesFile
import io.github.nakanomk.stacklife.images.normalizeStaticPhoto
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
    private suspend fun selected(bytes: ByteArray, mime: String = "image/jpeg", block: suspend (Uri) -> Unit) =
        withSelectedPhoto(context, bytes, mime) { block(it) }

    @Test fun motionPhotoWithoutImageMimeKeepsOnlyTheCompletePrimaryStillEvenOnLegacyAndroid() = runBlocking { fixture { app, _ ->
        val source = File(app.noBackupFilesDir, "testMP.jpg")
        val output = File(app.noBackupFilesDir, "normalized")
        val jpeg = redPixels(Bitmap.CompressFormat.JPEG)
        source.writeBytes(motionPhoto(jpeg))
        for (declared in listOf(null, "application/octet-stream")) {
            val photo = normalizeStaticPhoto(source, declared, output, BACKGROUND)
            assertEquals(PhotoInfo("image/jpeg", 64, 32, output.length()), photo)
            assertTrue(photo.matchesFile(output)) // exact EOI, no APP1 or MP4
            val image = BitmapFactory.decodeFile(output.path)
            try { assertTrue(Color.red(image.getPixel(32, 16)) > 235) } finally { image.recycle() }
        }
        for (mime in listOf("video/mp4", "text/xml", "image/svg+xml")) {
            assertTrue(runCatching { normalizeStaticPhoto(source, mime, output, BACKGROUND) }.exceptionOrNull() is PhotoProblem)
        }
        source.writeBytes(jpeg.copyOf(jpeg.size - 2))
        assertTrue(runCatching { normalizeStaticPhoto(source, "image/jpeg", output, BACKGROUND) }.exceptionOrNull() is PhotoProblem)
        source.writeBytes(photoFixture("green-avc.mp4.b64"))
        assertTrue(runCatching { normalizeStaticPhoto(source, null, output, BACKGROUND) }.exceptionOrNull() is PhotoProblem)
    } }

    @Test fun largeTransparentSourceUsesBoundedWebpAndOversizedHeaderCannotReplaceIt() = runBlocking { fixture { app, _ ->
        val source = File(app.noBackupFilesDir, "source.png")
        val output = File(app.noBackupFilesDir, "still")
        val noise = Bitmap.createBitmap(2304, 1280, Bitmap.Config.ARGB_8888)
        try {
            val random = java.util.Random(17)
            val row = IntArray(noise.width)
            for (y in 0 until noise.height) {
                for (x in row.indices) row[x] = random.nextInt() or 0x40000000
                noise.setPixels(row, 0, row.size, 0, y, row.size, 1)
            }
            source.outputStream().use { check(noise.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { noise.recycle() }
        assertTrue(source.length() > 8L * 1024 * 1024)
        val result = normalizeStaticPhoto(source, "image/png", output, BACKGROUND)
        assertEquals("image/webp", result.mime)
        assertTrue(result.width in 1..2048); assertTrue(result.height in 1..2048)
        assertTrue(result.matchesFile(output))
        val tooWide = Bitmap.createBitmap(65_536, 1, Bitmap.Config.ARGB_8888)
        try { source.outputStream().use { check(tooWide.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { tooWide.recycle() }
        assertTrue(runCatching { normalizeStaticPhoto(source, "image/png", output, BACKGROUND) }.exceptionOrNull() is PhotoProblem)
        assertTrue(result.matchesFile(output))
    } }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun wideGamutPrimaryIsConvertedToSrgbRatherThanRelabeled() = runBlocking { fixture { app, _ ->
        val source = File(app.noBackupFilesDir, "p3.png")
        val output = File(app.noBackupFilesDir, "sdr")
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        val color = Color.valueOf(.7f, .4f, .3f, 1f, p3)
        val pixels = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888, false, p3)
        try {
            pixels.eraseColor(color.pack())
            source.outputStream().use { check(pixels.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { pixels.recycle() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        assertFalse("fixture must carry wide-gamut color", requireNotNull(bounds.outColorSpace).isSrgb)
        normalizeStaticPhoto(source, "image/png", output, BACKGROUND)
        val bitmap = BitmapFactory.decodeFile(output.path)
        try {
            assertEquals(srgb, bitmap.colorSpace)
            val actual = bitmap.getPixel(32, 16)
            val expected = color.convert(srgb).toArgb()
            for (shift in listOf(0, 8, 16)) {
                assertEquals((expected ushr shift and 255).toDouble(), (actual ushr shift and 255).toDouble(), 3.0)
            }
        } finally { bitmap.recycle() }
    } }

    @SdkSuppress(minSdkVersion = 34)
    @Test fun ultraHdrSelectionStoresOnlyTheSrgbBaseImage() = runBlocking { fixture { app, dir ->
        val source = gainmapPhoto()
        val hdr = BitmapFactory.decodeByteArray(source, 0, source.size)
        try { assertTrue("fixture must actually encode a gainmap", hdr.hasGainmap()) } finally { hdr.recycle() }
        val store = BackgroundStore(app) { emptySet() }
        selected(source) { uri ->
            val id = store.importImage(uri)
            val file = File(dir, id)
            assertTrue(PhotoInfo("image/jpeg", 64, 32, file.length()).matchesFile(file))
            val bitmap = BitmapFactory.decodeFile(file.path)
            try {
                assertFalse(bitmap.hasGainmap()); assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), bitmap.colorSpace)
                assertTrue(Color.red(bitmap.getPixel(32, 16)) > 235)
            } finally { bitmap.recycle() }
        }
    } }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun rotatedHeicSelectionStoresUprightPixelsAndIsIndependentOfTheGrant() = runBlocking { fixture { app, dir ->
        var retained = emptySet<String>()
        val store = BackgroundStore(app) { retained }
        selected(photoFixture("red-blue-cw90.heic.b64"), "image/heic") { uri ->
            val id = store.importImage(uri)
            retained = setOf(id); store.release(id)
        }
        val id = retained.single()
        val file = File(dir, id)
        assertTrue(PhotoInfo("image/jpeg", 32, 64, file.length()).matchesFile(file))
        val reopened = BackgroundStore(app) { retained }.bitmap(id, 64)
        try {
            assertEquals(32, reopened.width); assertEquals(64, reopened.height)
            assertTrue(Color.red(reopened.getPixel(16, 8)) > 210)
            assertTrue(Color.blue(reopened.getPixel(16, 56)) > 210)
        } finally { reopened.recycle() }
    } }

    @SdkSuppress(maxSdkVersion = 27)
    @Test fun unsupportedLegacyContainerLeavesExistingOutputAlone() = runBlocking { fixture { app, _ ->
        val dir = app.noBackupFilesDir
        val source = File(dir, "source")
        val output = File(dir, "photo")
        source.writeBytes(redPixels(Bitmap.CompressFormat.JPEG))
        normalizeStaticPhoto(source, "image/jpeg", output, BACKGROUND)
        val previous = output.readBytes()
        source.writeBytes(photoFixture("red-blue-cw90.heic.b64"))
        val native = BitmapFactory.decodeFile(source.path)
        if (native == null) {
            val error = runCatching { normalizeStaticPhoto(source, "image/heic", output, BACKGROUND) }.exceptionOrNull()
            assertTrue(error is PhotoProblem)
            assertEquals(io.github.nakanomk.stacklife.i18n.uiText("This system cannot decode this image.", "当前系统无法解码这张图片。"), error?.message)
            assertArrayEquals(previous, output.readBytes())
        } else {
            native.recycle()
            val normalized = normalizeStaticPhoto(source, "image/heic", output, BACKGROUND)
            assertEquals(32, normalized.width); assertEquals(64, normalized.height)
        }
    } }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun translucentPngAndWebpSelectionsRetainAlphaInPrivatePng() = runBlocking { fixture { app, dir ->
        val store = BackgroundStore(app) { emptySet() }
        @Suppress("DEPRECATION")
        for ((mime, format) in listOf("image/png" to Bitmap.CompressFormat.PNG, "image/webp" to Bitmap.CompressFormat.WEBP)) {
            selected(redPixels(format, 128), mime) { uri ->
                val id = store.importImage(uri)
                val file = File(dir, id)
                assertTrue(PhotoInfo("image/png", 64, 32, file.length()).matchesFile(file))
                val bitmap = store.bitmap(id, 64)
                try { assertEquals(128, Color.alpha(bitmap.getPixel(32, 16))) } finally { bitmap.recycle() }
                store.release(id)
            }
        }
    } }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun aDraftSurvivesCollectionAndOnlyTheLastCommittedOwnerReleasesItsUprightFile() = runBlocking { fixture { app, dir ->
        val owners = mutableMapOf<String, String>()
        val store = BackgroundStore(app) { owners.values.toSet() }
        val source = File(app.noBackupFilesDir, "source.jpg")
        val picture = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        try { picture.eraseColor(Color.RED); source.outputStream().use { assertTrue(picture.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
        finally { picture.recycle() }
        ExifInterface(source.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, "6"); setAttribute(ExifInterface.TAG_MAKE, "fixture")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,2/1,3/1"); setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            saveAttributes()
        }
        assertNotNull(ExifInterface(source.path).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        lateinit var id: String
        selected(source.readBytes()) { uri ->
            id = store.importImage(uri)
            val cancelled = store.importImage(uri)
            store.collect(); assertTrue(store.exists(id)); assertTrue(store.exists(cancelled))
            store.release(cancelled); assertFalse(store.exists(cancelled))
        }
        val image = store.bitmap(id, 64)
        try { assertEquals(8, image.width); assertEquals(16, image.height) } finally { image.recycle() }
        val clean = ExifInterface(File(dir, id).path)
        assertNull(clean.getAttribute(ExifInterface.TAG_MAKE)); assertNull(clean.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
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
    @Test fun malformedOversizedOrRevokedSelectionsLeaveOnlyThePreviousBackground() = runBlocking { fixture { app, dir ->
        var keep = emptySet<String>()
        val store = BackgroundStore(app) { keep }
        lateinit var expired: Uri
        selected(redPixels(Bitmap.CompressFormat.JPEG)) { uri ->
            expired = uri
            val id = store.importImage(uri)
            keep = setOf(id); store.release(id)
        }
        assertTrue(runCatching { store.importImage(expired) }.exceptionOrNull() is PhotoProblem)
        selected("<svg/>".toByteArray()) { uri -> assertTrue(runCatching { store.importImage(uri) }.exceptionOrNull() is PhotoProblem) }
        selected(byteArrayOf(0)) { uri ->
            val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "rw"))
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
                out.channel.position(MAX_PHOTO_BYTES); out.write(0)
            }
            assertTrue(runCatching { store.importImage(uri) }.exceptionOrNull()?.message.orEmpty().contains("50 MiB"))
        }
        assertEquals(keep, dir.list()!!.toSet())
    } }
}
