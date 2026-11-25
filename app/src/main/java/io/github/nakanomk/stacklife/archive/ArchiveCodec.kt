package io.github.nakanomk.stacklife.archive

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// BiliPai UiSkinPackageReader / WebDavBackupService, c7f835b34d8323358be57be298f86f29fecb53c8,
// GPL-3.0. Version 1 uses STORED members: photos are already compressed, and this permits strict,
// single-pass validation even for non-seekable document providers. No whole ZIP or secret spool.
internal enum class ArchiveType(val wire: String) { APPEARANCE("stacklife.appearance"), BACKUP("stacklife.backup") }
internal interface ArchiveLayout {
    val type: ArchiveType
    fun limit(name: String): Long? // null rejects, including other categories' valid names
    fun validCounts(counts: Map<String, Int>): Boolean
}
internal data class ArchiveSource(val name: String, val open: () -> InputStream)
@Serializable internal data class ArchiveMember(val name: String, val bytes: Long, val sha256: String)
@Serializable internal data class ArchiveManifest(val version: Int, val contentType: String,
    val counts: Map<String, Int>, val members: List<ArchiveMember>, val complete: Boolean)

internal val archiveJson = Json { encodeDefaults = true }
internal const val ARCHIVE_TOTAL = 128L * 1024 * 1024
private const val MANIFEST = "manifest.json"
private const val MANIFEST_BYTES = 64L * 1024
private const val MAX_ENTRIES = 256
private const val NAME_BYTES = 100
private val path = Regex("[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)*")
private const val WIRE_LIMIT = ARCHIVE_TOTAL + MANIFEST_BYTES + MAX_ENTRIES * (76 + 2 * NAME_BYTES) + 22
private const val DIRECTORY_LIMIT = MAX_ENTRIES * (46 + NAME_BYTES) + 22
private fun allowed(layout: ArchiveLayout, name: String): Long {
    require(name.length <= NAME_BYTES && path.matches(name) && name != MANIFEST)
    return requireNotNull(layout.limit(name)).also { require(it in 0..ARCHIVE_TOTAL) }
}
private val hex = "0123456789abcdef".toCharArray()
internal fun ByteArray.archiveHex() = CharArray(size * 2).also { chars ->
    forEachIndexed { i, byte -> val n = byte.toInt() and 255; chars[2*i] = hex[n ushr 4]; chars[2*i+1] = hex[n and 15] }
}.concatToString()

internal object ArchiveCodec {
    /** Output ownership transfers to the ZIP writer. Return only after its central directory and close succeed. */
    fun write(layout: ArchiveLayout, counts: Map<String, Int>, sources: List<ArchiveSource>, output: OutputStream,
        checkActive: () -> Unit = {}) {
        ZipOutputStream(output.buffered()).use { zip ->
            require(sources.size < MAX_ENTRIES && layout.validCounts(counts))
            val members = ArrayList<ArchiveMember>(sources.size)
            val names = HashSet<String>()
            val scratch = ByteArray(16 * 1024)
            var remaining = ARCHIVE_TOTAL
            fun put(name: String, size: Long, crc: Long, input: InputStream): String {
                input.use {
                    zip.putNextEntry(ZipEntry(name).apply {
                        time = 946684800000L // ordinary DOS date; no timestamp extra field
                        method = ZipEntry.STORED; this.size = size; compressedSize = size; this.crc = crc
                    })
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    while (true) {
                        checkActive(); val n = it.read(scratch); if (n < 0) break
                        copied += n; require(copied <= size); digest.update(scratch, 0, n); zip.write(scratch, 0, n)
                    }
                    require(copied == size); zip.closeEntry(); return digest.digest().archiveHex()
                }
            }
            for (source in sources) {
                val cap = minOf(allowed(layout, source.name), remaining)
                require(names.add(source.name)); var size = 0L; val crc = CRC32()
                source.open().use { input ->
                    while (true) {
                        checkActive(); val n = input.read(scratch); if (n < 0) break
                        size += n; require(size <= cap); crc.update(scratch, 0, n)
                    }
                }
                remaining -= size
                members += ArchiveMember(source.name, size, put(source.name, size, crc.value, source.open()))
            }
            val bytes = archiveJson.encodeToString(ArchiveManifest(1, layout.type.wire, counts, members, true)).encodeToByteArray()
            require(bytes.size <= MANIFEST_BYTES)
            put(MANIFEST, bytes.size.toLong(), CRC32().apply { update(bytes) }.value, bytes.inputStream())
        }
    }

