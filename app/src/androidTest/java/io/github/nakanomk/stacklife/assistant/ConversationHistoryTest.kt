package io.github.nakanomk.stacklife.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.acceptance.WorkspaceFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationHistoryTest {
    @get:Rule val store = WorkspaceFixture()

    @Test fun imageReferenceInsertRollsBackAsAUnitAndRecoveryOnlyUnlinksOrphans() = runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "history-images-${java.util.UUID.randomUUID()}"
        fun files() = AttachmentStore(context, { store.db.conversationHistory().attachmentIds().toSet() }, directory)
        val files = files()
        val history = store.db.conversationHistory()
        try {
            files.recover()
            fun png(width: Int): ChatImage {
                val id = java.util.UUID.randomUUID().toString()
                val file = files.file(id)
                val bitmap = android.graphics.Bitmap.createBitmap(width, 10, android.graphics.Bitmap.Config.ARGB_8888)
                try { file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
                return ChatImage(id, "image/png", width, 10, file.length())
            }
            val first = png(18)
            val second = png(24)
            val sent = history.sendUser(null, "Today", "", null, listOf(second, first))
            val original = history.observeConversations().first()
            val orphan = png(8)
            val failed = runCatching { history.sendUser(null, "Today", "", null, listOf(orphan, first)) }.exceptionOrNull()
            assertTrue("Duplicate image identity must reject the entire transaction", failed != null)
            assertEquals(original, history.observeConversations().first())
            assertEquals(setOf(first.id, second.id), history.attachmentIds().toSet())
            assertTrue(history.recentDialogue(sent.conversationId, chatContextLimit).isEmpty())
            assertTrue(files.file(orphan.id).isFile)
            store.reopen()
            val recovered = files()
            recovered.recover()
            assertTrue(!recovered.file(orphan.id).exists())
            assertEquals(listOf(second, first), store.db.conversationHistory().observeMessages(sent.conversationId).first().single().attachments)
            assertEquals(18, recovered.bitmap(first, 128).let { bitmap -> try { bitmap.width } finally { bitmap.recycle() } })
            assertTrue(runCatching { recovered.remove(first.id) }.isFailure)
        } finally { java.io.File(context.noBackupFilesDir, directory).deleteRecursively() }
    }

    @Test fun directRemovalWhilePreparingCannotPublishALateThumbnailOrRef() = runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "canceled-images-${java.util.UUID.randomUUID()}"
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val canceled = kotlinx.coroutines.CompletableDeferred<Unit>()
        val files = AttachmentStore(context, {
            entered.complete(Unit)
            try { kotlinx.coroutines.awaitCancellation() } finally { canceled.complete(Unit) }
        }, directory)
        val job = kotlinx.coroutines.SupervisorJob()
        val draft = ChatDraft(files, kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.Main.immediate))
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                draft.add(listOf(AttachmentSource.Photo(android.net.Uri.parse("content://unused/selection"))))
            }
            kotlinx.coroutines.withTimeout(5_000) { entered.await() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { draft.remove(draft.items.single()) }
            kotlinx.coroutines.withTimeout(5_000) { canceled.await() }
            assertTrue(draft.items.isEmpty()); assertTrue(!draft.sendable)
            assertTrue(java.io.File(context.noBackupFilesDir, directory).listFiles().orEmpty().isEmpty())
            assertTrue(store.db.conversationHistory().attachmentIds().isEmpty())
        } finally { job.cancel(); java.io.File(context.noBackupFilesDir, directory).deleteRecursively() }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun coldMixedOwnershipSurvivesLostGrantsAndDeletionRemovesBothTypes() = runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "typed-history-${java.util.UUID.randomUUID()}"
        fun files() = AttachmentStore(context, { store.db.conversationHistory().attachmentIds().toSet() }, directory)
        val files = files()
        try {
            val text = "周三,15:45\n"
            val doc = withChatDocument(context, "csv", "text/csv", text.encodeToByteArray()) { files.prepareDocument(it, false) }
            lateinit var photo: ChatImage
            withChatPhoto(context, 180) { photo = runBlocking { files.prepare(it) } }
            val other = withChatDocument(context, "txt", "text/plain", "end".encodeToByteArray()) { files.prepareDocument(it, false) }
            val order = listOf(doc, photo, other)
            val sent = store.db.conversationHistory().sendUser(null, "Today", "", null, order)
            val orphan = withChatDocument(context, "md", "text/markdown", "# note".encodeToByteArray()) { files.prepareDocument(it, false) }
            store.reopen()
            val history = store.db.conversationHistory()
            val cold = files().also { it.recover() }
            assertEquals(order, history.observeMessages(sent.conversationId).first().single().attachments)
            assertTrue(history.recentDialogue(sent.conversationId, chatContextLimit).isEmpty())
            assertEquals(text, doc.readValidated(cold.file(doc.id)))
            assertTrue(!cold.file(orphan.id).exists())
            assertTrue(runCatching { cold.remove(doc.id) }.isFailure)
            val removed = history.delete(sent.conversationId)
            assertEquals(order.map { it.id }.toSet(), removed.toSet())
            removed.forEach { cold.remove(it) }
            assertTrue(history.attachmentIds().isEmpty())
            assertTrue(order.none { cold.file(it.id).exists() })
        } finally { java.io.File(context.noBackupFilesDir, directory).deleteRecursively() }
    }

    @Test fun recentActivityAndFullTranscriptSurviveReopenButDeletedMessagesDoNot() = runBlocking {
        var history = store.db.conversationHistory()
        val prompt = "先安排上午的任务。".repeat(20)
        val a = history.sendUser(null, "今天", prompt, null, emptyList()).conversationId
        val b = history.sendUser(null, "课表", "周五有哪些空档？", null, emptyList()).conversationId
        assertEquals(listOf(b, a), history.observeConversations().first().map { it.id })
        val expected = mutableListOf(HistoryRole.USER to prompt)
        repeat(12) { turn ->
            expected += HistoryRole.ASSISTANT to "第 ${turn + 1} 次答复"
            expected += HistoryRole.USER to "继续第 ${turn + 2} 轮"
        }
        expected += HistoryRole.NOTICE to "尚未写入：报告移至 16:00"
        expected += HistoryRole.NOTICE to "已应用：报告移至 16:00"
        for ((role, text) in expected.drop(1)) history.append(a, role, text)
        store.reopen()
        history = store.db.conversationHistory()
        val rows = history.observeConversations().first()
        assertEquals(listOf(a, b), rows.map { it.id })
        assertEquals("今天", rows.first().scope)
        val messages = history.observeMessages(a).first()
        assertEquals(expected, messages.map { it.role to it.text })
        assertEquals(messages.last().id, rows.first().lastMessageId)

        // Delete the highest thread id: a later send must never reuse it or absorb a late reply.
        history.delete(b)
        val c = history.sendUser(null, "课表", "换一个问题", null, emptyList()).conversationId
        assertNotEquals(b, c)
        history.append(b, HistoryRole.ASSISTANT, "已被删除的对话的迟到答复")
        store.reopen()
        history = store.db.conversationHistory()
        assertEquals(listOf(c, a), history.observeConversations().first().map { it.id })
        assertTrue(history.observeMessages(b).first().isEmpty())
        assertEquals(listOf("换一个问题"), history.observeMessages(c).first().map { it.text })
        assertEquals(messages, history.observeMessages(a).first())
    }
}
