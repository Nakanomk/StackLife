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

    @Test fun recentActivityAndFullTranscriptSurviveReopenButDeletedMessagesDoNot() = runBlocking {
        var history = store.db.conversationHistory()
        val prompt = "先安排上午的任务。".repeat(20)
        val a = history.start("今天", prompt)
        val b = history.start("课表", "周五有哪些空档？")
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
        val c = history.start("课表", "换一个问题")
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
