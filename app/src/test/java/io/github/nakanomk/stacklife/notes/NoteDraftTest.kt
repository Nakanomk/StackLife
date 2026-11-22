package io.github.nakanomk.stacklife.notes

import androidx.compose.ui.text.TextRange
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteDraftTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun freshReadersRestoreBothSourcesAndReversedSelectionsWithoutAProcessCache() {
        val source = "标题📝 한글\n\n".repeat(12_000)
        val accepted = NoteValue(source, TextRange(34, 2))
        val working = NoteValue("# 日本語\n$source", TextRange(1, 100_000))
        val id = NoteDraft.create(folder.root, "旧文本\r\n").let {
            it.checkpoint(accepted); it.accept(); it.checkpoint(working); it.id
        }
        val resumed = NoteDraft.open(folder.root, id)
        assertEquals(accepted, resumed.accepted)
        assertEquals(working, resumed.working)
        resumed.discardEdit()
        val afterCancel = NoteDraft.open(folder.root, id)
        assertEquals(accepted, afterCancel.accepted)
        assertEquals(accepted, afterCancel.working)

        afterCancel.checkpoint(working); afterCancel.accept()
        assertEquals(working, NoteDraft.open(folder.root, id).accepted)
    }

    @Test fun cursorMovesOnlyReplaceTheSmallIndexAndDeletingOneDraftKeepsOthers() {
        val root = folder.root
        val draft = NoteDraft.create(root, "十万字".repeat(40_000))
        val second = NoteDraft.create(root, "不要删除")
        val directory = File(root, draft.id)
        val source = directory.listFiles()!!.single { it.name != "index" }
        assertTrue(source.setLastModified(1_690_000_000_000L))
        val timestamp = source.lastModified()
        repeat(50) { draft.checkpoint(NoteValue(draft.working.source, TextRange(it))) }
        assertEquals(setOf(source.name, "index"), directory.list()!!.toSet())
        assertEquals(timestamp, source.lastModified())
        assertEquals(TextRange(49), NoteDraft.open(root, draft.id).working.selection)
        NoteDraft.delete(root, draft.id)
        assertFalse(directory.exists())
        assertEquals("不要删除", NoteDraft.open(root, second.id).accepted.source)
    }

    @Test fun checkpointPreservesEveryUtf16SourceIndexDuringAnEmojiReplacement() {
        val draft = NoteDraft.create(folder.root, "A📝B")
        val wip = NoteValue("A\uD83DB", TextRange(2))
        draft.checkpoint(wip)
        assertEquals(wip, NoteDraft.open(folder.root, draft.id).working)
    }

    @Test fun damagedWorkingCopyCanBeDiscardedWithoutChangingTheAcceptedNote() {
        val root = folder.root
        val draft = NoteDraft.create(root, "已接受的原文")
        draft.checkpoint(NoteValue("还在编辑的内容"))
        val working = File(File(root, draft.id), "1")
        working.writeBytes(byteArrayOf(0xc3.toByte()))
        assertThrows(IOException::class.java) { NoteDraft.open(root, draft.id) }
        assertEquals("已接受的原文", NoteDraft.accepted(root, draft.id).source)
        val restored = NoteDraft.open(root, draft.id, discardWorking = true)
        assertEquals(restored.accepted, restored.working)
        assertFalse(working.exists())
    }

    @Test fun damagedOrMissingDraftIsNeverReinitializedAsEmpty() {
        val root = folder.root
        val draft = NoteDraft.create(root, "保留原件")
        val directory = File(root, draft.id)
        val index = File(directory, "index")
        index.writeBytes(byteArrayOf(1))
        assertThrows(IOException::class.java) { NoteDraft.open(root, draft.id) }
        assertTrue(index.readBytes().contentEquals(byteArrayOf(1)))
        assertEquals("保留原件", File(directory, "0").readText(Charsets.UTF_16BE))
        NoteDraft.delete(root, draft.id)
        assertThrows(IOException::class.java) { NoteDraft.open(root, draft.id) }
        assertFalse(directory.exists())
        assertThrows(IOException::class.java) { NoteDraft.open(root, "../outside") }
    }
}
