package io.github.nakanomk.stacklife.appearance

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.archive.*
import io.github.nakanomk.stacklife.planner.PlannerApi
import io.github.nakanomk.stacklife.planner.appearanceLabel
import io.github.nakanomk.stacklife.workspace.*
import java.io.IOException
import kotlinx.coroutines.*

private data class PendingReview(val data: AppearanceReview, val decision: CompletableDeferred<Boolean>)

// BiliPai SearchHistoryTransfer: two SAF contracts, parse before showing replacement confirmation.
// Launchers, jobs and review live with the screen, not with a recycled LazyColumn row.
@Composable
internal fun rememberAppearanceTransfer(planner: PlannerApi, store: BackgroundStore, available: Boolean,
    onApplied: () -> Unit): @Composable () -> Unit {
    val context = LocalContext.current
    val archive = remember(planner, store, context.cacheDir) { AppearanceArchive(planner, store, context.cacheDir) }
    val scope = rememberCoroutineScope(); val canStart by rememberUpdatedState(available); val applied by rememberUpdatedState(onApplied)
    var review by remember { mutableStateOf<PendingReview?>(null) }
    var message by remember { mutableStateOf("") }
    var running by remember { mutableStateOf<Job?>(null) }
    var committing by remember { mutableStateOf(false) }
    fun run(action: suspend () -> Unit) {
        if (running != null) return
        message = ""
        running = scope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: ArchivePlannerFailure) { message = error.message.orEmpty() }
            catch (_: Exception) { message = context.getString(R.string.transfer_invalid) }
            finally { review = null; committing = false; running = null }
        }
        running?.start()
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) run {
            try {
                check(canStart)
                archive.export { context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException() }
                message = context.getString(R.string.transfer_exported)
            } catch (error: Exception) {
                val removed = removePartial(context, uri)
                if (error is CancellationException) {
                    if (!removed) message = context.getString(R.string.transfer_partial)
                    throw error
                }
                message = context.getString(if (removed) R.string.transfer_export_failed else R.string.transfer_partial)
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            if (!canStart) { message = context.getString(R.string.transfer_finish_draft); return@run }
            val result = archive.restore({ context.contentResolver.openInputStream(uri) ?: throw IOException() }) { data ->
                committing = false
                val pending = PendingReview(data, CompletableDeferred()); review = pending
                try { pending.decision.await() } finally { review = null }
            }
            when (result) {
                AppearanceImport.APPLIED -> applied()
                AppearanceImport.UNCHANGED -> message = context.getString(R.string.transfer_unchanged)
                AppearanceImport.CANCELLED -> Unit
            }
        }
    }
    val pending = review
    if (pending != null) {
        fun decide(yes: Boolean) { if (pending.decision.complete(yes)) committing = yes }
        AlertDialog(onDismissRequest = { decide(false) }, title = { Text(stringResource(R.string.transfer_review)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (pending.data.refreshed) Text(stringResource(R.string.transfer_refreshed))
                    Text(stringResource(R.string.transfer_photo_count, pending.data.photos))
                    pending.data.differences.forEach { diff ->
                        Text(diff.label()); Hint("${appearanceLabel(diff.before, diff.key)} → ${appearanceLabel(diff.after, diff.key)}")
                    }
                }
            }, confirmButton = { TextButton(onClick = { decide(true) }) { Text(stringResource(R.string.transfer_replace)) } },
            dismissButton = { TextButton(onClick = { decide(false) }) { Text(stringResource(R.string.ui_cancel)) } })
    } else if (running != null) {
        val cancel = { if (!committing) running?.cancel(); Unit }
        AlertDialog(onDismissRequest = cancel, title = { Text(stringResource(R.string.transfer_title)) },
            text = { Text(stringResource(R.string.transfer_busy)) }, confirmButton = {},
            dismissButton = { if (!committing) TextButton(onClick = cancel) { Text(stringResource(R.string.ui_cancel)) } })
    }
    fun pick(action: () -> Unit) {
        try { action() }
        catch (_: android.content.ActivityNotFoundException) { message = context.getString(R.string.transfer_no_picker) }
        catch (_: SecurityException) { message = context.getString(R.string.transfer_no_picker) }
    }
    return {
        FormGroup(frosted = true) {
            SectionTitle(stringResource(R.string.transfer_title)); Hint(stringResource(R.string.transfer_scope))
            if (!available) Hint(stringResource(R.string.transfer_finish_draft))
            Secondary(stringResource(R.string.transfer_export), available && running == null) { pick { create.launch("stacklife-appearance.zip") } }
            Secondary(stringResource(R.string.transfer_import), available && running == null) {
                pick { open.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
            }
            if (message.isNotEmpty()) Text(message)
        }
    }
}

private suspend fun removePartial(context: Context, uri: Uri) = withContext(NonCancellable + Dispatchers.IO) {
    val resolver = context.contentResolver
    val removed = try { DocumentsContract.deleteDocument(resolver, uri) } catch (_: Exception) { false }
    removed || try { resolver.delete(uri, null, null) > 0 } catch (_: Exception) { false }
}
