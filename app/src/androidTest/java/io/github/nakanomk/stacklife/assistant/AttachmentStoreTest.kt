package io.github.nakanomk.stacklife.assistant

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.appearance.gainmapPhoto
import io.github.nakanomk.stacklife.appearance.motionPhoto
import io.github.nakanomk.stacklife.appearance.photoFixture
import io.github.nakanomk.stacklife.images.PhotoSourcePolicy
import io.github.nakanomk.stacklife.images.normalizeStaticPhoto
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pixels(w: Int, h: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.BLUE)
            val paint = android.graphics.Paint().apply { color = Color.RED }
            android.graphics.Canvas(bitmap).drawRect(0f, 0f, w / 2f, h.toFloat(), paint)
            ByteArrayOutputStream().use { assertTrue(bitmap.compress(format, 95, it)); it.toByteArray() }
        } finally { bitmap.recycle() }
    }

    /** A real, pending media item owned by this fixture; no gallery access or durable URI grants. */
    @RequiresApi(29)
    private suspend fun withMedia(type: String, body: ByteArray, block: suspend (Uri) -> Unit) {
        val resolver = context.contentResolver
        val suffix = if (type == "image/jpeg") "jpg" else type.substringAfter('/')
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "StackLife-test-${UUID.randomUUID()}.$suffix")
            put(MediaStore.MediaColumns.MIME_TYPE, type); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(body) }
            block(uri)
        } finally { resolver.delete(uri, null, null) }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun preparesExifUprightPixelsStripsGpsAndSamplesTheActualSentFile() = runBlocking {
        val folder = "image-fixture-${UUID.randomUUID()}"
        val store = AttachmentStore(context, { emptySet() }, folder)
        val raw = File.createTempFile("orientation", ".jpg", context.cacheDir)
        try {
            raw.writeBytes(pixels(64, 32, Bitmap.CompressFormat.JPEG))
            ExifInterface(raw.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, "6")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,2/1,3/1"); setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_MAKE, "fixture-camera"); saveAttributes()
            }
            assertNotNull(ExifInterface(raw.path).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            withMedia("image/jpeg", raw.readBytes()) { uri ->
                val image = store.prepare(uri)
                assertEquals(32, image.width); assertEquals(64, image.height)
                val sent = store.file(image.id)
                assertEquals(File(context.noBackupFilesDir, folder), sent.parentFile)
                assertEquals(sent.length(), image.bytes)
                val scrubbed = ExifInterface(sent.path)
                assertNull(scrubbed.getAttribute(ExifInterface.TAG_MAKE)); assertNull(scrubbed.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                val preview = store.bitmap(image, 16)
                try {
                    assertEquals(8, preview.width); assertEquals(16, preview.height)
                    assertTrue(Color.red(preview.getPixel(4, 3)) > 200); assertTrue(Color.blue(preview.getPixel(4, 12)) > 200)
                } finally { preview.recycle() }
                store.remove(image.id); assertFalse(sent.exists())
            }
        } finally { raw.delete(); File(context.noBackupFilesDir, folder).deleteRecursively() }
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test fun backgroundOnlyContainersRemainForbiddenForPreparationAndUpload() = runBlocking {
        val folder = "photo-policy-${UUID.randomUUID()}"
        val store = AttachmentStore(context, { emptySet() }, folder)
        val root = File(context.noBackupFilesDir, folder)
        try {
            val motion = motionPhoto(pixels(64, 32, Bitmap.CompressFormat.JPEG))
            for ((declared, bytes) in listOf("image/jpeg" to motion, "image/jpeg" to gainmapPhoto(),
                "image/heic" to photoFixture("red-blue-cw90.heic.b64"))) {
                withMedia(declared, bytes) { uri ->
                    assertTrue(runCatching { store.prepare(uri) }.exceptionOrNull() is ChatProblem)
                }
                assertTrue(root.list()!!.isEmpty())
                val replaced = File(root, UUID.randomUUID().toString()).apply { writeBytes(bytes) }
                val image = ChatImage(replaced.name, declared, 64, 32, replaced.length())
                assertFalse(image.matchesPreparedFile(replaced, forUpload = true))
                assertTrue(replaced.delete())
            }
        } finally { root.deleteRecursively() }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun rejectsMislabeledTruncatedAnimatedActiveAndOversizedInputsWithoutLeakingDrafts() = runBlocking {
        val folder = "image-bounds-${UUID.randomUUID()}"
        val store = AttachmentStore(context, { emptySet() }, folder)
        val root = File(context.noBackupFilesDir, folder)
        try {
            val png = pixels(16, 12, Bitmap.CompressFormat.PNG)
            val animatedPng = png.copyOfRange(0, 33) + pngChunk("acTL", ByteBuffer.allocate(8).putInt(2).putInt(0).array()) + png.copyOfRange(33, png.size)
            val huge = png.copyOfRange(0, 8) + pngChunk("IHDR", png.copyOfRange(16, 29).apply {
                ByteBuffer.wrap(this).putInt(999_999).putInt(999_999)
            }) + png.copyOfRange(33, png.size)
            @Suppress("DEPRECATION") val webp = pixels(16, 12, Bitmap.CompressFormat.WEBP)
            val animatedWebp = animatedWebp(webp, 16, 12)
            val jpeg = pixels(16, 12, Bitmap.CompressFormat.JPEG)
            for ((mime, bytes) in listOf("image/jpeg" to png, "image/png" to animatedPng, "image/webp" to animatedWebp,
                "image/png" to huge,
                "image/png" to "<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray(),
                "image/png" to png.copyOf(png.size - 1), "image/jpeg" to jpeg.copyOf(jpeg.size - 2), "image/jpeg" to jpeg + jpeg)) {
                withMedia(mime, bytes) { uri -> assertTrue(runCatching { store.prepare(uri) }.exceptionOrNull() is ChatProblem) }
                assertTrue(root.listFiles()!!.isEmpty())
            }
            withMedia("image/png", png) { uri ->
                val pfd = requireNotNull(context.contentResolver.openFileDescriptor(uri, "rw"))
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                    out.channel.position(MAX_IMAGE_INPUT_BYTES); out.write(0)
                }
                assertTrue(runCatching { store.prepare(uri) }.exceptionOrNull()?.message.orEmpty().contains("50 MiB"))
            }
            assertTrue(root.listFiles()!!.isEmpty())
            assertTrue(runCatching { store.prepare(Uri.parse("https://private.invalid/photo")) }.exceptionOrNull() is ChatProblem)
            withMedia("image/png", pixels(4150, 64, Bitmap.CompressFormat.PNG)) { uri ->
                val image = store.prepare(uri)
                assertTrue(image.width in 1..2048); assertEquals("image/png", image.mime)
                store.remove(image.id)
            }
            withMedia("image/webp", webp) { uri ->
                val image = store.prepare(uri)
                assertEquals("image/webp", image.mime); assertEquals(16, image.width); store.remove(image.id)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun platformCodecAlsoAppliesEmbeddedPngOrientationWithoutPlatformPngExifSupport() {
        val dir = File(context.cacheDir, "png-orientation-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val original = pixels(64, 32, Bitmap.CompressFormat.PNG)
            val ifd = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(0x4949); putShort(42); putInt(8); putShort(1)
                putShort(0x112); putShort(3); putInt(1); putShort(6); putShort(0); putInt(0)
            }.array()
            val source = File(dir, "source").apply {
                writeBytes(original.copyOfRange(0, 33) + pngChunk("eXIf", ifd) + original.copyOfRange(33, original.size))
            }
            val id = UUID.randomUUID().toString()
            val output = File(dir, id)
            val image = normalizeStaticPhoto(source, "image/png", output, PhotoSourcePolicy.STRICT_STATIC)
            assertEquals(32, image.width); assertEquals(64, image.height)
            val bitmap = android.graphics.BitmapFactory.decodeFile(output.path)
            try {
                assertEquals(Color.RED, bitmap.getPixel(16, 5)); assertEquals(Color.BLUE, bitmap.getPixel(16, 58))
            } finally { bitmap.recycle() }
            assertFalse(output.readBytes().toString(Charsets.ISO_8859_1).contains("eXIf"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun recoveryNeverUnlinksDurableOrInFlightFilesAndRetriesFailedReferenceRead() = runBlocking {
        val name = "image-recovery-${UUID.randomUUID()}"
        val dir = File(context.noBackupFilesDir, name).apply { mkdirs() }
        try {
            fun uid() = UUID.randomUUID().toString()
            val persisted = File(dir, uid()).apply { writeBytes(pixels(8, 8, Bitmap.CompressFormat.PNG)) }
            val image = ChatImage(persisted.name, "image/png", 8, 8, persisted.length())
            val orphan = File(dir, uid()).apply { writeText("orphan") }
            val partial = File(dir, ".pending-${uid()}").apply { writeText("partial") }
            val refs = mutableSetOf(image.id)
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var fail = true
            val store = AttachmentStore(context, {
                if (fail) error("simulated reference read failure")
                started.complete(Unit); release.await(); refs.toSet()
            }, name)
            assertTrue(runCatching { store.recover() }.isFailure)
            assertTrue(orphan.exists()); assertTrue(persisted.exists()); assertTrue(partial.exists())
            fail = false
            val duplicate = async { store.duplicate(image) }
            started.await()
            val startup = async { store.recover() }; release.complete(Unit)
            val new = duplicate.await(); startup.await()
            assertNotEquals(image.id, new.id)
            assertArrayEquals(persisted.readBytes(), store.file(new.id).readBytes())
            store.recover(); assertTrue(store.file(new.id).exists())
            assertFalse(orphan.exists()); assertFalse(partial.exists())
            assertTrue(runCatching { store.remove(image.id) }.exceptionOrNull() is ChatProblem); assertTrue(persisted.exists())
            store.remove(new.id); assertFalse(store.file(new.id).exists())
            refs.clear(); store.remove(image.id); assertFalse(persisted.exists())
            assertTrue(runCatching { store.file("../escape") }.isFailure)
        } finally { dir.deleteRecursively() }
    }

    private fun pngChunk(tag: String, data: ByteArray): ByteArray {
        val name = tag.toByteArray(Charsets.US_ASCII)
        val result = ByteArrayOutputStream()
        DataOutputStream(result).use { it.writeInt(data.size); it.write(name); it.write(data)
            it.writeInt(CRC32().apply { update(name); update(data) }.value.toInt()) }
        return result.toByteArray()
    }

    private fun animatedWebp(still: ByteArray, width: Int, height: Int): ByteArray {
        fun le32(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        fun chunk(name: String, bytes: ByteArray) = name.toByteArray(Charsets.US_ASCII) + le32(bytes.size) + bytes + ByteArray(bytes.size and 1)
        val frame = ByteArray(16).apply { this[6] = (width - 1).toByte(); this[9] = (height - 1).toByte(); this[12] = 50 } + still.copyOfRange(12, still.size)
        val chunks = "WEBP".toByteArray() + chunk("VP8X", ByteArray(10).apply { this[0] = 2; this[4] = (width - 1).toByte(); this[7] = (height - 1).toByte() }) +
            chunk("ANIM", ByteArray(6)) + chunk("ANMF", frame) + chunk("ANMF", frame)
        return "RIFF".toByteArray() + le32(chunks.size) + chunks
    }
}
