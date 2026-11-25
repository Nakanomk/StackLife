package io.github.nakanomk.stacklife.archive

import android.content.Context
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.i18n.AppLocales
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.storage.BackupDeviceDao
import io.github.nakanomk.stacklife.storage.BackupDeviceRow
import io.github.nakanomk.stacklife.updates.AppUpdates
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val DEVICE_MEMBER_LIMIT = 128 * 1024

// Never put provider credentials in data classes (generated toString/copy), intents, temp files or SavedState.
@Serializable internal class ProviderMember(val configured: Boolean, val profile: ProviderFields? = null, val key: String? = null) {
    init { require(configured == (profile != null) && (configured || key == null)) }
}
@Serializable internal data class ProviderFields(val baseUrl: String, val model: String, val supportsImages: Boolean) {
    fun verified(): ServiceProfile = serviceProfile(baseUrl, model, supportsImages).also {
        require(it.baseUrl == baseUrl && it.model == model)
    }
}

/** Local replay journal only. The enrolled launcher IDs come from the receiving device and must
 * never enter the wire DTO; the original baseline survives a crash before an unbound widget is added. */
@Serializable private class PreferenceJournal(val values: DevicePreferences, val existingWidgets: List<Int>, val activation: String)

internal class DeviceMembers(val preferences: ByteArray?, val connection: ByteArray?, val connectionCount: Int, val hasKey: Boolean)
internal class IncomingDevice internal constructor(internal val preferences: DevicePreferences?, internal val provider: ProviderMember?) {
    val connectionCount: Int get() = if (provider?.configured == true) 1 else 0
    val hasKey: Boolean get() = provider?.key != null
}
internal data class DeviceReview(val counts: Map<BackupPart, BackupCounts>, val stamp: String, val conflicts: List<String>, val impacts: List<String>)
internal class DeviceStampChanged : IllegalStateException(uiText("Device settings changed. Review the restore again.", "设备设置已更改，请重新预览恢复。"))
internal class ActivationCommittedWarning : IllegalStateException(uiText("Restore was committed. Device activation or cleanup will be retried.", "恢复已提交，将重试设备设置生效或清理。"))

internal class DeviceActivation internal constructor(private val write: suspend () -> Unit) {
    internal var called = false; private set
    internal var open = true
    suspend fun commit() { check(open && !called); write(); called = true }
}

private val providerJson = Json { explicitNulls = false }

/** Strict duplicate/member/type handling. Serialization exceptions can contain source snippets;
 * never propagate a decoder's exception or cause across the device-member boundary. */
private inline fun <reified T> decodeMember(bytes: ByteArray, json: Json): T = try {
    require(bytes.size in 1..DEVICE_MEMBER_LIMIT)
    val text = bytes.decodeToString(throwOnInvalidSequence = true)
    // Same canonical v1 record rule as the appearance package. This also rejects duplicate keys,
    // quoted booleans/numbers and implicit defaults instead of silently accepting their coercions.
    json.decodeFromString<T>(text).also { require(json.encodeToString(it) == text) }
} catch (_: Exception) { throw invalidMember() }

private fun invalidMember() = IllegalArgumentException(uiText("Invalid device settings in the backup.", "备份中的设备设置无效。"))

/** B4's verified publication and O5's logical-record export; this two-phase, Room-owned activation
 * is local to StackLife. The transaction never waits on a connection or managed-file lock. */
