package io.github.nakanomk.stacklife.archive

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ArchiveCodecTest {
    private val name = "appearance/settings.json"
    private val data = "{}".encodeToByteArray()
    private fun valid(): ByteArray = ByteArrayOutputStream().also {
        ArchiveCodec.write(AppearanceLayout, mapOf("appearance" to 1), listOf(ArchiveSource(name) { data.inputStream() }), it)
    }.toByteArray()
    private fun entries(wire: ByteArray): List<Pair<String, ByteArray>> = ZipInputStream(wire.inputStream()).use { zip ->
        buildList { while (true) { val entry = zip.nextEntry ?: break; add(entry.name to zip.readBytes()) } }
    }
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> for ((path, bytes) in entries) {
            zip.putNextEntry(ZipEntry(path).apply {
                method = ZipEntry.STORED; size = bytes.size.toLong(); crc = CRC32().apply { update(bytes) }.value; time = 946684800000L
            }); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
    private fun invalid(wire: ByteArray, layout: ArchiveLayout = AppearanceLayout) {
        try { ArchiveCodec.read(layout, wire.inputStream(), { ByteArrayOutputStream() }); fail("Archive accepted") }
        catch (_: IllegalArgumentException) { } catch (_: IOException) { } catch (_: java.nio.BufferUnderflowException) { }
    }
    @Test fun completeDocumentClosesAndOnlyDeclaredTypedMembersReachStaging() {
        val staged = HashMap<String, ByteArrayOutputStream>()
        val result = ArchiveCodec.read(AppearanceLayout, valid().inputStream(), { ByteArrayOutputStream().also { file -> staged[it] = file } })
        assertEquals(setOf(name), staged.keys); assertArrayEquals(data, staged.getValue(name).toByteArray())
        assertEquals(ArchiveType.APPEARANCE.wire, result.contentType); assertEquals(mapOf("appearance" to 1), result.counts)
        val badClose = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun close(): Unit = throw IOException("SAF provider failed to finish")
        }
        try { ArchiveCodec.write(AppearanceLayout, mapOf("appearance" to 1), listOf(ArchiveSource(name) { data.inputStream() }), badClose); fail() }
        catch (_: IOException) { }
    }
    @Test fun traversalForeignContentUndeclaredMembersAndDuplicateLocalPathsFailClosed() {
        val (record, manifest) = entries(valid())
        for (wrong in listOf("../escape", "/appearance/settings.json", "appearance/./settings.json", "appearance\\settings.json", "connections/keys.json", "history/messages")) {
            invalid(zip(listOf(wrong to data, record, manifest)))
        }
        invalid(zip(listOf(record, "appearance/photos/12345678-1234-1234-1234-123456789012" to data, manifest)))
        // java.util.zip refuses to produce duplicate names. Equal-width wire substitution in both indexes.
        val duplicated = zip(listOf(record, "appearance/settings.jsoX" to data, manifest))
        replaceAscii(duplicated, "appearance/settings.jsoX", name); invalid(duplicated)
    }
    @Test fun wrongVersionMissingCompletionBadDigestAndFalseCentralDirectoryFailClosed() {
        val wire = valid(); val (record, manifest) = entries(wire); val text = manifest.second.decodeToString()
        for (changed in listOf(text.replace("\"version\":1", "\"version\":2"), text.replace("true", "false"),
            text.replace("stacklife.appearance", "stacklife.backup"), text.replace("\"appearance\":1", "\"history\":1")))
            invalid(zip(listOf(record, manifest.first to changed.encodeToByteArray())))
        invalid(zip(listOf(record))); invalid(zip(listOf(record.first to "XX".encodeToByteArray(), manifest)))
        invalid(wire.copyOf(wire.size - 1)); invalid(wire + 0.toByte()); invalid(zip(listOf(record, manifest, "secret" to data)))
        val falseDirectory = wire.copyOf(); val start = falseDirectory.indexOfLastAscii(name); falseDirectory[start] = 'x'.code.toByte(); invalid(falseDirectory)
        invalid(wire, object : ArchiveLayout by AppearanceLayout { override fun limit(name: String) = 1L })
    }
    private fun ByteArray.indexOfLastAscii(text: String): Int = (size - text.length downTo 0).first { i -> text.indices.all { this[i + it].toInt() == text[it].code } }
    private fun replaceAscii(bytes: ByteArray, old: String, replacement: String) {
        require(old.length == replacement.length)
        for (i in 0..bytes.size-old.length) if (old.indices.all { bytes[i + it].toInt() == old[it].code }) replacement.encodeToByteArray().copyInto(bytes, i)
    }
}
