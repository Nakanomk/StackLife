package io.github.nakanomk.stacklife.about

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.workspace.*

@Composable
internal fun DiagnosticsContent(padding: PaddingValues) {
    val app = LocalContext.current.applicationContext
    val export: DiagnosticExportModel = viewModel()
    val status by export.status.collectAsStateWithLifecycle()
    val busy = status == ExportStatus.PICKING || status == ExportStatus.WRITING
    var lastExportFrames by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { export.result(app, it) }
    val capture = AppDiagnostics.capture.collectAsStateWithLifecycle().value
    val eventCount by AppDiagnostics.journal.total.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = listSpacing) {
        item { FormGroup {
            SectionTitle(stringResource(R.string.about_frames))
            Hint(stringResource(R.string.about_frames_intro))
            val finished = capture as? CaptureState.Finished
            when (capture) {
                CaptureState.Idle -> Text(stringResource(R.string.about_no_capture))
                CaptureState.Unavailable -> Text(stringResource(R.string.about_capture_unavailable))
                CaptureState.Recording -> Text(stringResource(R.string.about_recording), Modifier.testTag("about-recording"))
                is CaptureState.Finished -> {
                    Text(stringResource(if (capture.paused) R.string.about_finished_pause else R.string.about_finished))
                    val stats = capture.report
                    if (stats.frames == 0L) Text(stringResource(R.string.about_no_frames))
                    else {
                        Text(stringResource(R.string.about_frame_stats, stats.frames, stats.totalNs / stats.frames / 1e6, stats.worstNs / 1e6),
                            Modifier.testTag("about-frame-summary"))
                        Text(if (stats.withDeadline > 0) stringResource(R.string.about_deadline_stats, stats.overDeadline, stats.withDeadline)
                            else stringResource(R.string.about_no_deadline))
                    }
                    Text(stringResource(R.string.about_reports_lost, stats.lostReports))
                }
            }
            val recording = capture == CaptureState.Recording
            ActionPill(stringResource(if (recording) R.string.about_stop else R.string.about_start),
                onClick = { if (recording) AppDiagnostics.stop() else AppDiagnostics.start() },
                modifier = Modifier.testTag(if (recording) "about-stop-frames" else "about-start-frames"), enabled = !busy, prominent = true)
            ActionPill(stringResource(R.string.about_export_frames), onClick = {
                if (finished != null) {
                    lastExportFrames = true
                    export.pick(DiagnosticDocument.Frames(finished)) { picker.launch(it) }
                }
            }, modifier = Modifier.testTag("about-export-frames"), enabled = !busy && finished != null && finished.report.frames > 0)
            if (lastExportFrames && status != ExportStatus.IDLE) ExportFeedback(status)
        } }
        item { FormGroup {
            SectionTitle(stringResource(R.string.about_logs))
            Hint(stringResource(R.string.about_logs_intro, DiagnosticJournal.CAPACITY))
            Text(if (eventCount > 0) stringResource(R.string.about_log_count, minOf(eventCount, DiagnosticJournal.CAPACITY.toLong()), eventCount)
                else stringResource(R.string.about_no_events))
            ActionPill(stringResource(R.string.about_export_logs), onClick = {
                lastExportFrames = false
                export.pick(DiagnosticDocument.Events(AppDiagnostics.journal)) { picker.launch(it) }
            }, modifier = Modifier.testTag("about-export-logs"), enabled = !busy && eventCount > 0)
            if (!lastExportFrames && status != ExportStatus.IDLE) ExportFeedback(status)
        } }
        item { Hint(stringResource(R.string.about_export_hint)) }
    }
}

@Composable
private fun ExportFeedback(status: ExportStatus) {
    val message = when (status) {
        ExportStatus.IDLE -> return
        ExportStatus.PICKING -> R.string.about_export_picking
        ExportStatus.WRITING -> R.string.about_export_writing
        ExportStatus.WRITTEN -> R.string.about_export_written
        ExportStatus.CANCELED -> R.string.about_export_cancelled
        ExportStatus.EXPIRED -> R.string.about_export_expired
        ExportStatus.NO_PICKER -> R.string.about_no_picker
        ExportStatus.FAILED -> R.string.about_export_failed
    }
    Text(stringResource(message), Modifier.testTag("about-export-status").semantics { liveRegion = LiveRegionMode.Polite })
}
