package io.github.nakanomk.stacklife.workspace

import io.github.nakanomk.stacklife.planner.PlannerCommand

/** The saved request id reconnects to the activity ViewModel's write; it cannot replay a command. */
interface WorkspaceActions {
    fun start(command: PlannerCommand): String?
    suspend fun result(requestId: String, onError: ((String) -> Unit)? = null): SaveOutcome
}

enum class SaveOutcome { APPLIED, REJECTED, INTERRUPTED }
