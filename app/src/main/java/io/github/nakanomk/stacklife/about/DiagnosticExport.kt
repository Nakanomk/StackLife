package io.github.nakanomk.stacklife.about

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal sealed class DiagnosticDocument(val filename: String) {
    abstract fun writeBody(out: Appendable)
    class Events(journal: DiagnosticJournal) : DiagnosticDocument("StackLife-events.txt") {
        private val snapshot = journal.snapshot()
        override fun writeBody(out: Appendable) { out.append(snapshot) }
    }
    class Frames(private val finished: CaptureState.Finished) : DiagnosticDocument("StackLife-frames.txt") {
        override fun writeBody(out: Appendable) {
            out.appendLine("source=android.view.FrameMetrics (foreground main window)")
            out.appendLine("stopped_by=${if (finished.paused) "activity_pause" else "user"}")
            finished.report.writeTo(out)
        }
    }
}

internal fun writeDiagnosticDocument(context: Context, uri: Uri, document: DiagnosticDocument) {
    require(uri.scheme == android.content.ContentResolver.SCHEME_CONTENT) { "Expected a document provider" }
    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Document provider unavailable")
    stream.bufferedWriter(Charsets.UTF_8).use { out ->
        val version = installedVersion(context)
        out.appendLine("StackLife ${document.filename}")
        out.appendLine("version_name=${version.name ?: "unavailable"}")
        out.appendLine("version_code=${version.code}")
        out.appendLine("android_api=${Build.VERSION.SDK_INT}")
        document.writeBody(out)
    }
}

internal enum class ExportStatus { IDLE, PICKING, WRITING, WRITTEN, CANCELED, EXPIRED, NO_PICKER, FAILED }

/** Keep the exact chosen snapshot through Activity recreation, never persist it or a document URI. */
internal class DiagnosticExportModel : ViewModel() {
    private var pending: DiagnosticDocument? = null
    private val state = MutableStateFlow(ExportStatus.IDLE)
    val status = state.asStateFlow()

    fun pick(document: DiagnosticDocument, launch: (String) -> Unit) {
        if (state.value == ExportStatus.WRITING || pending != null) return
        pending = document
        state.value = ExportStatus.PICKING
        try { launch(document.filename) } catch (_: RuntimeException) {
            pending = null
            state.value = ExportStatus.NO_PICKER
        }
    }

    fun result(context: Context, uri: Uri?) {
        val document = pending
        pending = null
        if (uri == null || document == null) {
            state.value = if (uri == null) ExportStatus.CANCELED else ExportStatus.EXPIRED
            return
        }
        state.value = ExportStatus.WRITING
        viewModelScope.launch {
            val success = try {
                withContext(Dispatchers.IO) { writeDiagnosticDocument(context, uri, document) }
                true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
            AppDiagnostics.journal.record(when (document) {
                is DiagnosticDocument.Events -> if (success) DiagnosticEvent.LOG_EXPORT_WRITTEN else DiagnosticEvent.LOG_EXPORT_FAILED
                is DiagnosticDocument.Frames -> if (success) DiagnosticEvent.FRAMES_EXPORT_WRITTEN else DiagnosticEvent.FRAMES_EXPORT_FAILED
            })
            state.value = if (success) ExportStatus.WRITTEN else ExportStatus.FAILED
        }
    }
}
