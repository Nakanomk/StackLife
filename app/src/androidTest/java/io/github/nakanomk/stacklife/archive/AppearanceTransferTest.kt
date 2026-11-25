package io.github.nakanomk.stacklife.archive

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.appearance.BackgroundStore
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.storage.PlannerDatabase
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceTransferTest {
    /** Independent named Room DB and private image root for each source/target, even on reopen. */
    private class Device : Closeable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "transfer-test-").toFile()
        val app = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        val name = "appearance-transfer-${UUID.randomUUID()}.db"
        lateinit var db: PlannerDatabase
        lateinit var api: PlannerApi
        lateinit var photos: BackgroundStore
        val transfer get() = AppearanceArchive(api, photos, root)
        init { reopen() }
        fun reopen() {
            if (::db.isInitialized) db.close()
            db = PlannerDatabase.open(context, name)
            photos = BackgroundStore(app) {
                settings().appearance.backgroundIds() + countdowns().mapNotNull { it.backgroundId }
            }
            api = RoomPlannerApi(db, imageExists = photos::exists, collectBackgrounds = photos::collect)
        }
        suspend fun settings() = api.observe(PlannerQuery.Settings).first() as PolicyView
        suspend fun workspace() = api.observe(PlannerQuery.Workspace).first() as Workspace
        suspend fun countdowns() = (api.observe(PlannerQuery.Countdowns).first() as CountdownsView).items
        suspend fun apply(vararg commands: PlannerCommand) {
            val ready = api.preview(commands.toList()) as PreviewResult.Ready
            assertTrue(api.commit(ready.proposalId) is CommitResult.Applied)
        }
        suspend fun export() = ByteArrayOutputStream().also { stream -> transfer.export { stream } }.toByteArray()
        suspend fun BackgroundStore.ArchiveLease.photo(id: String, color: Int): String {
            val file = File.createTempFile("image-", ".png", root)
            val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color); file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                return publish(id, file, MessageDigest.getInstance("SHA-256").digest(file.readBytes()).archiveHex())
            } finally { bitmap.recycle(); file.delete() }
        }
        fun photoFiles() = File(root, "background-images").list().orEmpty().toSet()
        suspend fun color(id: String): Int = photos.bitmap(id, 64).let { try { it.getPixel(0, 0) } finally { it.recycle() } }
        override fun close() { db.close(); context.deleteDatabase(name); root.deleteRecursively() }
    }
    private val global = "01010101-0101-0101-0101-010101010101"
    private val perPage = "02020202-0202-0202-0202-020202020202"
    private suspend fun Device.source(): Appearance {
        val value = Appearance(theme = ThemeMode.DARK, compact = true, background = WallpaperSpec(global, 1.3f, .2f, -.2f, .4f, blur = .15f),
            pageBackgrounds = mapOf(Destination.COURSES to WallpaperSpec(perPage, scale = 2f), Destination.TODAY to WallpaperSpec(global, offsetY = .4f)))
        photos.withArchiveLease { lease ->
            lease.photo(global, Color.RED); lease.photo(perPage, Color.GREEN)
            apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(value)), PlannerCommand.TaskAdd(TaskInput("private donor task", "private donor note")),
                PlannerCommand.KeepRestoredFrames(true))
        }
        return value
    }
    @Test fun fullAppearanceAndPerPageFilesRoundTripWithoutChangingOtherOwnersAcrossReopen() = runBlocking {
        Device().use { donor -> Device().use { target ->
            val expected = donor.source(); val packed = donor.export()
            val contents = linkedMapOf<String, ByteArrayOutputStream>()
            ArchiveCodec.read(AppearanceLayout, packed.inputStream(), { name -> ByteArrayOutputStream().also { contents[name] = it } })
            assertEquals(setOf("appearance/settings.json", "appearance/photos/$global", "appearance/photos/$perPage"), contents.keys)
            assertEquals(expected, archiveJson.decodeFromString<Appearance>(contents.getValue("appearance/settings.json").toString("UTF-8")))
            target.photos.withArchiveLease { lease ->
                with(target) { lease.photo(global, Color.BLUE) }
                target.apply(PlannerCommand.CountdownAdd("private target event", 20000, backgroundId = global),
                    PlannerCommand.TaskAdd(TaskInput("private target task")), PlannerCommand.SetRestorePolicy(RestorePolicy.SWAP))
            }
            val before = target.settings(); val days = target.countdowns(); val tasks = target.workspace().queue
            val result = target.transfer.restore({ packed.inputStream() }) { preview ->
                assertEquals(2, preview.photos); assertTrue(preview.differences.any { it.key == "pageBackgrounds" }); true
            }
            assertEquals(AppearanceImport.APPLIED, result)
            target.reopen(); target.photos.recover(); val restored = target.settings().appearance
            val actualId = restored.background.imageId!!; assertNotEquals(global, actualId)
            assertEquals(expected.copy(background = expected.background.copy(imageId = actualId), pageBackgrounds =
                expected.pageBackgrounds + (Destination.TODAY to expected.pageBackgrounds.getValue(Destination.TODAY).copy(imageId = actualId))), restored)
            assertEquals(Color.RED, target.color(actualId)); assertEquals(Color.GREEN, target.color(perPage)); assertEquals(Color.BLUE, target.color(global))
            assertEquals(days, target.countdowns()); assertEquals(tasks, target.workspace().queue); assertEquals(before.copy(appearance = restored), target.settings())
        } }
    }
    @Test fun cancellationConflictAndForeignFieldsLeaveNoOrphanOrUnconfirmedRevision() = runBlocking {
        Device().use { donor -> Device().use { target ->
            val expected = donor.source(); val packed = donor.export(); val initial = target.workspace(); val original = target.settings()
            val previewed = CompletableDeferred<Unit>()
            val reading = async { target.transfer.restore({ packed.inputStream() }) {
                previewed.complete(Unit); kotlinx.coroutines.awaitCancellation()
            } }
            previewed.await(); target.photos.collect(); assertEquals(setOf(global, perPage), target.photoFiles())
            reading.cancelAndJoin(); assertEquals(emptySet<String>(), target.photoFiles()); assertEquals(initial, target.workspace())
            var reviews = 0
            val cancelled = target.transfer.restore({ packed.inputStream() }) { review ->
                if (++reviews == 1) { target.apply(PlannerCommand.TaskAdd(TaskInput("another local edit"))); true }
                else { assertTrue(review.refreshed); false }
            }
            assertEquals(2, reviews); assertEquals(AppearanceImport.CANCELLED, cancelled); val after = target.workspace()
            assertEquals(initial.revision + 1, after.revision); assertEquals(original, target.settings()); assertEquals(emptySet<String>(), target.photoFiles())
            val invalid = ByteArrayOutputStream().also { out ->
                val body = archiveJson.encodeToString(expected).dropLast(1) + ",\"apiKey\":\"synthetic bogus field\"}"
                ArchiveCodec.write(AppearanceLayout, mapOf("appearance" to 1), listOf(ArchiveSource("appearance/settings.json") { body.byteInputStream() }), out)
            }.toByteArray()
            for (broken in listOf(invalid, packed.copyOf(packed.size - 2))) {
                try { target.transfer.restore({ broken.inputStream() }) { fail("Invalid package reached confirmation"); true }; fail() }
                catch (_: IllegalArgumentException) { }
            }
            target.reopen(); target.photos.recover()
            assertEquals(after, target.workspace()); assertEquals(original, target.settings()); assertEquals(emptySet<String>(), target.photoFiles())
        } }
    }
}