internal class BackupDevice(private val context: Context, private val dao: BackupDeviceDao,
    private val connections: ConnectionStore, private val assistant: AssistantPreference, private val updates: AppUpdates) {
    private val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val marker get() = connections.recoveryMarker

    /** Cold process barrier: replay a committed journal before any consumer can write old settings.
     * Normal startup does not need to query Room, initialize the Keystore or contact a service. */
    fun initialize() { if (marker.exists()) runBlocking { recover() } }

    private suspend fun <T> locked(action: suspend () -> T) = connections.exclusive { DevicePreferenceGate.exclusive(action) }
    private suspend fun row() = dao.active() ?: BackupDeviceRow()
    private fun preferences() = DevicePreferences(assistant.enabled, AppLocales.choice(context), updates.logicalPreferences(), BackupWidgetTemplates.snapshot(context))

    private suspend fun members(parts: Set<BackupPart>, includeKey: Boolean): DeviceMembers {
        val prefs = if (BackupPart.PREFERENCES in parts) archiveJson.encodeToString(preferences()).encodeToByteArray() else null
        val provider = if (BackupPart.CONNECTION in parts) {
            val active = connections.current(row().connectionGeneration)
            val profile = active?.profile?.let { ProviderFields(it.baseUrl, it.model, it.supportsImages) }
            ProviderMember(active != null, profile, if (includeKey) active?.key else null)
        } else null
        val wire = provider?.let { providerJson.encodeToString(it).encodeToByteArray() }
        check((prefs?.size ?: 0) <= DEVICE_MEMBER_LIMIT && (wire?.size ?: 0) <= DEVICE_MEMBER_LIMIT)
        return DeviceMembers(prefs, wire, if (provider?.configured == true) 1 else 0, provider?.key != null)
    }

    suspend fun snapshot(parts: Set<BackupPart>, includeKey: Boolean = true): DeviceMembers = locked {
        if (marker.exists()) recoverLocked()
        members(parts, includeKey)
    }
    suspend fun <T> withSnapshot(parts: Set<BackupPart>, includeKey: Boolean = true, block: suspend (DeviceMembers) -> T): T = locked {
        if (marker.exists()) recoverLocked()
        val snapshot = members(parts, includeKey)
        try { block(snapshot) } finally { snapshot.connection?.fill(0) }
    }

    fun read(preferences: ByteArray?, connection: ByteArray?): IncomingDevice {
        val prefs = preferences?.let { decodeMember<DevicePreferences>(it, archiveJson) }
        val provider = connection?.let { decodeMember<ProviderMember>(it, providerJson) }
        try {
            provider?.profile?.verified()?.let { profile -> provider.key?.let { ServiceConnection(profile, it) } }
        } catch (_: Exception) { throw invalidMember() }
        return IncomingDevice(prefs, provider)
    }

    private class Intent(val review: DeviceReview, val resolved: ServiceConnection?, val baseline: DevicePreferences?)
    private fun intent(incoming: IncomingDevice, row: BackupDeviceRow): Intent {
        val counts = mutableMapOf<BackupPart, BackupCounts>()
        val conflicts = mutableListOf<String>(); val impacts = mutableListOf<String>()
        val digest = MessageDigest.getInstance("SHA-256").apply { update(salt) }
        fun field(value: String?) {
            if (value != null) { val bytes = value.encodeToByteArray(); try { digest.update(bytes) } finally { bytes.fill(0) } }
            digest.update(0.toByte())
        }
        field(row.connectionEpoch.toString()); field(row.connectionGeneration)
        var resolved: ServiceConnection? = null
        incoming.provider?.let { member ->
            val profile = member.profile?.verified()
            field("connection"); field(profile?.baseUrl); field(profile?.model); field(profile?.supportsImages?.toString()); field(member.key)
            // Explicitly clearing a lost/damaged credential must not require decrypting it.
            val previous = if (profile == null) null else connections.current(row.connectionGeneration)
            val wasConfigured = previous != null || (profile == null && connections.hasActiveFile(row.connectionGeneration))
            if (profile != null) {
                val key = member.key ?: previous?.takeIf { it.profile.origin == profile.origin }?.key
                if (key == null) conflicts += uiText("This backup omits the key and this device has no key for the same HTTPS origin. Exclude the connection or use a backup with its key.", "备份未含密钥，设备没有同源 HTTPS 服务的密钥。请取消连接分类或使用含密钥的备份。")
                else resolved = ServiceConnection(profile, key)
                impacts += if (member.key != null) uiText("Includes a readable API key; it will be encrypted by this device.", "包含可读 API 密钥，将使用此设备的密钥库重新加密。")
                    else uiText("Key omitted; only an existing same-origin key can be kept.", "未包含密钥；只可保留设备上同源服务的密钥。")
            } else impacts += uiText("The selected empty connection clears this device’s connection.", "选中的空连接将清除本机连接。")
            counts[BackupPart.CONNECTION] = when {
                previous == null && profile != null -> BackupCounts(added = 1)
                wasConfigured && profile == null -> BackupCounts(removed = 1)
                profile != previous?.profile || (member.key != null && member.key != previous?.key) -> BackupCounts(updated = 1)
                else -> BackupCounts()
            }
        }
        val baseline = incoming.preferences?.let { prefs ->
            val current = preferences()
            field("preferences"); field(DevicePreferenceGate.epoch.toString()); field(archiveJson.encodeToString(current)); field(archiveJson.encodeToString(prefs))
            counts[BackupPart.PREFERENCES] = BackupCounts(updated = if (current == prefs) 0 else 1)
            impacts += uiText("Device preferences change after confirmation. No Android permission or installation session is restored.", "设备偏好将在确认后生效，不恢复系统权限或安装会话。")
            if (prefs.widgets.isNotEmpty()) impacts += uiText("Re-add widgets to use their saved configuration; existing widgets stay unchanged.", "请重新添加小组件以使用保存的配置；已有小组件保持原状。")
            current
        }
        return Intent(DeviceReview(counts, digest.digest().archiveHex(), conflicts, impacts), resolved, baseline)
    }

    suspend fun preview(incoming: IncomingDevice): DeviceReview = locked {
        if (marker.exists()) recoverLocked()
        intent(incoming, row()).review
    }

    /** The callback's last durable operation is its own Room transaction. A rollback cannot publish
     * the staged generation. Recovery always reads Room, not a process-local 'committed' boolean. */
    suspend fun <T> withActivation(incoming: IncomingDevice, expectedStamp: String, block: suspend (DeviceActivation) -> T): T = locked {
        if (marker.exists()) recoverLocked()
        val before = row()
        val intent = intent(incoming, before)
        if (intent.review.stamp != expectedStamp || intent.review.conflicts.isNotEmpty()) throw DeviceStampChanged()
        val changes = incoming.preferences != null || incoming.provider != null
        if (changes) connections.beginPublication()
        val after = try { before.copy(
            connectionGeneration = if (incoming.provider != null) connections.stage(intent.resolved) else before.connectionGeneration,
            connectionEpoch = if (incoming.provider != null) Math.addExact(before.connectionEpoch, 1L) else before.connectionEpoch,
            pendingPreferences = incoming.preferences?.let {
                archiveJson.encodeToString(PreferenceJournal(it, BackupWidgetTemplates.existing(context), java.util.UUID.randomUUID().toString()))
            },
        ) } catch (e: Exception) {
            recoverLocked(); throw e
        }
        val activation = DeviceActivation {
            if (row() != before || (intent.baseline != null && preferences() != intent.baseline)) throw DeviceStampChanged()
            if (incoming.provider != null) {
                val verified = connections.current(after.connectionGeneration)
                check(verified?.profile == intent.resolved?.profile && verified?.key == intent.resolved?.key)
            }
            if (changes) dao.write(after)
        }
        try {
            val value = try { block(activation) } catch (failure: Throwable) {
                if (changes) withContext(NonCancellable) {
                    if (activation.called && row() == after) throw committedWarning()
                    recoverLocked()
                }
                throw failure
            }
            if (changes) try { withContext(NonCancellable) { recoverLocked() } } catch (_: Exception) {
                if (activation.called) throw committedWarning() else throw IllegalStateException("Device cleanup deferred")
            }
            value
        } finally { activation.open = false }
    }

    private fun committedWarning(): ActivationCommittedWarning {
        // A later ordinary edit must not be overwritten by a still-pending restore on next launch.
        DevicePreferenceGate.defer(marker) { runBlocking { recover() } }
        return ActivationCommittedWarning()
    }

    suspend fun recover() = locked { recoverLocked() }
    private suspend fun recoverLocked() {
        val active = row() // failure leaves all encrypted files and the marker intact
        active.pendingPreferences?.let { json ->
            val journal = archiveJson.decodeFromString<PreferenceJournal>(json)
            val prefs = journal.values
            // Each effect is idempotent. A crash before the acknowledgement repeats the full logical snapshot.
            updates.restorePreferences(prefs.updates)
            assistant.restore(prefs.assistantEnabled)
            BackupWidgetTemplates.restore(context, prefs.widgets, journal.existingWidgets, journal.activation)
            AppLocales.restore(context, prefs.language)
            dao.write(active.copy(pendingPreferences = null))
            DevicePreferenceGate.restored()
        }
        connections.collect(active.connectionGeneration)
        if (Files.deleteIfExists(marker.toPath())) syncDirectory(checkNotNull(marker.parentFile))
        DevicePreferenceGate.completed(marker)
    }
}
