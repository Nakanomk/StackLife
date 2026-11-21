package io.github.nakanomk.stacklife.about

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.nakanomk.stacklife.planner.CommitResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface CaptureState {
    data object Idle : CaptureState
    data object Recording : CaptureState
    data object Unavailable : CaptureState
    class Finished(val report: FrameReport, val paused: Boolean) : CaptureState
}

internal object AppDiagnostics {
    val journal = DiagnosticJournal()
    private val state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val capture = state.asStateFlow()
    private var foreground: Window? = null
    private var session: WindowCapture? = null

    @MainThread fun bind(activity: ComponentActivity) {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                if (foreground !== activity.window && session != null) finish(paused = true)
                foreground = activity.window
                journal.record(DiagnosticEvent.WINDOW_RESUMED)
            }
            override fun onPause(owner: LifecycleOwner) {
                if (foreground === activity.window) {
                    finish(paused = true)
                    foreground = null
                }
                journal.record(DiagnosticEvent.WINDOW_PAUSED)
            }
        })
    }

    fun recordCommit(result: CommitResult) = journal.record(result.diagnosticEvent())

    @MainThread fun start() {
        if (session != null) return
        val window = foreground
        if (window != null && window.decorView.isHardwareAccelerated) {
            val capture = WindowCapture(window)
            if (capture.attach()) {
                session = capture
                state.value = CaptureState.Recording
                journal.record(DiagnosticEvent.CAPTURE_STARTED)
                return
            }
        }
        state.value = CaptureState.Unavailable
        journal.record(DiagnosticEvent.CAPTURE_UNAVAILABLE)
    }

    @MainThread fun stop() = finish(paused = false)

    private fun finish(paused: Boolean) {
        val active = session ?: return
        session = null
        state.value = CaptureState.Finished(active.detach(), paused)
        journal.record(if (paused) DiagnosticEvent.CAPTURE_STOPPED_ON_PAUSE else DiagnosticEvent.CAPTURE_STOPPED)
    }
}

/** A thread and listener exist only during an explicit measurement; no screenshots or view inspection. */
private class WindowCapture(private val window: Window) : Window.OnFrameMetricsAvailableListener {
    private val worker = HandlerThread("StackLife-FrameMetrics")
    private val report = FrameReport()
    private var closed = false

    fun attach(): Boolean {
        worker.start()
        return try {
            window.addOnFrameMetricsAvailableListener(this, Handler(worker.looper))
            true
        } catch (_: RuntimeException) {
            worker.quitSafely()
            false
        }
    }

    @Synchronized override fun onFrameMetricsAvailable(window: Window, metrics: FrameMetrics, dropCountSinceLastInvocation: Int) {
        if (!closed) report.accept(
            metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP), metrics.getMetric(FrameMetrics.TOTAL_DURATION),
            if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L,
            dropCountSinceLastInvocation,
        )
    }

    fun detach(): FrameReport {
        // Callbacks already enqueued may run after removal. Freeze under their lock before publishing.
        synchronized(this) { closed = true }
        try { window.removeOnFrameMetricsAvailableListener(this) } finally { worker.quitSafely() }
        return report
    }
}
