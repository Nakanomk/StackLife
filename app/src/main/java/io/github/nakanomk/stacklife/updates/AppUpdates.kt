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
import java.io.File

private const val DAY = 24 * 60 * 60 * 1000L
private const val SESSION = "installSession"
private const val OFFER = "release"
internal enum class UpdateStage { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, PERMISSION, INSTALLING, CONFIRM, RECOVER, FAILED, UPDATED }
internal data class UpdateStatus(val stage: UpdateStage, val release: ReleaseUpdate? = null, val dialog: Boolean = false,
    val progress: Int = 0, val problem: Int = 0)

/** Device-local distribution preferences, never part of a planner command or sent to the model. */
internal class AppUpdates(private val app: Context) {
    private val prefs = app.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    private val releases by lazy { StackLifeReleases(app) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = MutableStateFlow(restore())
    val status = state.asStateFlow()
    var autoCheck by mutableStateOf(prefs.getBoolean("automaticCheck", true)); private set
    var autoInstall by mutableStateOf(prefs.getBoolean("automaticInstall", false)); private set
    private var job: Job? = null
    private var automatic = false
    private var prepared: File? = null
    private var confirmation: Intent? = null

    private fun restore(): UpdateStatus {
        val cached = prefs.getString(OFFER, null)?.let { runCatching { Json.decodeFromString<ReleaseUpdate>(it) }.getOrNull() }
        val code = cached?.let { installedVersion(app).code }
        if (cached != null && code != null && code < cached.versionCode) {
            return UpdateStatus(if (prefs.contains(SESSION)) UpdateStage.RECOVER else UpdateStage.AVAILABLE, cached)
        }
        val applied = cached != null && prefs.contains(SESSION) && code == cached.versionCode
        forgetFile(cached)
        if (cached != null || prefs.contains(SESSION)) prefs.edit().remove(OFFER).remove(SESSION).apply()
        return UpdateStatus(when { applied -> UpdateStage.UPDATED; prefs.getBoolean("noNewer", false) -> UpdateStage.CURRENT; else -> UpdateStage.IDLE })
    }

    private fun forgetFile(release: ReleaseUpdate?) {
        if (release != null) scope.launch(Dispatchers.IO) { File(app.cacheDir, "app-updates/${apkName(release.version)}").delete() }
    }

    fun setChecking(enabled: Boolean) {
        autoCheck = enabled
        prefs.edit().putBoolean("automaticCheck", enabled).apply()
        if (!enabled) {
            setInstalling(false)
            if (automatic && state.value.stage == UpdateStage.CHECKING) { job?.cancel(); state.value = UpdateStatus(UpdateStage.IDLE) }
        } else enterHome()
    }

    fun setInstalling(enabled: Boolean) {
        autoInstall = enabled && autoCheck
        prefs.edit().putBoolean("automaticInstall", autoInstall).apply()
        if (!autoInstall && automatic) when (state.value.stage) {
            UpdateStage.DOWNLOADING, UpdateStage.READY, UpdateStage.PERMISSION -> later()
            else -> Unit
        }
        if (autoInstall && state.value.stage == UpdateStage.AVAILABLE) begin(automatically = true)
    }

    /** Like BiliPai's entry check; a persisted cooldown also covers process restarts. Manual checks bypass it. */
    fun enterHome() {
        if (!autoCheck) return
        val now = System.currentTimeMillis()
        val since = now - prefs.getLong("lastAttempt", 0)
        val s = state.value
        if (s.stage == UpdateStage.RECOVER) { state.value = s.copy(dialog = true); return }
        if (since !in 0 until DAY) checkRelease(manual = false)
        else if (s.stage == UpdateStage.AVAILABLE && !s.dialog && now - prefs.getLong("later", 0) !in 0 until DAY) {
            if (autoInstall) begin(automatically = true) else state.value = s.copy(dialog = true)
        }
    }

    fun checkRelease(manual: Boolean = true) {
        val old = state.value
        when (old.stage) {
            UpdateStage.CHECKING, UpdateStage.DOWNLOADING, UpdateStage.READY, UpdateStage.PERMISSION,
            UpdateStage.INSTALLING, UpdateStage.CONFIRM, UpdateStage.RECOVER -> {
                if (manual) state.value = old.copy(dialog = true)
                return
            }
            else -> Unit
        }
        automatic = !manual
        prefs.edit().putLong("lastAttempt", System.currentTimeMillis()).putBoolean("noNewer", false).apply()
        state.value = UpdateStatus(UpdateStage.CHECKING, dialog = manual)
        job = scope.launch {
            try {
                val next = releases.check()
                val foregroundRequest = state.value.dialog
                if (old.release?.version != next?.version) forgetFile(old.release)
                prefs.edit().putString(OFFER, next?.let { Json.encodeToString(it) }).putBoolean("noNewer", next == null).apply()
                if (next == null) state.value = UpdateStatus(UpdateStage.CURRENT, dialog = foregroundRequest)
                else {
                    state.value = UpdateStatus(UpdateStage.AVAILABLE, next, dialog = foregroundRequest || automatic && autoCheck)
                    if (automatic && autoInstall) begin(automatically = true)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e, null, state.value.dialog) }
        }
    }

    fun show() { state.update { it.copy(dialog = true) } }
    fun later() {
        val s = state.value
        if (s.release != null) prefs.edit().putLong("later", System.currentTimeMillis()).apply()
        if (s.stage == UpdateStage.DOWNLOADING || s.stage == UpdateStage.READY || s.stage == UpdateStage.PERMISSION) {
            job?.cancel(); prepared = null; state.value = UpdateStatus(UpdateStage.AVAILABLE, s.release)
        } else state.value = s.copy(dialog = false)
    }

    fun begin(automatically: Boolean = false) {
        val s = state.value
        val update = s.release ?: return
        when (s.stage) {
            UpdateStage.AVAILABLE, UpdateStage.PERMISSION, UpdateStage.FAILED, UpdateStage.RECOVER -> Unit
            else -> return
        }
        automatic = automatically
        prepared = null
        state.value = UpdateStatus(UpdateStage.DOWNLOADING, update, dialog = true)
        job = scope.launch {
            try {
                if (s.stage == UpdateStage.RECOVER) abandonOldSession()
                prepared = releases.prepare(update) { percent -> state.update { if (it.stage == UpdateStage.DOWNLOADING) it.copy(progress = percent) else it } }
                state.value = UpdateStatus(UpdateStage.READY, update, dialog = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e, update, true) }
        }
    }

    /** The host calls this only when resumed on Home or About, never from a background receiver. */
    fun installWhenVisible() {
        val s = state.value
        if (s.stage != UpdateStage.READY) return
        val apk = prepared ?: return
        val release = s.release ?: return
        prepared = null
        if (!app.packageManager.canRequestPackageInstalls()) {
            state.value = s.copy(stage = UpdateStage.PERMISSION)
            return
        }
        state.value = s.copy(stage = UpdateStage.INSTALLING)
        job = scope.launch {
            try { installUpdate(app, apk, release, automatic && autoInstall) { id -> prefs.edit().putInt(SESSION, id).commit() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { systemFailed() }
        }
    }

    fun returnedFromPermission() {
        if (state.value.stage == UpdateStage.PERMISSION && app.packageManager.canRequestPackageInstalls()) begin(automatic && autoInstall)
    }
    fun takeConfirmation(): Intent? {
        if (state.value.stage != UpdateStage.CONFIRM) return null
        return confirmation?.also { confirmation = null; state.update { s -> s.copy(stage = UpdateStage.INSTALLING) } }
    }
    fun systemFailed() {
        abandonOldSession()
        state.update { it.copy(stage = UpdateStage.FAILED, problem = R.string.update_system_failed, dialog = true) }
    }
    private fun abandonOldSession() {
        val old = prefs.getInt(SESSION, -1)
        prefs.edit().remove(SESSION).apply()
        if (old != -1) runCatching { app.packageManager.packageInstaller.abandonSession(old) }
        confirmation = null
    }
    @Suppress("DEPRECATION")
    fun onInstallResult(intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id < 0 || id != prefs.getInt(SESSION, -1)) return
        val s = state.value
        when (val result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmation == null) systemFailed() else state.value = s.copy(stage = UpdateStage.CONFIRM, dialog = true)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                forgetFile(s.release)
                prefs.edit().remove(SESSION).remove(OFFER).apply()
                state.value = UpdateStatus(UpdateStage.UPDATED)
            }
            else -> {
                prefs.edit().remove(SESSION).apply()
                val reason = when (result) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> R.string.update_cancelled
                    PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.update_storage
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.update_blocked
                    else -> R.string.update_system_failed
                }
                state.value = s.copy(stage = UpdateStage.FAILED, problem = reason, dialog = true)
            }
        }
    }
    private fun fail(e: Exception, update: ReleaseUpdate?, dialog: Boolean) {
        val message = when ((e as? ReleaseFailure)?.problem) {
            ReleaseProblem.RATE_LIMIT -> R.string.update_rate_limit
            ReleaseProblem.INVALID -> R.string.update_invalid
            ReleaseProblem.INCOMPATIBLE -> R.string.update_incompatible
            else -> R.string.update_network
        }
        state.value = UpdateStatus(UpdateStage.FAILED, update, dialog, problem = message)
    }
}
