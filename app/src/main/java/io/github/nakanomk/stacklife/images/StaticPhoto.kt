package io.github.nakanomk.stacklife.images

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.nakanomk.stacklife.i18n.uiText
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import okio.source

// GPTMobile v0.9.1 FileUtils.kt (GPL-3.0): bounds-first sampling, followed by EXIF normalization.
internal const val PHOTO_EDGE = 2048
internal const val MAX_PHOTO_BYTES = 50L * 1024 * 1024
private const val DIRECT_IMAGE_BYTES = 8L * 1024 * 1024
private val photoTypes = setOf("image/jpeg", "image/png", "image/webp")
private val jfif = "JFIF\u0000".encodeUtf8()
private val iccProfile = "ICC_PROFILE\u0000".encodeUtf8()
private val adobe = "Adobe".encodeUtf8()
private val preparedPngChunks = setOf("IHDR", "PLTE", "tRNS", "IDAT", "IEND", "sRGB", "iCCP", "gAMA", "cHRM", "sBIT", "pHYs", "cICP")
private val preparedWebpChunks = setOf("VP8X", "VP8 ", "VP8L", "ALPH", "ICCP")
internal enum class PhotoDestination { BACKGROUND, CHAT }
internal data class PhotoInfo(val mime: String, val width: Int, val height: Int, val bytes: Long)
internal class PhotoProblem(message: String) : IOException(message)
private fun invalidPhoto(): Nothing = throw PhotoProblem(uiText("This image is damaged or has an unsupported structure.", "图片已损坏或其结构不受支持。"))
private fun animatedPhoto(): Nothing = throw PhotoProblem(uiText("Animated or multi-frame images are not supported. Select a still image.", "不支持动画或多帧图片，请选择静态图片。"))
private fun unavailable(): Nothing = throw PhotoProblem(uiText("Could not read or store this image. Check the selection and available storage.", "无法读取或存储这张图片，请检查选取权限和存储空间。"))
private fun cannotDecode(): Nothing = throw PhotoProblem(uiText("This system cannot decode this image.", "当前系统无法解码这张图片。"))

private fun checkDeclaration(mime: String?) {
    if (mime != null && mime != "application/octet-stream" &&
        (!mime.startsWith("image/") || mime == "image/svg+xml")) cannotDecode()
}

// BiliPai WallpaperImageImport.kt, 5893c2c80739e3759ac64ae5f232cf03a94da7f8 (GPL-3.0):
// copy during the grant. StackLife bounds the copy and publishes only private, re-encoded pixels.
internal suspend fun prepareStaticPhoto(resolver: ContentResolver, uri: Uri, source: File, pending: File,
    destination: PhotoDestination): PhotoInfo {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) unavailable()
    val declared = resolver.getType(uri)?.lowercase()
    checkDeclaration(declared)
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
    return normalizeChecked(source, pending, destination)
}

/** [source] is a private, disposable input copy, not a caller-owned original. */
internal fun normalizeStaticPhoto(source: File, declared: String?, pending: File, destination: PhotoDestination): PhotoInfo {
    checkDeclaration(declared)
    return normalizeChecked(source, pending, destination)
}

private fun normalizeChecked(source: File, pending: File, destination: PhotoDestination): PhotoInfo {
    val size = source.length()
    if (size !in 1..MAX_PHOTO_BYTES) cannotDecode()
    // Only the disposable .input copy is touched, never the granted URI. Some platform
    // decoders honor PNG/WebP EXIF and some ignore it. Consume it here and apply it once.
    val modern = Build.VERSION.SDK_INT >= 28
    var orientation = 1
    val rasterFormat = if (destination == PhotoDestination.CHAT && size >= 12) {
        RandomAccessFile(source, if (modern) "rw" else "r").use { input ->
            val type = input.photoType()
            if (type == "image/png" || type == "image/webp") {
                orientation = input.inspectChunks(type == "image/png", consumeOrientation = modern)
                if (type == "image/png" && size <= DIRECT_IMAGE_BYTES) "image/png" else "image/webp"
            } else null
        }
    } else null
    val pixels = if (Build.VERSION.SDK_INT >= 28) {
        decodePrimary(source, destination, checkedRaster = rasterFormat != null)
    } else {
        val bounds = dimensions(source)
        val mime = bounds.outMimeType ?: cannotDecode()
        if (rasterFormat == null) {
            // BitmapFactory doesn't expose GIF frame count on these releases.
            if (destination == PhotoDestination.CHAT && mime == "image/gif") animatedPhoto()
            orientation = legacyOrientation(source, mime)
        }
        sampledBitmap(source, bounds, PHOTO_EDGE)
    }
    try {
        val upright = orient(pixels, orientation)
        try {
            return encodePrimary(upright, size, pending, rasterFormat)
        } finally { if (upright !== pixels) upright.recycle() }
    } finally { pixels.recycle() }
}

