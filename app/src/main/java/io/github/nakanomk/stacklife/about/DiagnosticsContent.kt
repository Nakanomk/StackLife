package io.github.nakanomk.stacklife.about

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    AboutList(padding) {
        item {
            AboutCard {
                AboutHeading(stringResource(R.string.about_frames), stringResource(R.string.about_frames_intro))
                val stateLabel = when (capture) {
                    CaptureState.Idle -> R.string.about_no_capture
                    CaptureState.Unavailable -> R.string.about_capture_unavailable
                    CaptureState.Recording -> R.string.about_recording
                    is CaptureState.Finished -> if (capture.paused) R.string.about_finished_pause else R.string.about_finished
                }
                val recording = capture == CaptureState.Recording
                Text(stringResource(stateLabel),
                    (if (recording) Modifier.testTag("about-recording") else Modifier).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyLarge, color = when (capture) {
                        CaptureState.Unavailable -> MaterialTheme.colorScheme.error
                        CaptureState.Recording -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
                Button(onClick = { if (recording) AppDiagnostics.stop() else AppDiagnostics.start() },
                    modifier = Modifier.testTag(if (recording) "about-stop-frames" else "about-start-frames"), enabled = !busy) {
                    Text(stringResource(if (recording) R.string.about_stop else R.string.about_start))
                }
                val finished = capture as? CaptureState.Finished
                if (finished != null) {
                    HorizontalDivider()
                    AboutLabel(stringResource(R.string.about_measurement_results))
                    val stats = finished.report
                    if (stats.frames == 0L) Text(stringResource(R.string.about_no_frames))
                    else {
                        Text(stringResource(R.string.about_frame_stats, stats.frames, stats.totalNs / stats.frames / 1e6, stats.worstNs / 1e6),
                            Modifier.testTag("about-frame-summary"), style = MaterialTheme.typography.titleMedium)
                        Hint(if (stats.withDeadline > 0) stringResource(R.string.about_deadline_stats, stats.overDeadline, stats.withDeadline)
                            else stringResource(R.string.about_no_deadline))
                    }
                    Hint(stringResource(R.string.about_reports_lost, stats.lostReports))
                }
                ExportAction(R.string.about_export_frames, "about-export-frames", !busy && finished != null && finished.report.frames > 0) {
                    if (finished != null) {
                        lastExportFrames = true
                        export.pick(DiagnosticDocument.Frames(finished)) { picker.launch(it) }
                    }
                }
                if (lastExportFrames) ExportFeedback(status)
            }
        }
        item {
            AboutCard {
                AboutHeading(stringResource(R.string.about_logs), stringResource(R.string.about_logs_intro, DiagnosticJournal.CAPACITY))
                Text(if (eventCount > 0) stringResource(R.string.about_log_count, minOf(eventCount, DiagnosticJournal.CAPACITY.toLong()), eventCount)
                    else stringResource(R.string.about_no_events), style = MaterialTheme.typography.bodyLarge)
                ExportAction(R.string.about_export_logs, "about-export-logs", !busy && eventCount > 0) {
                    lastExportFrames = false
                    export.pick(DiagnosticDocument.Events(AppDiagnostics.journal)) { picker.launch(it) }
                }
                if (!lastExportFrames) ExportFeedback(status)
            }
        }
    }
}

@Composable
private fun ExportAction(labelId: Int, tag: String, enabled: Boolean, action: () -> Unit) {
    val label = stringResource(labelId)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.weight(1f, fill = false).testTag(tag)) {
            Text(label)
        }
        SettingInfo(stringResource(R.string.about_export_hint), title = label)
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
    val failed = status == ExportStatus.EXPIRED || status == ExportStatus.NO_PICKER || status == ExportStatus.FAILED
    Text(stringResource(message), Modifier.testTag("about-export-status").semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
