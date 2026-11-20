package io.github.nakanomk.stacklife.workspace

import io.github.nakanomk.stacklife.planner.PlannerCommand

/** Only the request id enters saved UI state. The activity ViewModel owns the write and receipt. */
interface WorkspaceActions {
    fun start(command: PlannerCommand): String?
    suspend fun result(requestId: String): SaveOutcome
}

enum class SaveOutcome { APPLIED, REJECTED, INTERRUPTED }