// Adapted from Coil StaticImageDecoder.kt, 14d99b18c4491fd6eccdc54454f3c079a90e3c10.
// Copyright 2026 Coil Contributors, Apache-2.0 (assets/licenses/Coil-Apache-2.0.txt).
// StackLife: bounded private file, no partial pixels, software/sRGB primary still only.
@RequiresApi(28)
private fun decodePrimary(file: File, destination: PhotoDestination, checkedRaster: Boolean): Bitmap = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        if (!info.mimeType.startsWith("image/") || info.mimeType == "image/svg+xml") cannotDecode()
        if (destination == PhotoDestination.CHAT && info.isAnimated) animatedPhoto()
        val width = info.size.width
        val height = info.size.height
        checkDimensions(width, height)
        // Some JPEG codecs synthesize an EOI at EOF. Require the complete primary codestream.
        if (info.mimeType == "image/jpeg") checkJpegEnd(file, allowContainerTail = true)
        if (!checkedRaster && !info.isAnimated && (info.mimeType == "image/png" || info.mimeType == "image/webp")) {
            RandomAccessFile(file, "r").use { it.inspectChunks(info.mimeType == "image/png") }
        }
        val edge = maxOf(width, height)
        if (edge > PHOTO_EDGE) {
            val scale = PHOTO_EDGE.toDouble() / edge
            decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.setOnPartialImageListener { false }
    }
} catch (_: ImageDecoder.DecodeException) { cannotDecode() }
  catch (_: java.io.EOFException) { cannotDecode() }

private fun encodePrimary(bitmap: Bitmap, sourceBytes: Long, pending: File, rasterFormat: String? = null): PhotoInfo {
    if (Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()) bitmap.gainmap = null
    val mime = rasterFormat ?: when {
        !bitmap.hasAlpha() -> "image/jpeg"
        sourceBytes > DIRECT_IMAGE_BYTES -> "image/webp"
        else -> "image/png"
    }
    val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
    if (bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.colorSpace == srgb) return encode(bitmap, mime, pending)
    val sdr = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888, bitmap.hasAlpha(), srgb)
    try {
        Canvas(sdr).drawBitmap(bitmap, null, Rect(0, 0, sdr.width, sdr.height), null)
        return encode(sdr, mime, pending)
    } finally { sdr.recycle() }
}

private fun encode(bitmap: Bitmap, mime: String, pending: File): PhotoInfo {
    @Suppress("DEPRECATION") // API 26–29 support lossy WebP with alpha.
    val format = when (mime) {
        "image/png" -> Bitmap.CompressFormat.PNG
        "image/webp" -> Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.JPEG
    }
    pending.outputStream().use { if (!bitmap.compress(format, 95, it)) unavailable() }
    return PhotoInfo(mime, bitmap.width, bitmap.height, pending.length())
}

internal fun PhotoInfo.matchesFile(file: File): Boolean {
    if (width !in 1..PHOTO_EDGE || height !in 1..PHOTO_EDGE || mime !in photoTypes || !file.isFile || file.length() != bytes) return false
    return try {
        RandomAccessFile(file, "r").use { input ->
            if (input.photoType() != mime) return false
            if (mime == "image/jpeg") checkJpegEnd(file, this) else input.inspectChunks(mime == "image/png", this)
            true
        }
    } catch (_: IOException) { false }
}

internal fun decodePrivatePhoto(file: File, edge: Int, expected: PhotoInfo? = null): Bitmap {
    val bounds = dimensions(file)
    if (bounds.outMimeType !in photoTypes || expected != null && bounds.outMimeType != expected.mime) invalidPhoto()
    if (bounds.outWidth > PHOTO_EDGE || bounds.outHeight > PHOTO_EDGE ||
        expected != null && (bounds.outWidth != expected.width || bounds.outHeight != expected.height)) invalidPhoto()
    return sampledBitmap(file, bounds, edge.coerceIn(1, PHOTO_EDGE))
}

private fun dimensions(file: File): BitmapFactory.Options {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outMimeType?.startsWith("image/") != true || bounds.outMimeType == "image/svg+xml") cannotDecode()
    checkDimensions(bounds.outWidth, bounds.outHeight)
    return bounds
}

private fun checkDimensions(width: Int, height: Int) {
    if (width <= 0 || height <= 0) cannotDecode()
    if (maxOf(width, height) > 65_535 || width.toLong() * height > 256_000_000) {
        throw PhotoProblem(uiText("Image dimensions are too large to decode safely.", "图片尺寸过大，无法安全解码。"))
    }
}

