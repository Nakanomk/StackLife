package io.github.nakanomk.stacklife.images

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import io.github.nakanomk.stacklife.i18n.uiText
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.buffer
import okio.source

// GPTMobile v0.9.1 FileUtils.kt (GPL-3.0): bounds-first sampling, followed by EXIF normalization.
internal const val PHOTO_EDGE = 2048
internal const val MAX_PHOTO_BYTES = 50L * 1024 * 1024
private const val DIRECT_IMAGE_BYTES = 8L * 1024 * 1024
private val photoTypes = setOf("image/jpeg", "image/png", "image/webp")
internal data class PhotoInfo(val mime: String, val width: Int, val height: Int, val bytes: Long)
internal class PhotoProblem(message: String) : IOException(message)
private fun invalidPhoto(): Nothing = throw PhotoProblem(uiText("The file is not a valid static JPEG, PNG or WebP image.", "文件不是有效的静态 JPEG、PNG 或 WebP 图片。"))
private fun animatedPhoto(): Nothing = throw PhotoProblem(uiText("Animated or multi-frame images are not supported. Select a still image.", "不支持动画或多帧图片，请选择静态图片。"))
private fun unavailable(): Nothing = throw PhotoProblem(uiText("Could not read or store this image. Check the selection and available storage.", "无法读取或存储这张图片，请检查选取权限和存储空间。"))

/** Copy only while the grant is live. Both staging files belong to the caller, never the provider. */
internal suspend fun prepareStaticPhoto(resolver: ContentResolver, uri: Uri, source: File, pending: File): PhotoInfo {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) invalidPhoto()
    val declared = resolver.getType(uri)?.lowercase()
    if (declared != null && declared !in photoTypes) invalidPhoto()
    resolver.openInputStream(uri)?.use { input ->
        source.outputStream().use { out ->
            val buf = ByteArray(32 * 1024)
            val job = currentCoroutineContext()
            var size = 0L
            while (true) {
                job.ensureActive()
                val count = input.read(buf)
                if (count < 0) break
                if (count == 0) unavailable()
                size += count
                if (size > MAX_PHOTO_BYTES) throw PhotoProblem(uiText("Select an image no larger than 50 MiB.", "请选择不超过 50 MiB 的图片。"))
                out.write(buf, 0, count)
            }
        }
    } ?: unavailable()
    return normalizeStaticPhoto(source, declared, pending)
}

internal fun normalizeStaticPhoto(source: File, declared: String?, pending: File): PhotoInfo {
    if (source.length() !in 1..MAX_PHOTO_BYTES) invalidPhoto()
    val (mime, orientation) = inspectPhoto(source, declared)
    val pixels = sampledBitmap(source, dimensions(source, mime), PHOTO_EDGE)
    try {
        val upright = orient(pixels, orientation)
        try {
            val outputType = if (mime == "image/png" && source.length() > DIRECT_IMAGE_BYTES) "image/webp" else mime
            @Suppress("DEPRECATION") // API 26–29 support lossy WebP with alpha.
            val format = when (outputType) {
                "image/png" -> Bitmap.CompressFormat.PNG
                "image/webp" -> Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }
            pending.outputStream().use { if (!upright.compress(format, 95, it)) unavailable() }
            return PhotoInfo(outputType, upright.width, upright.height, pending.length())
        } finally { if (upright !== pixels) upright.recycle() }
    } finally { pixels.recycle() }
}

internal fun PhotoInfo.matchesFile(file: File, verifyContents: Boolean): Boolean {
    if (width !in 1..PHOTO_EDGE || height !in 1..PHOTO_EDGE || mime !in photoTypes || !file.isFile || file.length() != bytes) return false
    return try {
        RandomAccessFile(file, "r").use { input ->
            if (input.photoType() != mime) return false
            if (verifyContents) {
                if (mime == "image/jpeg") checkJpegEnd(file, this) else input.inspectChunks(mime == "image/png", this)
            }
            true
        }
    } catch (_: IOException) { false }
}

internal fun decodePrivatePhoto(file: File, edge: Int, expected: PhotoInfo? = null): Bitmap {
    val bounds = dimensions(file, expected?.mime)
    if (bounds.outWidth > PHOTO_EDGE || bounds.outHeight > PHOTO_EDGE ||
        expected != null && (bounds.outWidth != expected.width || bounds.outHeight != expected.height)) invalidPhoto()
    return sampledBitmap(file, bounds, edge.coerceIn(1, PHOTO_EDGE))
}

