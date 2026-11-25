package io.github.nakanomk.stacklife.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.withTransaction
import io.github.nakanomk.stacklife.appearance.BackgroundStore
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

internal class ArchiveFixture(d: BackupDeviceTest.Device) {
    val chat = d.db.conversationHistory()
    private val backgrounds = BackgroundStore(d.app) { d.db.withTransaction {
        d.db.countdownDao().backgroundIds().toSet() + chat.backgroundIds() +
            (d.db.plannerDao().settings()?.let { decodeAppearance(it.appearance).backgroundIds() } ?: emptySet())
    } }
    val files = AttachmentStore(d.app, { chat.attachmentIds().toSet() })
    val planner = RoomPlannerApi(d.db, imageExists = backgrounds::exists, collectBackgrounds = backgrounds::collect)
    val archive = FullBackup(planner, backgrounds, files, d.device, AssistantSessions(), d.root)
    suspend fun workspace() = planner.observe(PlannerQuery.Workspace).first() as Workspace
    suspend fun apply(vararg commands: PlannerCommand) {
        val ready = planner.preview(commands.toList()) as PreviewResult.Ready
        assertTrue(planner.commit(ready.proposalId) is CommitResult.Applied)
    }
    suspend fun pack(parts: Set<BackupPart> = BackupPart.entries.toSet(), key: Boolean = true): ByteArray = ByteArrayOutputStream().also { bytes ->
        archive.export(parts, key) { bytes }
    }.toByteArray()
}

@RunWith(AndroidJUnit4::class)
class FullBackupIntegrationTest {
    @Test fun fullArchiveRoundTripsAcrossKeystoresAndPartialRestoreKeepsOtherOwners() = runBlocking {
        BackupDeviceTest.Device().use { source -> BackupDeviceTest.Device().use { dest ->
            val a = ArchiveFixture(source); val b = ArchiveFixture(dest)
            val secret = "only-this-test-${java.util.UUID.randomUUID()}"
            source.connections.save("https://archive-fixture.invalid/v1", "source-model", secret)
            a.apply(PlannerCommand.TaskAdd(TaskInput("portable", ref = "t", icon = TaskIcon.EVENT)), PlannerCommand.TaskAdd(TaskInput("in frame", ref = "f")),
                PlannerCommand.FramePush("pause", listOf("@f")), PlannerCommand.CountdownAdd("exam", 22001), PlannerCommand.SetRestorePolicy(RestorePolicy.APPEND))
            val oldThread = a.chat.sendUser(null, "Queue", "fixture text", null, emptyList())
            a.chat.append(oldThread.conversationId, HistoryRole.ASSISTANT, "fixture reply")
            val original = a.workspace(); val data = a.pack(); val wire = linkedMapOf<String, ByteArrayOutputStream>()
            val manifest = ArchiveCodec.read(FullBackupLayout, data.inputStream(), { name -> ByteArrayOutputStream().also { wire[name] = it } })
            assertEquals(BackupPart.byWire.keys, manifest.counts.keys)
            assertEquals(listOf("connection.json"), wire.filterValues { it.toString("UTF-8").contains(secret) }.keys.toList())
            assertFalse(a.pack(setOf(BackupPart.CONNECTION), key = false).toString(Charsets.ISO_8859_1).contains(secret))
            var checked = false
            val receipt = requireNotNull(b.archive.restore({ data.inputStream() }, { assertTrue(it.containsKey); it.parts }) { review ->
                assertTrue(review.conflicts.toString(), review.conflicts.isEmpty()); assertTrue(review.containsKey)
                assertFalse(review.toString().contains(secret)); checked = true; true
            })
            assertTrue(checked); assertEquals(b.workspace().revision, receipt.revision)
            dest.reopen(); val cold = ArchiveFixture(dest); val state = cold.workspace()
            assertEquals(original.queue, state.queue); assertEquals(original.frames, state.frames); assertEquals(original.restorePolicy, state.restorePolicy)
            assertEquals("source-model", dest.connections.profile()?.model); assertTrue(dest.connections.load()?.key == secret)
            assertEquals(listOf("fixture text", "fixture reply"), cold.chat.observeMessages(oldThread.conversationId).first().map { it.text })
            val kept = cold.chat.sendUser(null, "Queue", "only on target", null, emptyList()); val revisions = state.revision
            cold.apply(PlannerCommand.TaskAdd(TaskInput("drop only this queued row")))
            assertNotNull(cold.archive.restore({ data.inputStream() }, { setOf(BackupPart.QUEUE) }) { review ->
                assertEquals(setOf(BackupPart.QUEUE), review.counts.keys); assertFalse(review.containsKey)
                assertTrue(review.conflicts.toString(), review.conflicts.isEmpty()); assertTrue(review.counts.getValue(BackupPart.QUEUE).removed > 0); true
            })
            assertEquals(original.queue, cold.workspace().queue); assertEquals(revisions + 2, cold.workspace().revision)
            assertTrue(dest.connections.load()?.key == secret); assertEquals("only on target", cold.chat.observeMessages(kept.conversationId).first().single().text)
        } }
    }

    @Test fun lateHistoryWritesForceNewReviewAndTheOldUndoCannotTouchReusedIds() = runBlocking {
        BackupDeviceTest.Device().use { target ->
            val f = ArchiveFixture(target)
            f.apply(PlannerCommand.TaskAdd(TaskInput("source", ref = "s")))
            val sourceId = f.workspace().queue.single().id
            val archive = f.pack(setOf(BackupPart.QUEUE, BackupPart.STACK))
            val ready = f.planner.preview(listOf(PlannerCommand.FramePush("prior", listOf(sourceId)))) as PreviewResult.Ready
            val pushed = f.planner.commit(ready.proposalId) as CommitResult.Applied
            val before = f.workspace(); val draft = f.planner.preview(listOf(PlannerCommand.TaskAdd(TaskInput("expired")))) as PreviewResult.Ready
            var visits = 0
            val saved = requireNotNull(f.archive.restore({ archive.inputStream() }, { it.parts }) { review ->
                assertTrue(review.conflicts.toString(), review.conflicts.isEmpty())
                if (++visits == 1) f.chat.sendUser(null, "Queue", "arrived while reviewing", null, emptyList())
                else assertTrue(review.refreshed)
                true
            })
            assertEquals(2, visits); assertEquals(before.revision + 1, saved.revision)
            assertEquals(sourceId, f.workspace().queue.single().id); assertTrue(f.workspace().frames.isEmpty())
            assertTrue(f.planner.applyWorkspaceReceipt(pushed.requestId, WorkspaceReceiptAction.UNDO) is CommitResult.Rejected)
            assertTrue(f.planner.commit(draft.proposalId) is CommitResult.Rejected)
            assertEquals(saved.revision, f.workspace().revision)
        }
    }
}