private fun sampledBitmap(file: File, bounds: BitmapFactory.Options, edge: Int): Bitmap {
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while ((longest + sample - 1) / sample > edge) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    }
    val bitmap = BitmapFactory.decodeFile(file.path, options) ?: cannotDecode()
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

private fun legacyOrientation(file: File, mime: String): Int = try {
    if (mime !in photoTypes) {
        // Vendor decoders on API 26/27 can support additional raster formats. Only reach here
        // after BitmapFactory has positively identified image pixels and bounded dimensions.
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
    } else RandomAccessFile(file, "r").use { input ->
        if (input.photoType() != mime) invalidPhoto()
        if (mime == "image/jpeg") {
            checkJpegEnd(file, allowContainerTail = true)
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        } else input.inspectChunks(mime == "image/png")
    }
} catch (_: java.io.EOFException) { invalidPhoto() }

// Walk actual JPEG marker boundaries (T.81 Annex B), not byte patterns in metadata/embedded thumbnails.
private fun checkJpegEnd(file: File, expected: PhotoInfo? = null, allowContainerTail: Boolean = false) = file.source().buffer().use { input ->
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
            marker == 0xd9 -> { if (!sawScan || !sawFrame || !allowContainerTail && !input.exhausted()) invalidPhoto(); break }
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
                    // Only ordinary encoder headers/color profiles belong in a published JPEG.
                    // Do not forward EXIF/XMP, MPF, gainmap metadata or free-form comments.
                    if (expected != null) when (marker) {
                        0xe0 -> if (length != 16 || !input.rangeEquals(0, jfif)) invalidPhoto()
                        0xe2 -> if (length < 16 || !input.rangeEquals(0, iccProfile)) invalidPhoto()
                        0xee -> if (length != 14 || !input.rangeEquals(0, adobe)) invalidPhoto()
                        in 0xe1..0xef, 0xfe -> invalidPhoto()
                    }
                    input.skip(length - 2L)
                }
                scanning = marker == 0xda
                if (scanning) sawScan = true
            }
        }
    }
}

// PNG (W3C PNG-3) / RIFF WebP chunk lengths are authoritative; a type name inside pixel data is not a chunk.
private fun RandomAccessFile.inspectChunks(png: Boolean, expected: PhotoInfo? = null, consumeOrientation: Boolean = false): Int {
    val size = length()
    if (png) seek(8) else {
        seek(4); if (Integer.reverseBytes(readInt()).toLong() != size - 8) invalidPhoto(); seek(12)
    }
    val type = ByteArray(4)
    val crc = if (png) CRC32() else null
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
        if (expected != null && name !in (if (png) preparedPngChunks else preparedWebpChunks)) invalidPhoto()
        if (png && chunks == 1 && (name != "IHDR" || count != 13)) invalidPhoto()
        if (crc != null) {
            val checksum = chunkCrc(crc, type, start, count, scratch!!)
            if (readInt() != checksum) invalidPhoto()
            seek(start)
        }
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
            "eXIf", "EXIF" -> {
                if (expected != null) invalidPhoto()
                orientation = tiffOrientation(start, end, consumeOrientation)
                if (consumeOrientation && orientation != 1 && crc != null) {
                    writeInt(chunkCrc(crc, type, start, count, scratch!!))
                }
            }
            "IEND" -> if (png) { if (count != 0 || next != size) invalidPhoto(); sawEnd = true }
        }
        seek(next)
    }
    if (!sawPixels || png && !sawEnd) invalidPhoto()
    return orientation
}

// Leaves the cursor immediately before the stored CRC.
private fun RandomAccessFile.chunkCrc(crc: CRC32, type: ByteArray, start: Long, count: Int, scratch: ByteArray): Int {
    seek(start); crc.reset(); crc.update(type)
    var remaining = count
    while (remaining > 0) {
        val n = minOf(remaining, scratch.size)
        readFully(scratch, 0, n); crc.update(scratch, 0, n); remaining -= n
    }
    return crc.value.toInt()
}

// API 26's platform ExifInterface lacks PNG/WebP containers. Read only TIFF's IFD0 Orientation SHORT,
// without loading an unbounded eXIf/EXIF chunk or trusting TIFF offsets outside that chunk.
private fun RandomAccessFile.tiffOrientation(start: Long, end: Long, consume: Boolean): Int {
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
            val slot = filePointer
            val orientation = short()
            if (orientation !in 1..8) invalidPhoto()
            if (consume && orientation != 1) {
                seek(slot); writeShort(if (little) 0x0100 else 1)
            }
            return orientation
        }
        seek(next)
    }
    return 1
}
