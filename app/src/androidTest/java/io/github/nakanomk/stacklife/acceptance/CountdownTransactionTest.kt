package io.github.nakanomk.stacklife.acceptance

import android.content.ContentValues
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.appearance.BackgroundStore
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountdownTransactionTest {
    @get:Rule val store = WorkspaceFixture()
    private val day = LocalDate.of(2028, 2, 29)
    private val date = day.toEpochDay()
    private suspend fun list() = store.api.observe(PlannerQuery.Countdowns).first() as CountdownsView

    @Test fun mixedBatchMergesChangesByIdentityAndKeepsBothGroupsDenseAcrossReopen() = runBlocking {
        store.apply(TaskAdd(TaskInput("Keep")), CountdownAdd("A", date), CountdownAdd("B", date), CountdownAdd("C", date),
            CountdownAdd("D", date, pinned = true), CountdownAdd("E", date, pinned = true))
        val initial = list(); val byTitle = initial.items.associateBy { it.title }
        fun id(s: String) = byTitle.getValue(s).id
        val queue = store.state().queue
        val query = PlannerQuery.Day(day, ZoneId.of("America/New_York"))
        suspend fun occurrences() = (store.api.observe(query).first() as DayPlan).occurrences
        val events = occurrences()
        val abandoned = store.proposal(CountdownEdit(id("A"), title = "Unsaved"))
        store.api.discard(abandoned.proposalId); assertEquals(initial, list())
        val stale = store.proposal(CountdownRemove(id("D"), initial.revision))
        val ready = store.proposal(CountdownSetPinned(id("B"), true), CountdownRemove(id("D")),
            CountdownAdd("Transient name", date, ref = "f"), CountdownEdit("@f", title = "F", mode = CountdownMode.ACCUMULATE),
            CountdownOrder(true, listOf(id("B"), id("E"))), CountdownOrder(false, listOf("@f", id("C"), id("A"))),
            CountdownEdit(id("C"), targetDay = date + 1))
        assertEquals(initial, list()); assertEquals(ready.changes.size, ready.changes.distinctBy { it.entityId }.size)
        val changes = ready.changes.associateBy { it.entityId }
        val f = ready.refs.getValue("f")
        assertNull(changes.getValue(f).before); assertNull(changes.getValue(id("D")).after)
        assertEquals(byTitle.getValue("C"), (changes.getValue(id("C")).before as CountdownSnapshot).value)
        assertTrue(store.api.commit(ready.proposalId) is CommitResult.Applied)
        val actual = list(); assertEquals(listOf("B", "E", "F", "C", "A"), actual.items.map { it.title })
        assertEquals(listOf(0, 1, 0, 1, 2), actual.items.map { it.position })
        actual.items.forEach { v -> changes[v.id]?.let { assertEquals(v, (it.after as CountdownSnapshot).value) } }
        assertEquals(CommitFailure.STALE_PREVIEW, (store.api.commit(stale.proposalId) as CommitResult.Rejected).code)
        for (bad in listOf(CountdownOrder(false, listOf(f, f, id("C"))), CountdownOrder(false, listOf(f, id("A"))),
            CountdownOrder(false, listOf(id("B"), f, id("C"))), CountdownEdit(f, targetDay = Long.MAX_VALUE),
            CountdownEdit(f, maskOpacity = Float.NaN), CountdownEdit(f, title = " "), CountdownEdit(f, maskOpacity = 1.1f),
            CountdownEdit(f, background = BackgroundChange("../photo")), CountdownSetPinned("unknown", true), CountdownRemove(f, initial.revision))) {
            assertTrue("$bad", store.api.preview(listOf(CountdownRemove(id("E")), bad)) is PreviewResult.Rejected)
        }
        store.reopen(); assertEquals(actual, list()); assertEquals(queue, store.state().queue); assertEquals(events, occurrences())
        assertEquals(actual.items[2], (store.api.observe(PlannerQuery.Countdown(f)).first() as CountdownView).value)
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun missingPhotoAtReceiptRejectsWholeBatchAndSharedPrivatePhotoOutlivesOneCard() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "countdown-test-${UUID.randomUUID()}")
        val local = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }
        val photos = BackgroundStore(local) { store.db.countdownDao().backgroundIds().toSet() }
        val api = RoomPlannerApi(store.db, imageExists = photos::exists, collectBackgrounds = photos::collect)
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "countdown-${UUID.randomUUID()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            val bitmap = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(android.graphics.Color.BLUE); requireNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            val draft = photos.importImage(uri)
            val preview = api.preview(listOf(CountdownAdd("Before", date), CountdownAdd("Photo", date, backgroundId = draft))) as PreviewResult.Ready
            photos.release(draft); assertFalse(photos.exists(draft))
            assertTrue(api.commit(preview.proposalId) is CommitResult.Rejected); assertTrue(list().items.isEmpty())
            val shared = photos.importImage(uri)
            suspend fun apply(vararg cmd: PlannerCommand) {
                val p = api.preview(cmd.toList()) as PreviewResult.Ready; assertTrue(api.commit(p.proposalId) is CommitResult.Applied)
            }
            apply(CountdownAdd("One", date, backgroundId = shared), CountdownAdd("Two", date, backgroundId = shared))
            photos.release(shared); assertTrue(photos.exists(shared))
            val (one, two) = list().items
            val replacement = photos.importImage(uri)
            val cancelled = api.preview(listOf(CountdownEdit(one.id, background = BackgroundChange(replacement)))) as PreviewResult.Ready
            api.discard(cancelled.proposalId); photos.release(replacement)
            assertFalse(photos.exists(replacement)); assertEquals(shared, list().items.first().backgroundId)
            apply(CountdownRemove(one.id)); assertTrue(photos.exists(shared))
            apply(CountdownEdit(two.id, background = BackgroundChange(null))); assertFalse(photos.exists(shared))
            assertNull(list().items.single().backgroundId)
        } finally { resolver.delete(uri, null, null); folder.deleteRecursively() }
    }
}
