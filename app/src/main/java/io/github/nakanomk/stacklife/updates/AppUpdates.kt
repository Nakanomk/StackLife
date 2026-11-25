package io.github.nakanomk.stacklife.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.about.installedVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private const val SESSION = "installSession"
private const val SESSION_RELEASE = "sessionRelease"
private const val OFFER = "release"
private const val CHANNEL = "channel"
private const val CHECKED_AT = "checkedAt"
private const val PROBLEM = "checkProblem"

internal data class CheckedRelease(val time: Long, val offer: ReleaseUpdate?, val problem: ReleaseProblem?, val cached: Boolean)
internal enum class Preparation { IDLE, DOWNLOADING, PERMISSION, SUBMITTING }
internal data class UpdateStatus(val last: CheckedRelease?, val checking: Boolean = false,
    val preparation: Preparation = Preparation.IDLE, val progress: Int = 0, val error: Int = 0)
internal enum class InstallStage { WAITING, CONFIRM, RECOVER, UPDATED, FAILED }
internal data class Installation(val release: ReleaseUpdate, val stage: InstallStage, val sessionId: Int? = null, val error: Int = 0)

/**
 * BiliPai c7f835b34d8323358be57be298f86f29fecb53c8, GPL-3.0:
 * app/src/main/java/com/android/purebilibili/{feature/settings/AppUpdateAutoCheckGate.kt,
 * core/store/SettingsManager.kt}. The gate is owned by StackLifeApplication's process instance.
 * UI events and install receipts enter on main. A channel generation owns uncommitted work;
 * PackageInstaller receipts instead belong to the session that durably claimed them.
 */
