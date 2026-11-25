package io.github.nakanomk.stacklife.assistant

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.ReadState
import kotlinx.coroutines.CancellationException

@Composable
internal fun AttachmentMenu(connections: ConnectionStore, draft: ChatDraft, active: Boolean, prefix: String, modifier: Modifier) {
    val epoch = draft.pickerEpoch
    key(draft, epoch) {
        var open by remember(active) { mutableStateOf(false) }
        val photos = rememberLauncherForActivityResult(remember { PickMultipleVisualMedia(MAX_CHAT_ATTACHMENTS) }) {
            draft.receivePicker(epoch, it, active, document = false)
        }
        val document = rememberLauncherForActivityResult(remember { OpenDocument() }) {
            draft.receivePicker(epoch, listOfNotNull(it), active, document = true)
        }
        Box(modifier) {
            IconButton(onClick = { open = true }, enabled = active, modifier = Modifier.fillMaxSize().testTag("$prefix-attach")) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.chat_add_attachment), Modifier.size(24.dp))
            }
            if (open) {
                val recipient by produceState<ReadState<ServiceProfile?>>(ReadState.Loading, connections) {
                    value = try { ReadState.Loaded(connections.profile()) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { ReadState.ReadFailed }
                }
                val profile = (recipient as? ReadState.Loaded)?.value
                DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Column {
                        Text(stringResource(R.string.chat_pick_images))
                        Text(stringResource(R.string.chat_image_limits), style = MaterialTheme.typography.labelSmall)
                    } }, modifier = Modifier.testTag("$prefix-pick-photos"), onClick = {
                        open = false
                        draft.launchPicker { photos.launch(PickVisualMediaRequest.Builder().setMediaType(PickVisualMedia.ImageOnly).setOrderedSelection(true).build()) }
                    })
                    DropdownMenuItem(text = { Column {
                        Text(stringResource(R.string.chat_pick_document))
                        Text(stringResource(when (recipient) {
                            ReadState.Loading -> R.string.chat_reading_capabilities
                            ReadState.ReadFailed -> R.string.chat_recipient_unavailable
                            is ReadState.Loaded -> if (profile.acceptsPdf) R.string.chat_document_pdf_limits else R.string.chat_document_text_limits
                        }), style = MaterialTheme.typography.labelSmall)
                    } }, enabled = recipient is ReadState.Loaded, modifier = Modifier.testTag("$prefix-pick-document"), onClick = {
                        open = false
                        draft.launchPicker(profile) { document.launch(documentMimeTypes(profile.acceptsPdf)) }
                    })
                }
            }
        }
    }
}
