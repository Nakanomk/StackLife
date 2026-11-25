package io.github.nakanomk.stacklife.archive

import io.github.nakanomk.stacklife.appearance.BackgroundStore
import io.github.nakanomk.stacklife.assistant.AssistantSessions
import io.github.nakanomk.stacklife.assistant.AttachmentStore
import io.github.nakanomk.stacklife.planner.privatePhotoId
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.*

private const val RECORD_LIMIT = 8L * 1024 * 1024
private const val DEVICE_LIMIT = 128L * 1024
private const val PREFERENCES = "preferences.json"
private const val CONNECTION = "connection.json"
private fun BackupPart.record() = "records/$wire.jsonl"
private val records = BackupPart.entries.filter { it != BackupPart.CONNECTION }.associateBy { it.record() }
internal object FullBackupLayout : ArchiveLayout {
    override val type = ArchiveType.BACKUP
    override fun validCounts(counts: Map<String, Int>) = counts.isNotEmpty() && counts.all { (wire, count) ->
        val part = BackupPart.byWire[wire]
        part != null && count in 0..(if (part == BackupPart.CONNECTION) 1 else 8192)
    }
    override fun limit(name: String): Long? = when {
        name == CONNECTION || name == PREFERENCES -> DEVICE_LIMIT
        name in records -> RECORD_LIMIT
        (name.startsWith("photos/") || name.startsWith("attachments/")) && privatePhotoId.matches(name.substringAfter('/')) -> 32L * 1024 * 1024
        else -> null
    }
}
internal data class BackupSelection(val parts: Set<BackupPart>, val photos: Int, val attachments: Int, val containsKey: Boolean)
internal data class BackupReview(val counts: Map<BackupPart, BackupCounts>, val photos: Int, val attachments: Int,
    val containsKey: Boolean, val conflicts: List<String>, val impacts: List<String>, val refreshed: Boolean)
internal data class BackupReceipt(val revision: Long, val warnings: List<String>)
private class SecretBuffer : ByteArrayOutputStream() { fun erase() { buf.fill(0); reset() } }
private class Capture(val tables: ExportedTables, val preferences: ByteArray?, val connection: ByteArray?, val connectionCount: Int)

/** TasksJsonExporter: explicit parent records, related records and portable members, never db/wal/preferences files.
 * BiliPai SAF transfer: parse/validate first, replace only on a separate confirmation. Device and relational
 * snapshots share one acquisition order. No file lock, network call or user interaction inside the Room writer. */
