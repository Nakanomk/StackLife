package io.github.nakanomk.stacklife.planner

data class QueueView(val tasks: List<QueueTask>) : QueryResult
data class TaskView(val task: QueueTask?) : QueryResult
data class FrameSummary(val id: String, val reason: String, val savedAtMillis: Long, val source: FrameSource,
    val originalCount: Int, val remaining: Int)
data class FrameIndex(val frames: List<FrameSummary>) : QueryResult
data class FrameDetailResult(val frame: FrameView?) : QueryResult
data class PolicyView(val policy: RestorePolicy, val appearance: Appearance = Appearance()) : QueryResult