private fun dimensions(file: File, mime: String?): BitmapFactory.Options {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outMimeType !in photoTypes || mime != null && bounds.outMimeType != mime || bounds.outWidth <= 0 || bounds.outHeight <= 0) invalidPhoto()
    if (maxOf(bounds.outWidth, bounds.outHeight) > 65_535 || bounds.outWidth.toLong() * bounds.outHeight > 256_000_000) {
        throw PhotoProblem(uiText("Image dimensions are too large to decode safely.", "图片尺寸过大，无法安全解码。"))
    }
    return bounds
}

private fun sampledBitmap(file: File, bounds: BitmapFactory.Options, edge: Int): Bitmap {
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while ((longest + sample - 1) / sample > edge) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
    }
    val bitmap = BitmapFactory.decodeFile(file.path, options) ?: invalidPhoto()
    if (maxOf(bitmap.width, bitmap.height) > edge) { bitmap.recycle(); invalidPhoto() }
    return bitmap
}

private fun orient(source: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
        0, 1 -> return source
        2 -> m.setScale(-1f, 1f)
        3 -> m.setRotate(180f)
        4 -> m.setScale(1f, -1f)
        5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
        6 -> m.setRotate(90f)
        7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
        8 -> m.setRotate(-90f)
        else -> invalidPhoto()
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
}

private fun RandomAccessFile.photoType(): String? = when (readInt()) {
    0x89504e47.toInt() -> if (readInt() == 0x0d0a1a0a) "image/png" else null
    0x52494646 -> { skipBytes(4); if (readInt() == 0x57454250) "image/webp" else null }
    else -> { seek(0); if (readUnsignedShort() == 0xffd8 && readUnsignedByte() == 0xff) "image/jpeg" else null }
}

private fun inspectPhoto(file: File, declared: String?): Pair<String, Int> = try {
    RandomAccessFile(file, "r").use { input ->
        val mime = input.photoType() ?: invalidPhoto()
        if (declared != null && mime != declared) invalidPhoto()
        val orientation = if (mime == "image/jpeg") {
            checkJpegEnd(file)
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        } else input.inspectChunks(mime == "image/png")
        mime to orientation
    }
} catch (_: java.io.EOFException) { invalidPhoto() }

// Walk actual JPEG marker boundaries (T.81 Annex B), not byte patterns in metadata/embedded thumbnails.
private fun checkJpegEnd(file: File, expected: PhotoInfo? = null) = file.source().buffer().use { input ->
    input.skip(2)
    var scanning = false
    var sawScan = false
    var sawFrame = false
    while (true) {
        if (scanning) {
            // Bound the search window: indexOf on an entire malformed file would buffer it all.
            var offset = input.indexOf((-1).toByte(), 0L, 8192L)
            while (offset < 0 && input.request(8192L)) { input.skip(8192L); offset = input.indexOf((-1).toByte(), 0L, 8192L) }
            if (offset < 0) invalidPhoto()
            input.skip(offset)
        }
        if (input.readByte().toInt() != -1) invalidPhoto()
        var marker = input.readByte().toInt() and 255
        while (marker == 255) marker = input.readByte().toInt() and 255
        when {
            scanning && (marker == 0 || marker in 0xd0..0xd7) -> continue
            marker == 0xd9 -> { if (!sawScan || !sawFrame || !input.exhausted()) invalidPhoto(); break }
            marker < 0xc0 || marker in 0xd0..0xd8 -> invalidPhoto()
            else -> {
                val length = input.readShort().toInt() and 65535
                if (length < 2) invalidPhoto()
                if (marker in 0xc0..0xc3 || marker in 0xc5..0xc7 || marker in 0xc9..0xcb || marker in 0xcd..0xcf) {
                    if (length < 8) invalidPhoto()
                    if (sawFrame) animatedPhoto()
                    sawFrame = true
                    input.skip(1)
                    val h = input.readShort().toInt() and 65535
                    val w = input.readShort().toInt() and 65535
                    if (expected != null && (expected.width != w || expected.height != h)) invalidPhoto()
                    input.skip(length - 7L)
                } else {
                    if (marker == 0xe1 && expected != null) invalidPhoto()
                    input.skip(length - 2L)
                }
                scanning = marker == 0xda
                if (scanning) sawScan = true
            }
        }
    }
}