internal class FullBackup(private val planner: RoomPlannerApi, private val photos: BackgroundStore,
    private val attachments: AttachmentStore, private val device: BackupDevice, private val sessions: AssistantSessions,
    private val cache: File, private val preferencesActivated: () -> Unit = {}) {
    private val settled = kotlinx.coroutines.flow.MutableStateFlow<BackupReceipt?>(null)
    val lastRestore: kotlinx.coroutines.flow.StateFlow<BackupReceipt?> = settled
    suspend fun export(selection: Set<BackupPart>, includeKey: Boolean, open: () -> OutputStream) = withContext(Dispatchers.IO) {
        val parts = selection.toSet(); require(parts.isNotEmpty())
        val context = currentCoroutineContext()
        ArchiveStaging.use(cache, ArchiveType.BACKUP) { stage ->
            val directory = File(stage, "records").also { check(it.mkdir()) }
            photos.withArchiveLease { backgrounds -> attachments.withArchiveLease { media ->
                var secret: ByteArray? = null
                try {
                    val (captureAndFiles, images) = backgrounds.snapshotFiles({ media.snapshotFiles({
                        device.withSnapshot(parts, includeKey) { state ->
                            val tables = planner.exportBackupTables(parts, directory)
                            secret = state.connection?.copyOf()
                            Capture(tables, state.preferences, secret, state.connectionCount)
                        }
                    }, { it.tables.files.attachments }) }, { it.first.tables.files.backgrounds })
                    val (capture, files) = captureAndFiles
                    val counts = capture.tables.counts.mapKeys { it.key.wire }.toMutableMap()
                    if (BackupPart.CONNECTION in parts) counts[BackupPart.CONNECTION.wire] = capture.connectionCount
                    check(counts.keys == parts.mapTo(HashSet()) { it.wire })
                    val sources = buildList {
                        for (part in parts) if (part != BackupPart.CONNECTION) add(ArchiveSource(part.record()) { File(directory, "${part.wire}.jsonl").inputStream() })
                        if (BackupPart.PREFERENCES in parts) add(ArchiveSource(PREFERENCES) { checkNotNull(capture.preferences).inputStream() })
                        if (BackupPart.CONNECTION in parts) add(ArchiveSource(CONNECTION) { checkNotNull(capture.connection).inputStream() })
                        images.forEach { (id, file) -> add(ArchiveSource("photos/$id") { file.inputStream() }) }
                        files.forEach { (id, file) -> add(ArchiveSource("attachments/$id") { file.inputStream() }) }
                    }
                    ArchiveCodec.write(FullBackupLayout, counts, sources, open()) { context.ensureActive() }
                } finally { secret?.fill(0) }
            } }
        }
    }

    /** All supplied categories and their closure are checked, even for a partial restore. The subset is never inferred. */
    suspend fun restore(open: () -> InputStream, select: suspend (BackupSelection) -> Set<BackupPart>?,
        confirm: suspend (BackupReview) -> Boolean): BackupReceipt? = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        ArchiveStaging.use(cache, ArchiveType.BACKUP) { dir ->
            val directory = File(dir, "records").also { check(it.mkdir()) }; val binaries = HashMap<String, File>()
            val preferences = ByteArrayOutputStream(); val key = SecretBuffer(); var connectionBytes: ByteArray? = null
            try {
                val manifest = ArchiveCodec.read(FullBackupLayout, open(), { name -> when (name) {
                    CONNECTION -> key
                    PREFERENCES -> preferences
                    in records -> File(directory, "${records.getValue(name).wire}.jsonl").outputStream()
                    else -> File(dir, "blob-${binaries.size}").also { binaries[name] = it }.outputStream()
                } }) { context.ensureActive() }
                val available = manifest.counts.keys.mapTo(LinkedHashSet(), BackupPart.byWire::getValue)
                val members = manifest.members.associateBy { it.name }
                val expected = available.filter { it != BackupPart.CONNECTION }.mapTo(HashSet()) { it.record() }
                if (BackupPart.CONNECTION in available) expected.add(CONNECTION)
                if (BackupPart.PREFERENCES in available) expected.add(PREFERENCES)
                require(members.keys - binaries.keys == expected)
                val all = planner.readBackupTables(available, directory)
                val prefs = if (BackupPart.PREFERENCES in available) preferences.toByteArray() else null
                connectionBytes = if (BackupPart.CONNECTION in available) key.toByteArray() else null; key.erase()
                val deviceImage = device.read(prefs, connectionBytes)
                val counts = all.counts.mapKeys { it.key.wire }.toMutableMap()
                if (BackupPart.CONNECTION in available) counts[BackupPart.CONNECTION.wire] = deviceImage.connectionCount
                require(counts == manifest.counts)
                val resources = all.files
                require(binaries.keys == resources.backgrounds.map { "photos/$it" }.toSet() + resources.attachments.keys.map { "attachments/$it" })
                for ((id, attachment) in resources.attachments) require(attachment.bytes == members.getValue("attachments/$id").bytes)
                val parts = select(BackupSelection(available, resources.backgrounds.size, resources.attachments.size, deviceImage.hasKey))?.toSet() ?: return@use null
                require(parts.isNotEmpty() && available.containsAll(parts))
                val tables = all.select(parts)
                val local = if (parts == available) deviceImage else device.read(prefs.takeIf { BackupPart.PREFERENCES in parts }, connectionBytes.takeIf { BackupPart.CONNECTION in parts })
                // The selected blobs undergo the same format checks at publication. Validate everything
                // else without registering an owner, revealing a key or writing to either durable store.
                for (id in resources.backgrounds - tables.files.backgrounds) {
                    context.ensureActive(); photos.validateArchiveFile(id, binaries.getValue("photos/$id"))
                }
                for ((id, item) in resources.attachments) if (id !in tables.files.attachments) {
                    context.ensureActive(); attachments.validateArchiveFile(item, binaries.getValue("attachments/$id"))
                }
                photos.withArchiveLease { bg -> attachments.withArchiveLease { media ->
                    val photoIds = tables.files.backgrounds.associateWith { id -> val name = "photos/$id"; bg.publish(id, binaries.getValue(name), members.getValue(name).sha256) }
                    val mediaIds = tables.files.attachments.mapValues { (id, item) -> val name = "attachments/$id"; media.publish(item, binaries.getValue(name), members.getValue(name).sha256) }
                    applyReviewed(parts, tables, local, photoIds, mediaIds, confirm)
                } }
            } finally { key.erase(); connectionBytes?.fill(0) }
        }?.let { receipt -> withContext(NonCancellable) {
            val result = try { attachments.collect(); receipt } catch (_: Exception) {
                receipt.copy(warnings = receipt.warnings + io.github.nakanomk.stacklife.i18n.uiText("Attachment cleanup is pending.", "附件待清理。"))
            }
            settled.value = result
            result
        } }
    }

    private suspend fun applyReviewed(parts: Set<BackupPart>, tables: io.github.nakanomk.stacklife.storage.IncomingTables,
        local: IncomingDevice, photos: Map<String, String>, files: Map<String, String>, confirm: suspend (BackupReview) -> Boolean): BackupReceipt? {
        var refreshed = false
        while (true) {
            val data = planner.planBackupTables(tables, photos, files)
            val settings = device.preview(local)
            val changes = parts.associateWith { (data.counts[it] ?: BackupCounts()) + (settings.counts[it] ?: BackupCounts()) }
            val review = BackupReview(changes, photos.size, files.size, local.hasKey,
                data.conflicts + settings.conflicts, data.impacts + settings.impacts, refreshed)
            if (!confirm(review)) return null
            check(review.conflicts.isEmpty())
            try {
                return withContext(NonCancellable) { sessions.withPaused {
                    var installed: Long? = null
                    var activationWarning = false
                    try { device.withActivation(local, settings.stamp) { activation ->
                        installed = planner.replaceBackupTables(tables, photos, files, data.stamp, activation)
                    } } catch (warning: ActivationCommittedWarning) { if (installed == null) throw warning; activationWarning = true }
                    val warnings = planner.afterBackup(activationWarning)
                    val preferenceWarning = if (BackupPart.PREFERENCES in parts) try {
                        withContext(Dispatchers.Main.immediate) { preferencesActivated() }; null
                    } catch (_: Exception) { io.github.nakanomk.stacklife.i18n.uiText("Device preferences will refresh after restart.", "设备偏好将在重启后刷新。") } else null
                    BackupReceipt(checkNotNull(installed), if (preferenceWarning == null) warnings else warnings + preferenceWarning)
                } }
            } catch (_: DeviceStampChanged) { refreshed = true }
              catch (_: BackupRevisionChanged) { refreshed = true }
        }
    }
}
internal class BackupRevisionChanged : IllegalStateException()