internal class AppUpdates(private val app: Context,
    private val releases: StackLifeReleases = StackLifeReleases(app),
    private val scan: suspend (UpdateChannel) -> ReleaseUpdate? = releases::check) {
    private val prefs = app.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var installed by mutableStateOf(installedVersion(app)); private set
    var channel by mutableStateOf(initialChannel()); private set
    var migrated by mutableStateOf(prefs.getBoolean("migratedChannel", false)); private set
    var autoCheck by mutableStateOf(prefs.getBoolean("automaticCheck", true)); private set
    var autoInstall by mutableStateOf(prefs.getBoolean("automaticInstall", false)); private set
    var installation by mutableStateOf(restoreInstall()); private set
    private val state = MutableStateFlow(UpdateStatus(restoreLast()))
    val status = state.asStateFlow()
    private var entered = false
    @Volatile private var generation = 0L
    val permissionGeneration: Long get() = generation
    private var work: Job? = null
    private var automatic = false
    private var manualCheck = false
    private var confirmation: Intent? = null

    private fun initialChannel(): UpdateChannel {
        val value = prefs.getString(CHANNEL, null)
        if (value != null) return UpdateChannel.entries.firstOrNull { it.name == value } ?: UpdateChannel.STABLE
        val existing = prefs.all.isNotEmpty()
        val choice = if (existing) UpdateChannel.BETA else UpdateChannel.STABLE
        prefs.edit().putString(CHANNEL, choice.name).putBoolean("migratedChannel", existing).apply()
        return choice
    }

    private fun release(key: String): ReleaseUpdate? = prefs.getString(key, null)?.let {
        runCatching { Json.decodeFromString<ReleaseUpdate>(it) }.getOrNull()
    }

    private fun restoreInstall(): Installation? {
        if (!prefs.contains(SESSION)) return null
        val id = prefs.getInt(SESSION, -1)
        val update = release(SESSION_RELEASE) ?: release(OFFER)
        if (id < 0 || update == null) {
            clearSession()
            return null
        }
        if (installed.code >= update.versionCode) {
            clearSession()
            return if (installed.code == update.versionCode && installed.name == update.version) {
                removeInstalledApk(update.version)
                Installation(update, InstallStage.UPDATED)
            } else null
        }
        // Old builds stored the receipt's release only in the channel's offer slot.
        if (!prefs.contains(SESSION_RELEASE)) prefs.edit().putString(SESSION_RELEASE, Json.encodeToString(update)).apply()
        return Installation(update, InstallStage.RECOVER, id)
    }

    private fun restoreLast(): CheckedRelease? {
        val offer = release(OFFER)
        val problem = prefs.getString(PROBLEM, null)?.let { value -> ReleaseProblem.entries.firstOrNull { it.name == value } }
        val time = prefs.getLong(CHECKED_AT, prefs.getLong("lastAttempt", 0))
        if (problem != null) return CheckedRelease(time, null, problem, cached = true)
        if (offer == null) return when {
            prefs.contains(OFFER) -> CheckedRelease(time, null, ReleaseProblem.INVALID, cached = true)
            prefs.getBoolean("noNewer", false) -> CheckedRelease(time, null, null, cached = true)
            else -> null
        }
        if (offer.versionCode in 1L..installed.code) return CheckedRelease(time, null, null, cached = true)
        return try {
            requireUpgrade(offer, installed)
            CheckedRelease(time, offer, null, cached = true)
        } catch (e: ReleaseFailure) {
            CheckedRelease(time, null, e.problem, cached = true)
        }
    }

    private fun invalidate() {
        generation++
        work?.cancel()
        work = null
        automatic = false
    }

    fun selectChannel(value: UpdateChannel) {
        if (channel == value) return
        invalidate()
        channel = value
        migrated = false
        prefs.edit().putString(CHANNEL, value.name).putBoolean("migratedChannel", false)
            .remove(OFFER).remove(PROBLEM).remove(CHECKED_AT).remove("noNewer").apply()
        state.value = UpdateStatus(null)
    }

    fun setChecking(enabled: Boolean) {
        autoCheck = enabled
        prefs.edit().putBoolean("automaticCheck", enabled).apply()
        if (!enabled) {
            setInstalling(false)
            if (automatic && !manualCheck && state.value.checking) {
                invalidate()
                state.value = UpdateStatus(state.value.last)
            }
        }
    }

    fun setInstalling(enabled: Boolean) {
        autoInstall = enabled && autoCheck
        prefs.edit().putBoolean("automaticInstall", autoInstall).apply()
        if (!autoInstall && automatic && state.value.preparation != Preparation.IDLE) {
            invalidate()
            state.value = UpdateStatus(state.value.last)
        }
    }

    fun enterForeground() {
        if (entered) return
        entered = true
        if (autoCheck && app.packageName == RELEASE_PACKAGE) check(automatically = true)
    }

    fun checkRelease() = check(automatically = false)

    private fun check(automatically: Boolean) {
        val old = state.value
        if (old.checking) {
            // Keep both interests: a manual join survives disabling auto-check; it also doesn't
            // revoke an existing opt-in automatic installation when that preference stays on.
            if (automatically) automatic = true else manualCheck = true
            return
        }
        if (old.preparation == Preparation.DOWNLOADING || old.preparation == Preparation.SUBMITTING) return
        val token = generation
        val selected = channel
        automatic = automatically
        manualCheck = !automatically
        state.value = UpdateStatus(old.last, checking = true)
        work = scope.launch {
            val result = try {
                val offer = scan(selected)
                CheckedRelease(System.currentTimeMillis(), offer, null, cached = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                CheckedRelease(System.currentTimeMillis(), null, (e as? ReleaseFailure)?.problem ?: ReleaseProblem.NETWORK, cached = false)
            }
            if (token != generation) return@launch
            prefs.edit().putLong(CHECKED_AT, result.time).putString(PROBLEM, result.problem?.name)
                .putString(OFFER, result.offer?.let { Json.encodeToString(it) })
                .putBoolean("noNewer", result.problem == null && result.offer == null).apply()
            state.value = UpdateStatus(result)
            if (automatic && autoInstall && result.offer != null) begin(automatically = true)
        }
    }

    /** Permission and confirmation activities are launched only by explicit actions in UpdateScreen. */
    fun begin(automatically: Boolean = false) {
        val s = state.value
        val update = s.last?.offer ?: return
        if (s.checking || installation?.sessionId != null || s.preparation == Preparation.DOWNLOADING || s.preparation == Preparation.SUBMITTING) return
        automatic = automatically
        val token = generation
        state.value = s.copy(preparation = Preparation.DOWNLOADING, progress = 0, error = 0)
        work = scope.launch {
            var recorded: Int? = null
            try {
                val apk = releases.prepare(update) { percent ->
                    state.update { if (generation == token && it.preparation == Preparation.DOWNLOADING) it.copy(progress = percent) else it }
                }
                if (generation != token) return@launch
                if (!app.packageManager.canRequestPackageInstalls()) {
                    state.value = state.value.copy(preparation = Preparation.PERMISSION)
                    return@launch
                }
                state.value = state.value.copy(preparation = Preparation.SUBMITTING)
                installUpdate(app, apk, update, automatically && autoInstall) { id ->
                    kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                        if (generation != token) throw CancellationException("Update selection changed")
                        recorded = id
                        work = null // The committed hand-off is independent of subsequent channel or preference changes.
                        installation = Installation(update, InstallStage.WAITING, id)
                        state.value = state.value.copy(preparation = Preparation.IDLE)
                    }
                    // Persist off main before PackageInstaller is allowed to commit. A process death
                    // before this point cannot leave an untracked committed session.
                    if (!prefs.edit().putString(SESSION_RELEASE, Json.encodeToString(update)).putInt(SESSION, id).commit()) {
                        throw java.io.IOException("Could not save install receipt")
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (recorded != null && installation?.sessionId == recorded) systemFailed()
                else if (generation == token) state.value = state.value.copy(preparation = Preparation.IDLE,
                    error = if (e is ReleaseFailure) e.problem.messageId() else R.string.update_system_failed)
            }
        }
    }

    fun returnedFromPermission(expected: Long) {
        if (expected == generation && state.value.preparation == Preparation.PERMISSION && app.packageManager.canRequestPackageInstalls()) begin()
    }

    fun takeConfirmation(): Intent? {
        val receipt = installation ?: return null
        if (receipt.stage != InstallStage.CONFIRM) return null
        return confirmation?.also { confirmation = null; installation = receipt.copy(stage = InstallStage.WAITING) }
    }

    private fun clearSession() = prefs.edit().remove(SESSION).remove(SESSION_RELEASE).apply()

    fun systemFailed() {
        val receipt = installation
        if (receipt?.sessionId == null) {
            state.value = state.value.copy(preparation = Preparation.IDLE, error = R.string.update_system_failed)
            return
        }
        runCatching { app.packageManager.packageInstaller.abandonSession(receipt.sessionId) }
        finish(receipt, R.string.update_system_failed)
    }

    fun discardUnresolvedSession() {
        val receipt = installation ?: return
        val id = receipt.sessionId ?: return
        if (receipt.stage != InstallStage.RECOVER) return
        val installer = app.packageManager.packageInstaller
        if (runCatching { if (installer.getSessionInfo(id) != null) installer.abandonSession(id) }.isSuccess) {
            finish(receipt, R.string.update_cancelled)
        } else installation = receipt.copy(error = R.string.update_system_failed)
    }

    // Caller has matched this release to Android's installed package identity.
    private fun removeInstalledApk(version: String) {
        scope.launch(Dispatchers.IO) { java.io.File(app.cacheDir, "app-updates/${apkName(version)}").delete() }
    }

    private fun finish(receipt: Installation, problem: Int) {
        confirmation = null
        clearSession()
        installation = receipt.copy(sessionId = null, stage = if (problem == 0) InstallStage.UPDATED else InstallStage.FAILED, error = problem)
        if (problem == 0) removeInstalledApk(receipt.release.version)
        if (problem == 0 && (state.value.last?.offer?.versionCode ?: Long.MAX_VALUE) <= receipt.release.versionCode) {
            prefs.edit().remove(OFFER).putBoolean("noNewer", true).apply()
            state.update { it.copy(last = it.last?.copy(offer = null)) }
        }
    }

    @Suppress("DEPRECATION")
    fun onInstallResult(intent: Intent) {
        val receipt = installation ?: return
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id < 0 || id != receipt.sessionId) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmation == null) systemFailed() else installation = receipt.copy(stage = InstallStage.CONFIRM)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                installed = installedVersion(app)
                finish(receipt, if (installed.code == receipt.release.versionCode && installed.name == receipt.release.version)
                    0 else R.string.update_incompatible)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish(receipt, R.string.update_cancelled)
            PackageInstaller.STATUS_FAILURE_STORAGE -> finish(receipt, R.string.update_storage)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> finish(receipt, R.string.update_blocked)
            else -> finish(receipt, R.string.update_system_failed)
        }
    }
}

internal fun ReleaseProblem.messageId() = when (this) {
    ReleaseProblem.RATE_LIMIT -> R.string.update_rate_limit
    ReleaseProblem.INVALID -> R.string.update_invalid
    ReleaseProblem.INCOMPATIBLE -> R.string.update_incompatible
    ReleaseProblem.NETWORK -> R.string.update_network
}
