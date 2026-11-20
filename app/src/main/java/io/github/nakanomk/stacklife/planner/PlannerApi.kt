package io.github.nakanomk.stacklife.planner

import kotlinx.coroutines.flow.Flow

interface PlannerApi {
    fun observe(query: PlannerQuery): Flow<QueryResult>
    suspend fun preview(commands: List<PlannerCommand>): PreviewResult
    suspend fun commit(proposalId: String): CommitResult
    fun discard(proposalId: String)
}

sealed interface PlannerQuery {
    data object Workspace : PlannerQuery
}

sealed interface QueryResult

/** Queue order is priority order; frames are newest first within restorable and then empty frames. */
data class Workspace(
    val revision: Long,
    val queue: List<QueueTask>,
    val frames: List<FrameView>,
    val restorePolicy: RestorePolicy,
) : QueryResult

data class QueueTask(val id: String, val title: String, val notes: String, val minutes: Int)

enum class FrameSource { PUSH, MANUAL, SWAP }
enum class RestorePolicy { PREPEND, APPEND, SWAP }
enum class RestoreMode { ALL, SELECTED }

/** Items are immutable snapshots in their original order. */
data class FrameView(
    val id: String,
    val reason: String,
    val savedAtMillis: Long,
    val source: FrameSource,
    val items: List<FrameEntry>,
) {
    val remaining: Int = items.count { it.restorable }
}

data class FrameEntry(
    val taskId: String,
    val originalPosition: Int,
    val title: String,
    val notes: String,
    val minutes: Int,
    val scheduledStartMillis: Long?,
    val scheduledEndMillis: Long?,
    val restorable: Boolean,
)

/** A creation ref is batch-local: declare `ref = "a"`, then address it as `@a`. */
data class TaskInput(val title: String, val notes: String = "", val minutes: Int = 30, val ref: String? = null)

sealed interface PlannerCommand {
    data class TaskAdd(val task: TaskInput) : PlannerCommand
    data class TaskEdit(val taskId: String, val task: TaskInput) : PlannerCommand
    data class TaskFinish(val taskId: String) : PlannerCommand
    data class TaskRemove(val taskId: String) : PlannerCommand
    data class QueueOrder(val taskIds: List<String>) : PlannerCommand
    data class FrameCreate(val reason: String, val tasks: List<TaskInput>, val ref: String? = null) : PlannerCommand
    data class FramePush(val reason: String, val taskIds: List<String>, val ref: String? = null) : PlannerCommand
    data class FrameRestore(val frameId: String, val mode: RestoreMode, val taskIds: List<String> = emptyList()) : PlannerCommand
    data class SetRestorePolicy(val policy: RestorePolicy) : PlannerCommand
}

data class PlannerChange(val entityId: String, val field: String, val before: String?, val after: String?)

sealed interface PreviewResult {
    data class Ready(val proposalId: String, val baseRevision: Long, val changes: List<PlannerChange>) : PreviewResult
    data class Rejected(val message: String) : PreviewResult
}

enum class CommitFailure { STALE_PREVIEW, UNKNOWN_PROPOSAL, STORAGE }

sealed interface CommitResult {
    data class Applied(val revision: Long) : CommitResult
    data class Rejected(val code: CommitFailure, val message: String) : CommitResult
}
