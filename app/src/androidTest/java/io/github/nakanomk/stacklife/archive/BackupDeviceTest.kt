package io.github.nakanomk.stacklife.archive

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.i18n.AppLocales
import io.github.nakanomk.stacklife.storage.PlannerDatabase
import io.github.nakanomk.stacklife.updates.AppUpdates
import io.github.nakanomk.stacklife.updates.UpdateChannel
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupDeviceTest {
    internal class Device : Closeable {
        val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "archive-device-${UUID.randomUUID()}"
        val root: File = Files.createTempDirectory(target.cacheDir.toPath(), name).toFile()
        private val stores = mutableSetOf<String>()
        val app = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = root
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(file: String, mode: Int): SharedPreferences {
                val scoped = "$name-$file"; synchronized(stores) { stores.add(scoped) }
                return target.getSharedPreferences(scoped, mode)
            }
        }
        lateinit var db: PlannerDatabase
        lateinit var connections: ConnectionStore
        lateinit var assistant: AssistantPreference
        lateinit var updates: AppUpdates
        lateinit var device: BackupDevice
        val generations get() = File(root, "$name.generations").list().orEmpty().toSet()
        init { reopen() }
        fun reopen() {
            if (::db.isInitialized) db.close()
            db = PlannerDatabase.open(target, "$name.db")
            connections = ConnectionStore(app, name, db.backupDeviceDao()); assistant = AssistantPreference(app)
            updates = AppUpdates(app) { error("Device activation must never contact the network") }
            device = BackupDevice(app, db.backupDeviceDao(), connections, assistant, updates)
        }
        suspend fun apply(incoming: IncomingDevice) {
            val preview = device.preview(incoming); assertTrue(preview.conflicts.isEmpty())
            device.withActivation(incoming, preview.stamp) { activation -> db.withTransaction { activation.commit() } }
        }
        override fun close() {
            try { runBlocking { connections.clear() } } finally {
                db.close(); target.deleteDatabase("$name.db"); stores.forEach(target::deleteSharedPreferences); root.deleteRecursively()
            }
        }
    }
    private val origin = "https://fixture.invalid/v1"
    private val other = "https://fixture.invalid:8443/v1"
    private val parts = setOf(BackupPart.CONNECTION)
    private fun assertNotDisclosed(value: Any?, secret: String) { assertFalse(value.toString().contains(secret)) }

    @Test fun defaultIncludesKeyRedactionBindsCanonicalOriginAndAbsentIsNotExplicitEmpty() = runBlocking {
        Device().use { source -> Device().use { target ->
            val donorKey = "fixture-only-${UUID.randomUUID()}"; val targetKey = "target-only-${UUID.randomUUID()}"
            source.connections.save(origin, "donor", donorKey, true)
            target.connections.save(other, "port-mismatch", targetKey)
            val full = source.device.snapshot(parts)
            val redacted = source.device.snapshot(parts, includeKey = false)
            try {
                assertTrue(full.hasKey && full.connection!!.decodeToString().contains(donorKey))
                assertFalse(redacted.hasKey); assertFalse(requireNotNull(redacted.connection).decodeToString().contains("\"key\""))
                val noKey = target.device.read(null, redacted.connection)
                val denied = target.device.preview(noKey); assertTrue(denied.conflicts.isNotEmpty())
                assertTrue(runCatching { target.device.withActivation(noKey, denied.stamp) { error("unconfirmed cross-origin change") } }
                    .exceptionOrNull() is DeviceStampChanged)
                assertTrue(target.connections.load()?.key == targetKey)
                target.connections.save(origin, "local", targetKey)
                target.apply(noKey); assertTrue(target.connections.load()?.key == targetKey); assertEquals("donor", target.connections.profile()?.model)
                target.apply(target.device.read(null, null)); assertTrue(target.connections.load()?.key == targetKey)
                val complete = target.device.read(null, full.connection); val review = target.device.preview(complete)
                listOf(full, complete, review, review.stamp).forEach { assertNotDisclosed(it, donorKey) }
                target.apply(complete); target.reopen(); assertTrue(target.connections.load()?.key == donorKey)
                assertFalse(target.root.walkTopDown().filter { it.isFile }.any { it.readBytes().decodeToString().contains(donorKey) })
                assertTrue(source.connections.load()?.key == donorKey) // No copy/move of the source Keystore entry.
                source.connections.clear()
                val empty = source.device.snapshot(parts); assertEquals(0, empty.connectionCount)
                target.apply(target.device.read(null, empty.connection)); assertNull(target.connections.load()); assertTrue(target.generations.isEmpty())
            } finally { full.connection?.fill(0); redacted.connection?.fill(0) }
        } }
    }

    @Test fun staleReviewStaleEditorAndFailedTargetEncryptionHaveNoSideEffectsOrCredentialDiagnostics() = runBlocking {
        Device().use { source -> Device().use { target ->
            val secret = "fixture-only-${UUID.randomUUID()}"; source.connections.save(origin, "restored", secret)
            val member = source.device.snapshot(parts); val incoming = target.device.read(null, member.connection); member.connection?.fill(0)
            val before = target.device.preview(incoming); val editorEpoch = target.connections.editEpoch()
            target.connections.save(origin, "new local", "local-key")
            assertTrue(runCatching { target.device.withActivation(incoming, before.stamp) { error("stale work executed") } }.exceptionOrNull() is DeviceStampChanged)
            assertTrue(runCatching { target.connections.save(origin, "stale editor", null, expectedEpoch = editorEpoch) }.isFailure)
            assertTrue(runCatching { target.connections.clear(editorEpoch) }.isFailure)
            assertEquals("new local", target.connections.profile()?.model); target.connections.clear()
            // A real target Keystore alias whose policy prohibits encryption: staging must fail before any DB write.
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("io.github.nakanomk.stacklife.${target.name}", KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
            val baseline = target.db.backupDeviceDao().active(); val failure = runCatching { target.apply(incoming) }.exceptionOrNull()
            assertNotNull(failure); assertNotDisclosed(failure, secret); assertEquals(baseline, target.db.backupDeviceDao().active())
            assertNull(target.connections.load()); assertTrue(target.generations.isEmpty())
            val malformed = """{"configured":false,"illegal":"$secret"}""".encodeToByteArray()
            val parseError = runCatching { target.device.read(null, malformed) }.exceptionOrNull()
            assertNotNull(parseError); assertNotDisclosed(parseError, secret); assertNull(parseError?.cause)
        } }
    }

    @Test fun powerLossBeforeAndAfterCommitReopensOnlyTheAuthoritativeGenerationAndReplaysDevicePreferences() = runBlocking {
        Device().use { source -> Device().use { target ->
            target.connections.save(origin, "before", "before-key")
            val old = target.generations.single()
            target.connections.exclusive {
                target.connections.beginPublication()
                target.connections.stage(ServiceConnection(serviceProfile(other, "uncommitted"), "uncommitted-key"))
            } // Process terminated with two complete ciphertext files but only one DB reference.
            target.reopen(); target.device.initialize(); assertEquals(setOf(old), target.generations)
            assertEquals("before", target.connections.profile()?.model)
            source.connections.save(other, "restored", "restored-key"); source.assistant.setEnabled(false)
            source.updates.selectChannel(UpdateChannel.BETA); source.updates.setInstalling(true)
            val all = source.device.snapshot(parts + BackupPart.PREFERENCES)
            val prefs = archiveJson.decodeFromString<DevicePreferences>(requireNotNull(all.preferences).decodeToString())
                .copy(widgets = listOf(WidgetTemplate(true, 4, null, null)), language = AppLocales.choice(target.app))
            val incoming = target.device.read(archiveJson.encodeToString(prefs).encodeToByteArray(), all.connection); all.connection?.fill(0)
            val preview = target.device.preview(incoming)
            val rolledBack = runCatching { target.device.withActivation(incoming, preview.stamp) { activation ->
                target.db.withTransaction { activation.commit(); error("representative Room failure") }
            } }.exceptionOrNull()
            assertNotNull(rolledBack); assertFalse(rolledBack is ActivationCommittedWarning)
            assertEquals(setOf(old), target.generations); assertEquals("before", target.connections.profile()?.model); assertTrue(target.assistant.enabled)
            val committed = runCatching { target.device.withActivation(incoming, preview.stamp) { activation ->
                target.db.withTransaction { activation.commit() }; error("process lost after commit but before cleanup")
            } }.exceptionOrNull()
            assertTrue(committed is ActivationCommittedWarning); assertTrue(target.connections.recoveryMarker.exists())
            target.reopen(); target.device.initialize(); target.device.recover()
            assertFalse(target.assistant.enabled); assertEquals(UpdateChannel.BETA, target.updates.channel); assertTrue(target.updates.autoInstall)
            assertEquals("restored", target.connections.profile()?.model); assertEquals(1, target.generations.size); assertFalse(old in target.generations)
            assertNull(target.db.backupDeviceDao().active()?.pendingPreferences); assertFalse(target.connections.recoveryMarker.exists())
            assertTrue(BackupWidgetTemplates.snapshot(target.app).contains(WidgetTemplate(true, 4, null, null)))
            assertNull(target.updates.installation); assertNull(target.updates.status.value.last)
        } }
    }

    @Test fun restoredUpdateChoicesPreserveOnlyTheTargetsInstallSessionAndStalePreferencesMustBeReviewedAgain() = runBlocking {
        Device().use { source -> Device().use { target ->
            val local = target.app.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
            val offer = io.github.nakanomk.stacklife.updates.ReleaseUpdate("99.0.0", 99999, "Target-only receipt", 100L, "0".repeat(64))
            check(local.edit().putInt("installSession", 41).putString("sessionRelease", archiveJson.encodeToString(offer)).commit())
            target.reopen()
            assertEquals(41, target.updates.installation?.sessionId)
            source.updates.selectChannel(UpdateChannel.BETA); source.updates.setChecking(false)
            val prefs = requireNotNull(source.device.snapshot(setOf(BackupPart.PREFERENCES)).preferences)
            assertFalse(prefs.decodeToString().contains("installSession")); assertFalse(prefs.decodeToString().contains("sessionRelease"))
            val incoming = target.device.read(prefs, null); val approval = target.device.preview(incoming)
            target.assistant.setEnabled(false); target.assistant.setEnabled(true) // same visible state, renewed authorization
            assertTrue(runCatching { target.device.withActivation(incoming, approval.stamp) { error("stale activation") } }
                .exceptionOrNull() is DeviceStampChanged)
            val oldPermissionTicket = target.updates.permissionGeneration
            target.apply(incoming)
            assertNotEquals(oldPermissionTicket, target.updates.permissionGeneration)
            assertFalse(target.updates.autoCheck); assertFalse(target.updates.autoInstall); assertEquals(UpdateChannel.BETA, target.updates.channel)
            target.reopen(); assertEquals(41, target.updates.installation?.sessionId)
            assertEquals(offer, target.updates.installation?.release); assertNull(target.updates.status.value.last)
            assertNull(source.updates.installation)
        } }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun widgetTemplateIsClaimedOnlyByANewHostIdAndNeverOverwritesAnExistingWeek() = runBlocking {
        Device().use { target ->
            val manager = android.appwidget.AppWidgetManager.getInstance(target.target)
            val host = android.appwidget.AppWidgetHost(target.target, 0x534C4232)
            val component = android.content.ComponentName(target.target, io.github.nakanomk.stacklife.widget.WeekWidgetProvider::class.java)
            val picked = io.github.nakanomk.stacklife.widget.PICKED_WEEK
            fun bind(): Int {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                val id = host.allocateAppWidgetId()
                automation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
                try { assertTrue(manager.bindAppWidgetIdIfAllowed(id, component)) } finally { automation.dropShellPermissionIdentity() }
                return id
            }
            try {
                val existing = bind()
                manager.updateAppWidgetOptions(existing, android.os.Bundle().apply { putLong(picked, 11L) })
                val prefs = DevicePreferences(true, AppLocales.choice(target.app), target.updates.logicalPreferences(),
                    listOf(WidgetTemplate(true, 4, null, null)))
                target.apply(target.device.read(archiveJson.encodeToString(prefs).encodeToByteArray(), null))
                BackupWidgetTemplates.applyToNew(target.app, manager, intArrayOf(existing), true)
                assertEquals(11L, manager.getAppWidgetOptions(existing).getLong(picked))
                val fresh = bind()
                BackupWidgetTemplates.applyToNew(target.app, manager, intArrayOf(fresh), true)
                assertEquals(4L, manager.getAppWidgetOptions(fresh).getLong(picked))
                manager.updateAppWidgetOptions(fresh, android.os.Bundle().apply { putLong(picked, 18L) })
                BackupWidgetTemplates.applyToNew(target.app, manager, intArrayOf(fresh), true)
                assertEquals(18L, manager.getAppWidgetOptions(fresh).getLong(picked))
                val second = bind()
                BackupWidgetTemplates.applyToNew(target.app, manager, intArrayOf(second), true)
                assertFalse(manager.getAppWidgetOptions(second).containsKey(picked))
            } finally { host.deleteHost() }
        }
    }

    @Test fun snapshotExcludesConcurrentSaveAndWipesBorrowedCredentialBytesOnExit() = runBlocking {
        Device().use { target ->
            target.connections.save(origin, "initial", "old-key")
            val acquired = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val saving = CompletableDeferred<Unit>()
            lateinit var borrowed: ByteArray
            val reader = async(Dispatchers.IO) { target.device.withSnapshot(parts) { member ->
                borrowed = requireNotNull(member.connection); acquired.complete(Unit); release.await()
                assertTrue(borrowed.decodeToString().contains("old-key"))
            } }
            acquired.await()
            val writer = async(Dispatchers.IO) { saving.complete(Unit); target.connections.save(other, "next", "new-key") }
            saving.await(); assertFalse(writer.isCompleted); release.complete(Unit); reader.await(); writer.await()
            assertTrue(borrowed.all { it == 0.toByte() }); assertEquals("next", target.connections.profile()?.model)
        }
    }
}
