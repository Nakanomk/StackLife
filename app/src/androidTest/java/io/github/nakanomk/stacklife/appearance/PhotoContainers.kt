package io.github.nakanomk.stacklife.appearance

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Gainmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

// Original synthetic fixtures, no camera data. The HEIC was generated from red/blue 32px squares
// using heif-enc --rotate-cw 90 (container irot); the silent 2-frame green AVC MP4 with ffmpeg.
internal fun photoFixture(name: String): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets
    .open(name).use { Base64.decode(it.readBytes(), Base64.DEFAULT) }

/** Real, transient, test-owned media provider item; the test never touches the user's gallery. */
@RequiresApi(29)
internal inline fun <T> withSelectedPhoto(context: Context, bytes: ByteArray, mime: String, block: (Uri) -> T): T {
    val resolver = context.contentResolver
    val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
        val suffix = if (mime == "image/jpeg") "jpg" else mime.substringAfter('/')
        put(MediaStore.MediaColumns.DISPLAY_NAME, "wallpaper-${UUID.randomUUID()}.$suffix")
        put(MediaStore.MediaColumns.MIME_TYPE, mime); put(MediaStore.MediaColumns.IS_PENDING, 1)
    }))
    try { requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }; return block(uri) }
    finally { resolver.delete(uri, null, null) }
}

internal fun redPixels(format: Bitmap.CompressFormat, alpha: Int = 255): ByteArray {
    val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(Color.argb(alpha, 255, 0, 0))
        return ByteArrayOutputStream().use { check(bitmap.compress(format, 95, it)); it.toByteArray() }
    } finally { bitmap.recycle() }
}

internal fun motionPhoto(jpeg: ByteArray): ByteArray {
    val video = photoFixture("green-avc.mp4.b64")
    val xmp = ("http://ns.adobe.com/xap/1.0/\u0000" + """
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
         xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
         Camera:MotionPhoto="1" Camera:MotionPhotoVersion="1" Camera:MotionPhotoPresentationTimestampUs="-1">
        <Container:Directory><rdf:Seq>
         <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/></rdf:li>
         <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${video.size}"/></rdf:li>
        </rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>
    """.trimIndent()).toByteArray(Charsets.UTF_8)
    return ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use {
            it.write(jpeg, 0, 2); it.writeShort(0xffe1); it.writeShort(xmp.size + 2); it.write(xmp)
            it.write(jpeg, 2, jpeg.size - 2); it.write(video)
        }
        bytes.toByteArray()
    }
}

@RequiresApi(34)
internal fun gainmapPhoto(): ByteArray {
    val base = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
    val gain = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
    try {
        base.eraseColor(Color.RED); gain.eraseColor(Color.LTGRAY)
        base.gainmap = Gainmap(gain).apply { setRatioMax(4f, 4f, 4f); setDisplayRatioForFullHdr(4f) }
        return ByteArrayOutputStream().use { check(base.compress(Bitmap.CompressFormat.JPEG, 95, it)); it.toByteArray() }
    } finally { base.recycle(); gain.recycle() }
}
