package io.github.nakanomk.stacklife.assistant

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

@Composable
internal fun DraftAttachmentStrip(store: AttachmentStore, draft: ChatDraft) {
    draft.issue?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (draft.items.isEmpty()) return
    val document = draft.items.firstNotNullOfOrNull { it.source as? AttachmentSource.Document }
    if (document != null) Text(stringResource(if (document.recipient.acceptsPdf) R.string.chat_document_pdf_limits else R.string.chat_document_text_limits),
        style = MaterialTheme.typography.labelSmall)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        draft.items.forEachIndexed { index, item -> key(item.id) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (val status = item.status) {
                        AttachmentPreparation.Preparing -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                        }
                        is AttachmentPreparation.Ready -> when (val part = status.attachment) {
                            is ChatImage -> ChatImageThumbnail(store, part, index + 1, 48, null)
                            is ChatDocument -> DocumentLabel(part)
                        }
                        is AttachmentPreparation.Failed -> {
                            var details by rememberSaveable { mutableStateOf(false) }
                            val label = stringResource(if (item.source is AttachmentSource.Document) R.string.chat_failed_document else R.string.chat_failed_image, index + 1)
                            TextButton(onClick = { details = true }) { Text(label) }
                            if (details) PlannerAlertDialog({ details = false }, title = { Text(label) },
                                text = { Text(status.reason) }, confirmButton = { TextButton(onClick = { details = false; draft.retry(item) }) {
                                    Text(stringResource(R.string.chat_retry))
                                } }, dismissButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.assistant_close)) } })
                        }
                    }
                    IconButton(onClick = { draft.remove(item) }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(if (item.source is AttachmentSource.Document) R.string.chat_remove_document else R.string.chat_remove_image, index + 1))
                    }
                }
            }
        } }
    }
}

@Composable
internal fun DocumentLabel(part: ChatDocument) {
    Column(Modifier.widthIn(max = 240.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(part.displayName, style = MaterialTheme.typography.labelLarge)
        Text("${part.mime} · ${part.bytes} B", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun ChatImageThumbnail(store: AttachmentStore, image: ChatImage, ordinal: Int, sizeDp: Int, reattach: ((ChatImage) -> Unit)?) {
    var open by rememberSaveable(image.id) { mutableStateOf(false) }
    val label = stringResource(R.string.chat_image_label, ordinal, image.width, image.height)
    LocalImage(store, image, 240, label, Modifier.size(sizeDp.dp).clip(MaterialTheme.shapes.small)
        .testTag("chat-image-${image.id}").clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_view_image)) { open = true }, crop = true)
    if (!open) return
    var scale by rememberSaveable(image.id) { mutableFloatStateOf(1f) }
    var offset by remember(image.id) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    fun transform(factor: Float, pan: Offset) {
        scale = (scale * factor).coerceIn(1f, 6f)
        val dx = viewport.width * (scale - 1f) / 2f
        val dy = viewport.height * (scale - 1f) / 2f
        offset = Offset((offset.x + pan.x).coerceIn(-dx, dx), (offset.y + pan.y).coerceIn(-dy, dy))
    }
    PlannerAlertDialog({ open = false }, title = { Text(label) }, text = {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().onSizeChanged { viewport = it }
                .transformable(rememberTransformableState { zoom, pan, _ -> transform(zoom, pan) })) {
                LocalImage(store, image, 2048, label, Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { transform(0.5f, Offset.Zero) }, enabled = scale > 1f) { Text(stringResource(R.string.chat_zoom_out)) }
                Text("${(scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { transform(2f, Offset.Zero) }, enabled = scale < 6f) { Text(stringResource(R.string.chat_zoom_in)) }
            }
        }
    }, dismissButton = { if (reattach != null) TextButton(onClick = { reattach(image); open = false }, modifier = Modifier.testTag("chat-reattach")) {
        Text(stringResource(R.string.chat_reattach))
    } }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.assistant_close)) } })
}

@Composable
private fun LocalImage(store: AttachmentStore, image: ChatImage, edge: Int, label: String, modifier: Modifier, crop: Boolean = false) {
    var failed by remember(store, image.id, edge) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, store, image.id, edge) {
        value = try { store.bitmap(image, edge) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true; null }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) Image(remember(current) { current.asImageBitmap() }, label, Modifier.fillMaxSize(),
            contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
        else if (failed) Text(stringResource(R.string.chat_image_unavailable), style = MaterialTheme.typography.labelSmall)
        else CircularProgressIndicator(Modifier.size(18.dp))
    }
}
