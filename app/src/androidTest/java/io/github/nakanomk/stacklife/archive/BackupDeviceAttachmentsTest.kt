package io.github.nakanomk.stacklife.archive

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.assistant.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupDeviceAttachmentsTest {
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).archiveHex()
    private fun photo(file: File, format: Bitmap.CompressFormat, color: Int) {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try { bmp.eraseColor(color); file.outputStream().use { check(bmp.compress(format, 95, it)) } } finally { bmp.recycle() }
    }

    @Suppress("DEPRECATION")
    @Test fun everyTypedFileSurvivesReopenAndCollidingHistoryOrDraftBytesAreNeverOverwritten() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val temp = Files.createTempDirectory(base.cacheDir.toPath(), "archive-files").toFile()
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = temp
            override fun getApplicationContext(): Context = this
        }
        val owned = linkedMapOf<String, ChatAttachment>()
        fun open() = AttachmentStore(context, { owned.keys.toSet() })
        val store = open()
        try {
            val entries = linkedMapOf<ChatAttachment, File>()
            for ((format, mime) in listOf(Bitmap.CompressFormat.JPEG to "image/jpeg", Bitmap.CompressFormat.PNG to "image/png", Bitmap.CompressFormat.WEBP to "image/webp")) {
                val id = UUID.randomUUID().toString(); val source = File(temp, "incoming-$id")
                photo(source, format, Color.BLUE); entries[ChatImage(id, mime, 8, 8, source.length())] = source
            }
            for ((name, mime, content) in listOf(Triple("plain.txt", "text/plain", "hello"), Triple("notes.md", "text/markdown", "# Notes"),
                Triple("events.csv", "text/csv", "a,b\n1,2"), Triple("data.json", "application/json", "{\"day\":4}"),
                Triple("receipt.pdf", PDF_MIME, "%PDF-1.7\n1 0 obj\nendobj\n%%EOF"))) {
                val id = UUID.randomUUID().toString(); val source = File(temp, "incoming-$id").apply { writeText(content) }
                entries[ChatDocument(id, mime, name, source.length())] = source
            }
            store.withArchiveLease { lease ->
                for ((meta, source) in entries) { assertEquals(meta.id, lease.publish(meta, source, digest(source))); owned[meta.id] = meta }
            }
            val reopened = open(); reopened.recover(); val expected = owned.toMap()
            reopened.withArchiveLease { lease ->
                val (_, files) = lease.snapshotFiles({ expected }, { it })
                for ((meta, source) in entries) {
                    assertEquals(digest(source), digest(files.getValue(meta.id)))
                    assertEquals(meta.id, lease.publish(meta, source, digest(source)))
                    assertTrue(runCatching { reopened.remove(meta.id) }.isFailure)
                }
            }
            val text = expected.values.filterIsInstance<ChatDocument>().first()
            val replacement = File(temp, "replacement").apply { writeText("world") }
            var published = ""
            reopened.withArchiveLease { lease ->
                published = lease.publish(text.copy(bytes = replacement.length()), replacement, digest(replacement)); assertNotEquals(text.id, published)
                owned[published] = text.copy(id = published, bytes = replacement.length())
                assertEquals("hello", reopened.file(text.id).readText()); assertEquals("world", reopened.file(published).readText())
            }
            val reopenedAgain = open(); reopenedAgain.recover()
            assertEquals("world", text.copy(id = published, bytes = 5).readValidated(reopenedAgain.file(published)))
            assertEquals("hello", text.readValidated(reopenedAgain.file(text.id)))
            // Correct size and UUID are not enough: UTF-8 and photo dimensions must also verify.
            replacement.writeBytes(byteArrayOf(0xC3.toByte(), 0x28)); val forged = text.copy(bytes = 2)
            val image = entries.keys.filterIsInstance<ChatImage>().first()
            reopenedAgain.withArchiveLease { lease ->
                assertTrue(runCatching { lease.publish(forged, replacement, digest(replacement)) }.isFailure)
                assertTrue(runCatching { lease.publish(image.copy(width = 7), entries.getValue(image), digest(entries.getValue(image))) }.isFailure)
                assertTrue(runCatching { lease.publish(text, entries.getValue(text), "0".repeat(64)) }.isFailure)
            }
            val draft = reopenedAgain.duplicate(image)
            owned.remove(text.id)
            reopenedAgain.collect()
            assertFalse(reopenedAgain.file(text.id).exists()); assertTrue(draft.matchesPreparedFile(reopenedAgain.file(draft.id)))
            reopenedAgain.withArchiveLease { lease ->
                // Even identical content may not transfer a live unsent draft's sole identity.
                val replacementId = lease.publish(draft, entries.getValue(image), digest(entries.getValue(image)))
                assertNotEquals(draft.id, replacementId)
            }
            reopenedAgain.remove(draft.id)
            assertEquals(owned.keys, File(temp, "chat-images").list()!!.toSet())
        } finally { temp.deleteRecursively() }
    }

    @Test fun abortedPublicationAndFailedOwnershipReadPreserveReferencedBytesAndReleasePins() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dirName = "archive-attachments-${UUID.randomUUID()}"
        val root = File(base.noBackupFilesDir, dirName); val body = File.createTempFile("fixture", ".txt", base.cacheDir).apply { writeText("pinned") }
        val keep = mutableSetOf<String>(); var failRead = false
        fun open() = AttachmentStore(base, { check(!failRead); keep.toSet() }, dirName)
        val store = open(); val meta = ChatDocument(UUID.randomUUID().toString(), "text/plain", "note.txt", body.length())
        try {
            val failed = runCatching { store.withArchiveLease { lease -> lease.publish(meta, body, digest(body)); error("commit aborted") } }
            assertTrue(failed.isFailure); assertFalse(store.file(meta.id).exists())
            store.withArchiveLease { lease -> assertEquals(meta.id, lease.publish(meta, body, digest(body))); keep.add(meta.id); failRead = true }
            assertTrue(store.file(meta.id).isFile)
            val resumed = open(); assertTrue(runCatching { resumed.recover() }.isFailure); assertEquals("pinned", store.file(meta.id).readText())
            failRead = false; resumed.recover()
            resumed.withArchiveLease { lease ->
                val (_, files) = lease.snapshotFiles({ mapOf(meta.id to meta) }, { it }); keep.clear(); resumed.remove(meta.id)
                assertEquals("pinned", files.getValue(meta.id).readText())
            }
            assertFalse(resumed.file(meta.id).exists()); resumed.remove(meta.id); assertTrue(root.list()!!.isEmpty())
        } finally { root.deleteRecursively(); body.delete() }
    }
}
