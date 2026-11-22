package io.github.nakanomk.stacklife.assistant

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityOptionsCompat
import java.util.UUID

/** Only substitutes the system picker result, including the old provider that ignores its limit. */
internal fun photoPickerReturning(pick: () -> List<Uri>) = object : ActivityResultRegistryOwner {
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(contract is ActivityResultContracts.PickMultipleVisualMedia && (input as PickVisualMediaRequest).mediaType == ActivityResultContracts.PickVisualMedia.ImageOnly)
            val uris = pick()
            val result = Intent()
            if (uris.isNotEmpty()) result.clipData = ClipData.newRawUri("test", uris.first()).also { clip ->
                for (uri in uris.drop(1)) clip.addItem(ClipData.Item(uri))
            }
            dispatchResult(requestCode, if (uris.isEmpty()) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
        }
    }
}

/** A real provider URI owned by the isolated test app, never a grant or photo from the user's library. */
@RequiresApi(29)
internal fun withChatPhoto(context: Context, width: Int, block: (Uri) -> Unit) {
    val resolver = context.contentResolver
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "stacklife-fixture-${UUID.randomUUID()}.png")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }))
    try {
        val image = Bitmap.createBitmap(width, width * 2 / 3, Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(image)
            canvas.drawColor(0xfff1f2f5.toInt())
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            if (width == 180) {
                paint.color = 0xff386ab2.toInt()
                canvas.drawPath(android.graphics.Path().apply {
                    moveTo(90f, 12f); lineTo(147f, 104f); lineTo(33f, 104f); close()
                }, paint)
            } else {
                paint.color = 0xff208758.toInt()
                canvas.drawCircle(width / 2f, image.height / 2f, image.height * 0.38f, paint)
            }
            requireNotNull(resolver.openOutputStream(uri)).use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
        block(uri)
    } finally { resolver.delete(uri, null, null) }
}
