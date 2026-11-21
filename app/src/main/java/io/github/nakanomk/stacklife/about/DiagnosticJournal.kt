package io.github.nakanomk.stacklife.about

import io.github.nakanomk.stacklife.planner.CommitFailure
import io.github.nakanomk.stacklife.planner.CommitResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** No text/Throwable/Bundle parameter: user input cannot become an event or payload. */
internal enum class DiagnosticEvent {
    WINDOW_RESUMED, WINDOW_PAUSED,
    COMMIT_APPLIED, COMMIT_STALE_PREVIEW, COMMIT_UNKNOWN_PROPOSAL, COMMIT_STORAGE_FAILURE,
    CAPTURE_STARTED, CAPTURE_STOPPED, CAPTURE_STOPPED_ON_PAUSE, CAPTURE_UNAVAILABLE,
    LOG_EXPORT_WRITTEN, LOG_EXPORT_FAILED, FRAMES_EXPORT_WRITTEN, FRAMES_EXPORT_FAILED,
}

internal fun CommitResult.diagnosticEvent() = when (this) {
    is CommitResult.Applied -> DiagnosticEvent.COMMIT_APPLIED
    is CommitResult.Rejected -> when (code) {
        CommitFailure.STALE_PREVIEW -> DiagnosticEvent.COMMIT_STALE_PREVIEW
        CommitFailure.UNKNOWN_PROPOSAL -> DiagnosticEvent.COMMIT_UNKNOWN_PROPOSAL
        CommitFailure.STORAGE -> DiagnosticEvent.COMMIT_STORAGE_FAILURE
    }
}

internal class DiagnosticJournal {
    companion object { const val CAPACITY = 256 }
    private val origin = System.nanoTime()
    private val elapsed = LongArray(CAPACITY)
    private val events = arrayOfNulls<DiagnosticEvent>(CAPACITY)
    private val count = MutableStateFlow(0L)
    val total = count.asStateFlow()

    @Synchronized fun record(event: DiagnosticEvent) {
        val index = (count.value % CAPACITY).toInt()
        elapsed[index] = (System.nanoTime() - origin) / 1_000_000
        events[index] = event
        count.value++
    }

    @Synchronized fun snapshot(): String = buildString {
        val n = count.value
        appendLine("total_events=$n")
        appendLine("retained_events=${minOf(n, CAPACITY.toLong())}")
        appendLine("sequence,ms_since_journal_open,event")
        for (number in maxOf(0L, n - CAPACITY) until n) {
            val i = (number % CAPACITY).toInt()
            appendLine("${number + 1},${elapsed[i]},${events[i]!!.name}")
        }
    }
}
