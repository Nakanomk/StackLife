package io.github.nakanomk.stacklife.archive

import io.github.nakanomk.stacklife.appearance.BackgroundStore
import io.github.nakanomk.stacklife.planner.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val RECORD = "appearance/settings.json"
private const val PHOTOS = "appearance/photos/"
internal object AppearanceLayout : ArchiveLayout {
    override val type = ArchiveType.APPEARANCE
    override fun validCounts(counts: Map<String, Int>) = counts == mapOf("appearance" to 1)
    override fun limit(name: String) = when {
        name == RECORD -> 64L * 1024
        name.startsWith(PHOTOS) && privatePhotoId.matches(name.substring(PHOTOS.length)) -> 32L * 1024 * 1024
        else -> null
    }
}
internal data class AppearanceReview(val differences: List<AppearanceDifference>, val photos: Int, val refreshed: Boolean)
internal enum class AppearanceImport { APPLIED, UNCHANGED, CANCELLED }
internal class ArchivePlannerFailure(message: String) : IOException(message)

/** Only typed Appearance records and their actual image closure cross this boundary. */
internal class AppearanceArchive(private val planner: PlannerApi, private val photos: BackgroundStore, private val cache: File) {
    private suspend fun current() = (planner.observe(PlannerQuery.Settings).first() as PolicyView).appearance

    suspend fun export(open: () -> OutputStream) = photos.withArchiveLease { lease ->
        val (value, files) = lease.snapshotFiles(::current, Appearance::backgroundIds)
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext(); val record = archiveJson.encodeToString(value).encodeToByteArray()
            val members = buildList {
                add(ArchiveSource(RECORD) { record.inputStream() })
                files.forEach { (id, file) -> add(ArchiveSource(PHOTOS + id) { file.inputStream() }) }
            }
            ArchiveCodec.write(AppearanceLayout, mapOf("appearance" to 1), members, open()) { job.ensureActive() }
        }
    }

    suspend fun restore(open: () -> InputStream, review: suspend (AppearanceReview) -> Boolean): AppearanceImport =
        photos.withArchiveLease { lease ->
            val value = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                ArchiveStaging.use(cache, ArchiveType.APPEARANCE) { dir ->
                    val files = linkedMapOf<String, File>()
                    val record = ByteArrayOutputStream()
                    val manifest = ArchiveCodec.read(AppearanceLayout, open(), { name ->
                        // Never use user-provided paths on the filesystem, even after allowlisting.
                        if (name == RECORD) record else {
                            require(files.size < 1 + Destination.entries.size)
                            File(dir, files.size.toString()).also { files[name] = it }.outputStream()
                        }
                    }) { job.ensureActive() }
                    val text = record.toByteArray().decodeToString(throwOnInvalidSequence = true)
                    val imported = archiveJson.decodeFromString<Appearance>(text)
                    // The v1 DTO is the full, canonical record. No duplicate/unknown properties or
                    // partially populated inputs that silently apply current-version defaults.
                    require(archiveJson.encodeToString(imported) == text)
                    val ids = imported.backgroundIds()
                    require(files.keys == ids.mapTo(mutableSetOf()) { PHOTOS + it })
                    val replacements = ids.associateWith { id ->
                        val name = PHOTOS + id
                        lease.publish(id, files.getValue(name), manifest.members.single { it.name == name }.sha256)
                    }
                    fun WallpaperSpec.remap() = imageId?.let { copy(imageId = replacements.getValue(it)) } ?: this
                    imported.copy(background = imported.background.remap(), pageBackgrounds = imported.pageBackgrounds.mapValues { it.value.remap() })
                }
            }
            reviewedReplacement(value, review)
        }

    private suspend fun reviewedReplacement(value: Appearance, review: suspend (AppearanceReview) -> Boolean): AppearanceImport {
        var refreshed = false
        while (true) {
            val baseline = current()
            if (baseline == value) return AppearanceImport.UNCHANGED
            val differences = appearanceDifferences(baseline, value)
            check(differences.isNotEmpty())
            val preview = planner.preview(listOf(PlannerCommand.EditAppearance(AppearancePatch.Replace(value), expected = baseline)))
            if (preview is PreviewResult.Rejected) {
                if (current() != baseline) { refreshed = true; continue }
                throw ArchivePlannerFailure(preview.message)
            }
            preview as PreviewResult.Ready
            try {
                if (!review(AppearanceReview(differences, value.backgroundIds().size, refreshed))) return AppearanceImport.CANCELLED
                when (val result = withContext(NonCancellable) { planner.commit(preview.proposalId) }) {
                    is CommitResult.Applied -> return AppearanceImport.APPLIED
                    is CommitResult.Rejected -> if (result.code == CommitFailure.STALE_PREVIEW) refreshed = true
                        else throw ArchivePlannerFailure(result.message)
                }
            } finally { planner.discard(preview.proposalId) }
        }
    }
}
