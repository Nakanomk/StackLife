package io.github.nakanomk.stacklife.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.appearance.gainmapPhoto
import io.github.nakanomk.stacklife.appearance.motionPhoto
import io.github.nakanomk.stacklife.appearance.photoFixture
import io.github.nakanomk.stacklife.appearance.redPixels
import io.github.nakanomk.stacklife.appearance.withSelectedPhoto
import io.github.nakanomk.stacklife.images.PhotoDestination.CHAT
import io.github.nakanomk.stacklife.images.PhotoProblem
import io.github.nakanomk.stacklife.images.matchesFile
import io.github.nakanomk.stacklife.images.normalizeStaticPhoto
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
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

    @RequiresApi(29)
    private suspend fun <T> withMedia(type: String, body: ByteArray, block: suspend (Uri) -> T): T =
        withSelectedPhoto(context, body, type) { block(it) }

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
    @Test fun pickedMotionGainmapAndRotatedHeicSendOnlyTheirPreparedSdrPixels() = runBlocking {
        val folder = "photo-wire-${UUID.randomUUID()}"
        val store = AttachmentStore(context, { emptySet() }, folder)
        val root = File(context.noBackupFilesDir, folder)
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        try { MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val profile = serviceProfile("https://127.0.0.1:${server.port}/v1", "test-model", true)
            val transport = OpenAiTransport(client)
            val messages = Json.parseToJsonElement("""[{"role":"user","content":""}]""").jsonArray
            val tools = JsonArray(emptyList())
            val hdr = gainmapPhoto()
            val hdrBitmap = BitmapFactory.decodeByteArray(hdr, 0, hdr.size)
            try { assertTrue(hdrBitmap.hasGainmap()) } finally { hdrBitmap.recycle() }
            val sources = listOf("image/jpeg" to motionPhoto(pixels(64, 32, Bitmap.CompressFormat.JPEG)),
                "image/jpeg" to hdr, "image/heic" to photoFixture("red-blue-cw90.heic.b64"))
            val entries = sources.map { (mime, bytes) ->
                val image = withMedia(mime, bytes) { store.prepare(it) } // grant gone before local use or send
                val file = store.file(image.id)
                assertEquals("image/jpeg", image.mime)
                assertTrue(image.matchesPreparedFile(file))
                image to file
            }
            val turn = TurnImages(0, entries)
            validateImageTurn(profile, messages, tools, turn)
            val disabled = ServiceConnection(profile.copy(supportsImages = false), "fixture")
            assertTrue(runCatching { transport.complete(disabled, messages, tools, turn) }.exceptionOrNull() is ChatProblem)
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse(body = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}"""))
            transport.complete(ServiceConnection(profile, "fixture"), messages, tools, turn)
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val sent = Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject
                .getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray
            assertEquals(sources.size, sent.size)
            for ((i, part) in sent.withIndex()) {
                val url = part.jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
                assertEquals("data:image/jpeg;base64", url.substringBefore(','))
                val bytes = java.util.Base64.getDecoder().decode(url.substringAfter(','))
                assertArrayEquals(entries[i].second.readBytes(), bytes)
                assertFalse(sources[i].second.contentEquals(bytes))
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                try {
                    assertFalse(bitmap.hasGainmap()); assertTrue(bitmap.colorSpace!!.isSrgb)
                    val rotated = i == 2
                    assertEquals(if (rotated) 32 else 64, bitmap.width)
                    assertEquals(if (rotated) 64 else 32, bitmap.height)
                    assertTrue(Color.red(bitmap.getPixel(8, 8)) > 210)
                    if (i != 1) assertTrue(Color.blue(bitmap.getPixel(bitmap.width - 8, bitmap.height - 8)) > 210)
                } finally { bitmap.recycle() }
            }
            assertEquals(1, server.requestCount)

            // Replacing a private image with the original container is never an alternate import path,
            // even if the recorded length matches. Reading history, duplicating and sending all fail.
            for ((i, entry) in entries.withIndex()) {
                val file = entry.second.apply { writeBytes(sources[i].second) }
                val corrupt = entry.first.copy(bytes = file.length())
                assertTrue(runCatching { store.bitmap(corrupt, 64) }.exceptionOrNull() is ChatProblem)
                assertTrue(runCatching { store.duplicate(corrupt) }.exceptionOrNull() is ChatProblem)
                assertTrue(runCatching { transport.complete(ServiceConnection(profile, "fixture"), messages, tools,
                    TurnImages(0, listOf(corrupt to file))) }.exceptionOrNull() is ChatProblem)
            }
            assertEquals(entries.map { it.first.id }.toSet(), root.list()!!.toSet())
            assertEquals(1, server.requestCount)
        } } finally { root.deleteRecursively(); client.connectionPool.evictAll() }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun invalidPartialAnimatedAndOversizedSelectionsLeavePreparedDraftUntouched() = runBlocking {
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
            val previous = withMedia("image/jpeg", jpeg) { store.prepare(it) }
            // A valid CRC and IEND alone do not prove that the compressed scanlines are complete.
            val partialPng = png.copyOfRange(0, 33) + pngChunk("IDAT", byteArrayOf(0x78, 0x01, 0x01)) +
                pngChunk("IEND", byteArrayOf())
            val kept = store.file(previous.id).readBytes()
            for ((mime, bytes) in listOf("image/jpeg" to photoFixture("green-avc.mp4.b64"),
                "image/png" to partialPng, "image/png" to animatedPng, "image/webp" to animatedWebp,
                "image/png" to huge,
                "image/png" to "<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray(),
                "image/png" to png.copyOf(png.size - 1), "image/jpeg" to jpeg.copyOf(jpeg.size - 2))) {
                withMedia(mime, bytes) { uri -> assertTrue(runCatching { store.prepare(uri) }.exceptionOrNull() is ChatProblem) }
                assertEquals(listOf(previous.id), root.list()!!.toList())
            }
            withMedia("image/png", png) { uri ->
                val pfd = requireNotNull(context.contentResolver.openFileDescriptor(uri, "rw"))
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                    out.channel.position(MAX_IMAGE_INPUT_BYTES); out.write(0)
                }
                assertTrue(runCatching { store.prepare(uri) }.exceptionOrNull()?.message.orEmpty().contains("50 MiB"))
            }
            assertEquals(listOf(previous.id), root.list()!!.toList())
            assertArrayEquals(kept, store.file(previous.id).readBytes())
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

    @SdkSuppress(minSdkVersion = 29)
    @Test fun rasterAttachmentsKeepTransparencyAndRejectMetadataSplicedIntoSavedPixels() = runBlocking {
        val folder = "raster-draft-${UUID.randomUUID()}"
        val root = File(context.noBackupFilesDir, folder)
        val store = AttachmentStore(context, { emptySet() }, folder)
        try {
            @Suppress("DEPRECATION")
            for ((mime, format) in listOf("image/png" to Bitmap.CompressFormat.PNG, "image/webp" to Bitmap.CompressFormat.WEBP)) {
                // An image declaration is not proof of the codec. Select normalized output using
                // the encoded pixels and keep an alpha-capable format, including for opaque PNG text.
                val image = withMedia("image/jpeg", redPixels(format, 96)) { store.prepare(it) }
                assertEquals(mime, image.mime)
                val preview = store.bitmap(image, 64)
                try { assertEquals(96, Color.alpha(preview.getPixel(32, 16))) } finally { preview.recycle() }
                if (mime == "image/png") {
                    val file = store.file(image.id)
                    val clean = file.readBytes()
                    val metadata = pngChunk("tEXt", "GPS\u0000fixture coordinates".toByteArray())
                    file.writeBytes(clean.copyOfRange(0, 33) + metadata + clean.copyOfRange(33, clean.size))
                    val changed = image.copy(bytes = file.length())
                    assertFalse(changed.matchesPreparedFile(file))
                    assertTrue(runCatching { store.duplicate(changed) }.exceptionOrNull() is ChatProblem)
                }
                store.remove(image.id)
            }
            assertTrue(root.list()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @SdkSuppress(maxSdkVersion = 27)
    @Test fun legacyChatImportKeepsOnlyThePrimaryJpegAndExplainsAnUnsupportedHeic() {
        val dir = File(context.cacheDir, "legacy-chat-${UUID.randomUUID()}").apply { mkdir() }
        try {
            val source = File(dir, "input")
            val output = File(dir, "prepared")
            source.writeBytes(motionPhoto(pixels(64, 32, Bitmap.CompressFormat.JPEG)))
            val still = normalizeStaticPhoto(source, "application/octet-stream", output, CHAT)
            assertEquals("image/jpeg", still.mime); assertTrue(still.matchesFile(output))
            val previous = output.readBytes()
            source.writeBytes(photoFixture("red-blue-cw90.heic.b64"))
            val native = BitmapFactory.decodeFile(source.path)
            if (native == null) {
                val error = runCatching { normalizeStaticPhoto(source, "image/heic", output, CHAT) }.exceptionOrNull()
                assertTrue(error is PhotoProblem)
                assertEquals(io.github.nakanomk.stacklife.i18n.uiText("This system cannot decode this image.", "当前系统无法解码这张图片。"), error?.message)
                assertArrayEquals(previous, output.readBytes())
            } else {
                native.recycle()
                val heic = normalizeStaticPhoto(source, "image/heic", output, CHAT)
                assertTrue(heic.matchesFile(output)); assertEquals(32, heic.width); assertEquals(64, heic.height)
            }
        } finally { dir.deleteRecursively() }
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
            val image = normalizeStaticPhoto(source, "image/png", output, CHAT)
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
