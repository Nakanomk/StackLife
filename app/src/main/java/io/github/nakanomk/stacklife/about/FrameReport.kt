package io.github.nakanomk.stacklife.about

/** Running aggregates cover the entire capture; the CSV tail is bounded, in callback order. */
internal class FrameReport {
    companion object { const val CAPACITY = 1024 }

    private val vsync = LongArray(CAPACITY)
    private val duration = LongArray(CAPACITY)
    private val deadline = LongArray(CAPACITY)
    private var next = 0
    var frames = 0L
        private set
    var lostReports = 0L
        private set
    var withDeadline = 0L
        private set
    var overDeadline = 0L
        private set
    var worstNs = 0L
        private set
    var totalNs = 0L
        private set
    val retained get() = minOf(frames, CAPACITY.toLong()).toInt()

    fun accept(intendedVsyncNs: Long, totalDurationNs: Long, deadlineNs: Long, droppedReports: Int) {
        lostReports += droppedReports
        if (totalDurationNs < 0) return
        vsync[next] = intendedVsyncNs
        duration[next] = totalDurationNs
        deadline[next] = deadlineNs
        next = (next + 1) % CAPACITY
        frames++
        totalNs += totalDurationNs
        worstNs = maxOf(worstNs, totalDurationNs)
        if (deadlineNs > 0) {
            withDeadline++
            if (totalDurationNs > deadlineNs) overDeadline++
        }
    }

    /** Called only after the listener is detached. -1 means the platform did not supply the metric. */
    fun writeTo(out: Appendable) = with(out) {
        appendLine("frames=$frames")
        appendLine("mean_total_ns=${if (frames > 0) (totalNs / frames).toString() else "unavailable"}")
        appendLine("max_total_ns=${if (frames > 0) worstNs.toString() else "unavailable"}")
        appendLine("frames_with_deadline=$withDeadline")
        appendLine("frames_over_deadline=${if (withDeadline > 0) overDeadline.toString() else "unavailable"}")
        appendLine("dropped_metric_reports=$lostReports (not dropped display frames)")
        appendLine("retained_frames=$retained (latest $CAPACITY; timestamps use the monotonic clock; unavailable=-1)")
        appendLine("intended_vsync_ns,total_duration_ns,deadline_ns")
        var i = (next + CAPACITY - retained) % CAPACITY
        repeat(retained) {
            appendLine("${vsync[i]},${duration[i]},${deadline[i]}")
            i = (i + 1) % CAPACITY
        }
    }
}
