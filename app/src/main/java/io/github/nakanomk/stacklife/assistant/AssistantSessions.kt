package io.github.nakanomk.stacklife.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

/** Main-thread model registry. A second activity cannot append to a newly restored historical row
 * using an old request/proposal ID. Confirmed commits finish; network turns are canceled. */
internal class AssistantSessions(private val deviceEnabled: (() -> Boolean)? = null) {
    private val live = WeakHashMap<AssistantModel, Unit>()
    private val restore = Mutex()
    private var paused = false

    fun register(model: AssistantModel) { live[model] = Unit; if (paused) model.pauseForBackup(true) }

    suspend fun <T> withPaused(block: suspend () -> T): T = restore.withLock {
        try {
            withContext(Dispatchers.Main.immediate) {
                paused = true
                val models = live.keys.toList()
                models.forEach { it.pauseForBackup(true) }
                for (model in models) model.drainHistory()
            }
            block()
        } finally { withContext(NonCancellable + Dispatchers.Main.immediate) {
            paused = false
            val enabled = deviceEnabled?.invoke()
            live.keys.forEach { model -> if (enabled != null) model.setEnabled(enabled); model.pauseForBackup(false) }
        } }
    }
}