    /** [stage] opens disposable, caller-owned private sinks only. Nothing is authoritative until read returns. */
    fun read(layout: ArchiveLayout, input: InputStream, stage: (String) -> OutputStream,
        checkActive: () -> Unit = {}): ArchiveManifest {
        val wire = ArchiveWire(input.buffered())
        ZipInputStream(wire).use { zip ->
            val members = ArrayList<ArchiveMember>()
            val directory = ArrayList<IndexEntry>()
            val names = HashSet<String>(); val scratch = ByteArray(16 * 1024)
            var remaining = ARCHIVE_TOTAL; var offset = 0L
            while (true) {
                checkActive()
                val entry = requireNotNull(zip.nextEntry)
                require(directory.size < MAX_ENTRIES && names.add(entry.name) && !entry.isDirectory && entry.extra.isNullOrEmptyBytes())
                val manifest = entry.name == MANIFEST
                val cap = if (manifest) MANIFEST_BYTES else minOf(allowed(layout, entry.name), remaining)
                require(entry.method == ZipEntry.STORED && entry.size in 0L..cap && entry.compressedSize == entry.size)
                val record = if (manifest) ByteArrayOutputStream(entry.size.toInt()) else null
                val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
                (record ?: stage(entry.name)).use { out ->
                    while (true) {
                        checkActive(); val n = zip.read(scratch); if (n < 0) break
                        size += n; require(size <= entry.size); digest.update(scratch, 0, n); out.write(scratch, 0, n)
                    }
                }
                require(size == entry.size); zip.closeEntry()
                directory += IndexEntry(entry.name, size, entry.crc, offset)
                offset += 30 + entry.name.length + size
                if (record != null) {
                    val bytes = record.toByteArray(); val text = bytes.decodeToString(throwOnInvalidSequence = true)
                    val result = archiveJson.decodeFromString<ArchiveManifest>(text)
                    require(result.version == 1 && result.complete && result.contentType == layout.type.wire &&
                        layout.validCounts(result.counts) && result.members == members && archiveJson.encodeToString(result) == text)
                    val expected = directory.sumOf { 46 + it.name.length } + 22
                    // ZipInputStream accepts missing/truncated central directories. Check both views, including EOF.
                    while (wire.count <= offset + expected) { checkActive(); if (wire.read(scratch) < 0) break }
                    require(wire.count == offset + expected)
                    verifyDirectory(wire.tail(expected), directory, offset.toInt())
                    return result
                }
                remaining -= size; members += ArchiveMember(entry.name, size, digest.digest().archiveHex())
            }
        }
    }
}
private fun ByteArray?.isNullOrEmptyBytes() = this == null || isEmpty()
private data class IndexEntry(val name: String, val size: Long, val crc: Long, val offset: Long)
private fun verifyDirectory(bytes: ByteArray, entries: List<IndexEntry>, start: Int) {
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun u16() = b.short.toInt() and 65535
    fun u32() = b.int.toLong() and 0xffffffffL
    for (entry in entries) {
        require(b.int == 0x02014b50); b.position(b.position() + 4)
        require(u16() and 0xf7ff == 0 && u16() == ZipEntry.STORED); b.position(b.position() + 4)
        require(u32() == entry.crc && u32() == entry.size && u32() == entry.size)
        require(u16() == entry.name.length && u16() == 0 && u16() == 0 && u16() == 0)
        b.position(b.position() + 6); require(u32() == entry.offset)
        entry.name.forEach { require(b.get().toInt() == it.code) }
    }
    require(b.int == 0x06054b50 && u16() == 0 && u16() == 0 && u16() == entries.size && u16() == entries.size &&
        b.int == bytes.size - 22 && b.int == start && u16() == 0 && !b.hasRemaining())
}
// Capture only the bounded directory tail, including any read-ahead by the platform ZIP implementation.
private class ArchiveWire(private val input: InputStream) : InputStream() {
    private val ring = ByteArray(DIRECTORY_LIMIT)
    var count = 0L
        private set
    override fun read(): Int = input.read().also { n ->
        if (n >= 0) { ring[(count % ring.size).toInt()] = n.toByte(); require(++count <= WIRE_LIMIT) }
    }
    override fun read(bytes: ByteArray, off: Int, len: Int): Int {
        val n = input.read(bytes, off, len); if (n <= 0) return n
        val kept = minOf(n, ring.size); val skip = n - kept; val pos = ((count + skip) % ring.size).toInt()
        val first = minOf(kept, ring.size - pos)
        bytes.copyInto(ring, pos, off + skip, off + skip + first)
        bytes.copyInto(ring, 0, off + skip + first, off + n)
        count += n; require(count <= WIRE_LIMIT); return n
    }
    fun tail(n: Int): ByteArray {
        require(n <= ring.size && count >= n); val pos = ((count - n) % ring.size).toInt(); val first = minOf(n, ring.size - pos)
        return ByteArray(n).also { ring.copyInto(it, 0, pos, pos + first); ring.copyInto(it, first, 0, n - first) }
    }
    override fun close() = input.close()
}
