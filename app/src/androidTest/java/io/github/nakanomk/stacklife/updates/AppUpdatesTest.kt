package io.github.nakanomk.stacklife.updates

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.planner.Appearance
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppUpdatesTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val file = "update-test-${UUID.randomUUID()}"
    private val context by lazy {
        object : ContextWrapper(ui.activity.applicationContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(file, mode)
        }
    }
    private val offer = ReleaseUpdate("99.0.0", 10000, "Offline fixture", 100, "0".repeat(64), "2026-11-22T19:23:01Z")
    @After fun clean() { ui.activity.deleteSharedPreferences(file) }

    @Test fun manualCheckJoinsFirstForegroundAndNewProcessDoesNotWaitADay() {
        val requests = mutableListOf<UpdateChannel>()
        val response = CompletableDeferred<ReleaseUpdate?>()
        val started = CompletableDeferred<Unit>()
        val first = AppUpdates(context) { requests += it; started.complete(Unit); response.await() }
        ui.runOnIdle {
            first.enterForeground()
            first.checkRelease()
            first.enterForeground()
        }
        ui.waitUntil { started.isCompleted }
        response.complete(offer)
        ui.waitUntil { first.status.value.last?.offer == offer }
        ui.runOnIdle {
            first.enterForeground()
            assertEquals(Preparation.IDLE, first.status.value.preparation)
            assertNull(first.installation)
        }
        val next = AppUpdates(context) { requests += it; throw ReleaseFailure(ReleaseProblem.RATE_LIMIT, "fixture") }
        assertTrue(next.status.value.last!!.cached)
        ui.runOnIdle { next.enterForeground() }
        ui.waitUntil { next.status.value.last?.problem == ReleaseProblem.RATE_LIMIT }
        assertEquals(listOf(UpdateChannel.STABLE, UpdateChannel.STABLE), requests)
        assertNull(next.status.value.last!!.offer)
        val reopened = AppUpdates(context) { error("Restoring cached failure must not check") }
        assertEquals(ReleaseProblem.RATE_LIMIT, reopened.status.value.last?.problem)
        assertTrue(reopened.status.value.last!!.cached)
    }

    @Test fun disabledCheckDoesNotRunOnResumeOrWhenEditingThePreference() {
        context.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit().putBoolean("automaticCheck", false).commit()
        var calls = 0
        val updates = AppUpdates(context) { calls++; null }
        ui.runOnIdle {
            updates.enterForeground()
            updates.setChecking(true)
            updates.enterForeground()
        }
        ui.runOnIdle {
            assertEquals(0, calls)
            updates.checkRelease()
        }
        ui.waitUntil { updates.status.value.last != null }
        ui.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun channelSwitchDropsLateSuccessButPreservesTheOriginalInstallReceipt() {
        context.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit().putBoolean("automaticCheck", true)
            .putInt("installSession", 42).putString("release", Json.encodeToString(offer)).commit()
        lateinit var late: Continuation<ReleaseUpdate?>
        val started = CompletableDeferred<Unit>()
        val stable = offer.copy(version = "98.0.0", versionCode = offer.versionCode - 1)
        val updates = AppUpdates(context) {
            if (it == UpdateChannel.BETA) suspendCoroutine { pending -> late = pending; started.complete(Unit) } else stable
        }
        assertEquals(UpdateChannel.BETA, updates.channel)
        assertTrue(updates.migrated)
        ui.runOnIdle { updates.enterForeground() }
        ui.waitUntil { started.isCompleted }
        ui.runOnIdle {
            updates.selectChannel(UpdateChannel.STABLE)
            assertNull(updates.status.value.last)
            assertEquals(offer, updates.installation?.release)
            updates.checkRelease()
        }
        ui.waitUntil { updates.status.value.last?.offer == stable }
        ui.runOnIdle { late.resume(offer) }
        ui.runOnIdle { assertEquals(stable, updates.status.value.last?.offer) }
        val restored = AppUpdates(context) { error("Unrequested release scan") }
        assertEquals(stable, restored.status.value.last?.offer)
        assertEquals(offer, restored.installation?.release)
        fun receipt(id: Int, status: Int) = Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
            .putExtra(PackageInstaller.EXTRA_STATUS, status)
        ui.runOnIdle {
            restored.onInstallResult(receipt(41, PackageInstaller.STATUS_SUCCESS))
            assertEquals(42, restored.installation?.sessionId)
            restored.onInstallResult(receipt(42, PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, Intent("fixture")))
            assertEquals(InstallStage.CONFIRM, restored.installation?.stage)
            assertEquals("fixture", restored.takeConfirmation()?.action)
            assertNull(restored.takeConfirmation())
            restored.onInstallResult(receipt(42, PackageInstaller.STATUS_FAILURE_ABORTED))
            assertNull(restored.installation?.sessionId)
            assertEquals(R.string.update_cancelled, restored.installation?.error)
            assertEquals(stable, restored.status.value.last?.offer)
        }
    }

    @Test fun openingThePageOrSelectingAChannelDoesNotScanAndExplicitFailureIsVisible() {
        var requests = 0
        val updates = AppUpdates(context) { requests++; throw ReleaseFailure(ReleaseProblem.NETWORK, "Offline fixture") }
        ui.setContent { StackLifeTheme(Appearance()) { UpdateScreen(updates) {} } }
        ui.onNodeWithTag("update-channel").performClick()
        ui.onNodeWithText(ui.activity.getString(R.string.update_beta)).performClick()
        ui.runOnIdle { assertEquals(0, requests) }
        ui.onNodeWithTag("updates-page").performScrollToNode(hasTestTag("update-check"))
        ui.onNodeWithTag("update-check").performClick()
        ui.waitUntil { updates.status.value.last?.problem == ReleaseProblem.NETWORK }
        ui.onNodeWithTag("update-status").assertTextEquals(ui.activity.getString(R.string.update_network))
        ui.onNodeWithTag("update-install").assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, requests) }
    }
}