// PNG (W3C PNG-3) / RIFF WebP chunk lengths are authoritative; a type name inside pixel data is not a chunk.
private fun RandomAccessFile.inspectChunks(png: Boolean, expected: PhotoInfo? = null): Int {
    val size = length()
    if (png) seek(8) else {
        seek(4); if (Integer.reverseBytes(readInt()).toLong() != size - 8) invalidPhoto(); seek(12)
    }
    val type = ByteArray(4)
    val crc = if (png && expected == null) CRC32() else null
    val scratch = if (crc != null) ByteArray(8192) else null
    var orientation = 1
    var sawPixels = false
    var sawEnd = false
    var chunks = 0
    while (filePointer < size) {
        if (++chunks > 65_536) invalidPhoto()
        val count: Int
        if (png) { count = readInt(); readFully(type) } else { readFully(type); count = Integer.reverseBytes(readInt()) }
        val start = filePointer
        val end = start + count
        val next = end + if (png) 4 else count and 1
        if (count < 0 || next > size) invalidPhoto()
        val name = String(type, Charsets.US_ASCII)
        if (png && chunks == 1 && (name != "IHDR" || count != 13)) invalidPhoto()
        when (name) {
            "IHDR" -> if (png && expected != null && (readInt() != expected.width || readInt() != expected.height)) invalidPhoto()
            "acTL", "fcTL", "fdAT", "ANIM", "ANMF" -> animatedPhoto()
            "VP8X" -> { if (count != 10) invalidPhoto(); if (readUnsignedByte() and 2 != 0) animatedPhoto() }
            "IDAT" -> if (png) sawPixels = true
            "VP8 ", "VP8L" -> if (!png) {
                if (sawPixels) animatedPhoto()
                sawPixels = true
                if (expected != null) {
                    if (name == "VP8L") {
                        if (count < 5 || readUnsignedByte() != 0x2f) invalidPhoto()
                        val packed = Integer.reverseBytes(readInt())
                        if ((packed and 0x3fff) + 1 != expected.width || (packed ushr 14 and 0x3fff) + 1 != expected.height) invalidPhoto()
                    } else {
                        if (count < 10) invalidPhoto()
                        seek(start + 3)
                        if (readUnsignedShort() != 0x9d01 || readUnsignedByte() != 0x2a) invalidPhoto()
                        fun dimension() = java.lang.Short.reverseBytes(readShort()).toInt() and 0x3fff
                        if (dimension() != expected.width || dimension() != expected.height) invalidPhoto()
                    }
                }
            }
            "eXIf", "EXIF" -> { if (expected != null) invalidPhoto(); orientation = tiffOrientation(start, end) }
            "IEND" -> if (png) { if (count != 0 || next != size) invalidPhoto(); sawEnd = true }
        }
        if (crc != null) {
            seek(start); crc.reset(); crc.update(type)
            var left = count
            while (left > 0) {
                val n = minOf(left, scratch!!.size); readFully(scratch, 0, n); crc.update(scratch, 0, n); left -= n
            }
            if (readInt() != crc.value.toInt()) invalidPhoto()
        } else seek(next)
    }
    if (!sawPixels || png && !sawEnd) invalidPhoto()
    return orientation
}

// API 26's platform ExifInterface lacks PNG/WebP containers. Read only TIFF's IFD0 Orientation SHORT,
// without loading an unbounded eXIf/EXIF chunk or trusting TIFF offsets outside that chunk.
private fun RandomAccessFile.tiffOrientation(start: Long, end: Long): Int {
    if (end - start < 8) invalidPhoto()
    seek(start)
    val header = if (readInt() == 0x45786966 && readUnsignedShort() == 0) start + 6 else start
    if (end - header < 8) invalidPhoto()
    seek(header)
    val little = when (readUnsignedShort()) { 0x4949 -> true; 0x4d4d -> false; else -> invalidPhoto() }
    fun short(): Int = readShort().let { if (little) java.lang.Short.reverseBytes(it) else it }.toInt() and 65535
    fun uint(): Long = readInt().let { if (little) Integer.reverseBytes(it) else it }.toLong() and 0xffffffffL
    if (short() != 42) invalidPhoto()
    val offset = uint()
    if (offset < 8 || header + offset + 2 > end) invalidPhoto()
    seek(header + offset)
    val count = short()
    if (filePointer + 12L * count > end) invalidPhoto()
    repeat(count) {
        val next = filePointer + 12
        if (short() == 0x112) {
            if (short() != 3 || uint() != 1L) invalidPhoto()
            return short().also { if (it !in 1..8) invalidPhoto() }
        }
        seek(next)
    }
    return 1
}
