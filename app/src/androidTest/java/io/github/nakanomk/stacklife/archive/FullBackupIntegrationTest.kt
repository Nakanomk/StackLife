package io.github.nakanomk.stacklife.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import io.github.nakanomk.stacklife.appearance.BackgroundStore
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
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
    val sessions = AssistantSessions { d.assistant.enabled }
    val archive = FullBackup(planner, backgrounds, files, d.device, sessions, d.root)
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

    private suspend fun <T> withLocalModels(d: BackupDeviceTest.Device, f: ArchiveFixture, count: Int,
        api: PlannerApi = f.planner, use: suspend (List<AssistantModel>, MockWebServer, OkHttpClient) -> T): T {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .callTimeout(15, TimeUnit.SECONDS).build()
        return MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            d.connections.save("https://127.0.0.1:${server.port}/v1", "archive-local", "fixture-only")
            val transport = OpenAiTransport(client)
            val models = withContext(Dispatchers.Main.immediate) {
                List(count) { AssistantModel(api, d.connections, transport, f.chat, f.files, sessions = f.sessions) }
            }
            try { use(models, server, client) } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    models.forEach { it.close(); it.viewModelScope.coroutineContext.job.cancelAndJoin() }
                }
                client.connectionPool.evictAll()
            }
        }
    }

    private suspend fun AssistantModel.sendFromQueue(prompt: String) = withContext(Dispatchers.Main.immediate) {
        draft("queue").field.edit { append(prompt) }
        submitLocal("queue") { AssistantPage.Queue }
    }

    @Test fun restoreCancelsBothLiveHttpRepliesBeforeTheyCanAppendToReusedThreads() = runBlocking {
        BackupDeviceTest.Device().use { donor -> BackupDeviceTest.Device().use { dest ->
            val a = ArchiveFixture(donor); val f = ArchiveFixture(dest)
            val note = "Only the archived document owns this message."
            a.files.recover()
            val id = UUID.randomUUID().toString()
            val file = a.files.file(id).apply { writeText(note) }
            val doc = ChatDocument(id, "text/plain", "notes.txt", file.length())
            a.chat.sendUser(null, "Queue", "first archived thread", null, emptyList())
            a.chat.sendUser(null, "Queue", "second archived thread", null, listOf(doc))
            val rows = a.chat.observeConversations().first()
            val messages = rows.associate { it.id to a.chat.observeMessages(it.id).first() }
            val data = a.pack(setOf(BackupPart.HISTORY))
            val before = f.workspace()

            val receipt = withLocalModels(dest, f, 2) { models, server, client ->
                val idle = CompletableDeferred<Unit>()
                val restored = try {
                    // No response is enqueued. Each real HTTP call has already committed a user
                    // message when the server receives it, including the second activity's turn.
                    models.forEachIndexed { index, model ->
                        model.sendFromQueue("superseded prompt $index")
                        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)?.handshake)
                        assertEquals(AssistantWork.REQUEST, model.state.value.work)
                    }
                    val targetThreads = f.chat.observeConversations().first()
                    assertEquals(rows.map { it.id }, targetThreads.map { it.id })
                    assertEquals(rows.map { it.lastMessageId }, targetThreads.map { it.lastMessageId })
                    assertNotEquals(rows.map { it.title }, targetThreads.map { it.title })
                    val calls = client.dispatcher.runningCalls()
                    assertEquals(2, calls.size)
                    client.dispatcher.idleCallback = Runnable { idle.complete(Unit) }
                    val saved = requireNotNull(withTimeout(10_000) {
                        f.archive.restore({ data.inputStream() }, { it.parts }) { review ->
                            assertEquals(1, review.attachments); assertTrue(review.conflicts.isEmpty()); true
                        }
                    })
                    saved to calls
                } finally {
                    // Deliver valid answers even on failure; neither server worker is left waiting.
                    repeat(2) { server.enqueue(MockResponse(body =
                        """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"late reply $it"}}]}""")) }
                }
                withTimeout(10_000) {
                    idle.await() // OkHttp callbacks returned, not just cancellation requested.
                    models.forEach { it.state.first { state -> state.work == AssistantWork.IDLE } }
                }
                assertTrue(restored.second.all { it.isCanceled() })
                assertTrue(models.all { it.state.value.page == null })
                restored.first
            }
            dest.reopen()
            val cold = ArchiveFixture(dest)
            assertEquals(before.revision + 1, receipt.revision)
            assertEquals(before.copy(revision = receipt.revision), cold.workspace())
            assertEquals(rows, cold.chat.observeConversations().first())
            for ((thread, lines) in messages) assertEquals(lines, cold.chat.observeMessages(thread).first())
            assertEquals(listOf(doc.id), cold.chat.attachmentIds())
            assertEquals(note, doc.readValidated(cold.files.file(doc.id)))
        } }
    }

    @Test fun restoreWaitsForConfirmedCommitReceiptAndReviewsItsDurableEventBeforeReplacingIt() = runBlocking {
        BackupDeviceTest.Device().use { dest ->
            val f = ArchiveFixture(dest); val before = f.workspace()
            val entered = CompletableDeferred<Unit>(); val allowWrite = CompletableDeferred<Unit>()
            val written = CompletableDeferred<CommitResult>(); val deliver = CompletableDeferred<Unit>()
            val gated = object : PlannerApi by f.planner {
                override suspend fun commit(proposalId: String): CommitResult {
                    entered.complete(Unit)
                    withTimeout(15_000) { allowWrite.await() }
                    val result = f.planner.commit(proposalId)
                    written.complete(result)
                    // The actual transaction has finished. No SQLite lock can accidentally
                    // serialize restore with the assistant's still outstanding history receipt.
                    withTimeout(15_000) { deliver.await() }
                    return result
                }
            }
            val (receipt, thread, archived) = withLocalModels(dest, f, 1, gated) { (model), server, _ ->
                val tool = """{"id":"local","type":"function","function":{"name":"planner_propose","arguments":"{\"commands\":[{\"kind\":\"task_add\",\"title\":\"confirmed once\"}]}"}}"""
                server.enqueue(MockResponse(body =
                    """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[$tool]}}]}"""))
                model.sendFromQueue("please add a task")
                val offered = withTimeout(5_000) { model.state.first { it.pending != null } }
                val proposal = requireNotNull(offered.pending); val eventId = requireNotNull(offered.eventId)
                val thread = f.chat.observeConversations().first().single().id
                val archived = f.chat.observeMessages(thread).first()
                assertEquals(ProposalStatus.PROPOSED, archived.single { it.id == eventId }.event?.status)
                val data = f.pack(setOf(BackupPart.QUEUE, BackupPart.HISTORY))
                val reviews = mutableListOf<Boolean>()
                val saved = try {
                    withContext(Dispatchers.Main.immediate) { model.apply(proposal.proposalId) }
                    withTimeout(5_000) { entered.await() }
                    assertEquals(AssistantWork.COMMIT, model.state.value.work)
                    val restoring = async {
                        requireNotNull(f.archive.restore({ data.inputStream() }, { it.parts }) { review ->
                            assertTrue(review.conflicts.toString(), review.conflicts.isEmpty())
                            reviews.add(review.refreshed)
                            val titles = f.workspace().queue.map { it.title }
                            assertEquals(if (review.refreshed) listOf("confirmed once") else before.queue.map { it.title }, titles)
                            val event = requireNotNull(f.chat.observeMessages(thread).first().single { it.id == eventId }.event)
                            assertEquals(if (review.refreshed) ProposalStatus.APPLIED else ProposalStatus.PROPOSED, event.status)
                            assertEquals(proposal.changes, event.changes)
                            true
                        })
                    }
                    val paused = async { model.state.first { it.page == null && it.work == AssistantWork.COMMIT } }
                    try {
                        // If restore overtakes the save, release its late receipt against the
                        // reused event ID as well; don't let a missing pause hang the fixture.
                        withTimeout(10_000) { select<Unit> { paused.onAwait { }; restoring.onAwait { } } }
                        allowWrite.complete(Unit)
                        val applied = withTimeout(5_000) { written.await() } as CommitResult.Applied
                        assertEquals(before.revision + 1, applied.revision)
                        assertEquals(applied.revision, f.workspace().revision)
                        assertEquals(archived, f.chat.observeMessages(thread).first())
                    } finally { deliver.complete(Unit); paused.cancel() }
                    withTimeout(10_000) { restoring.await() }
                } finally { allowWrite.complete(Unit); deliver.complete(Unit) }
                withTimeout(5_000) { model.state.first { it.work == AssistantWork.IDLE } }
                assertEquals(listOf(false, true), reviews)
                withContext(Dispatchers.Main.immediate) { model.apply(proposal.proposalId) }
                assertEquals(AssistantWork.IDLE, model.state.value.work)
                Triple(saved, thread, archived)
            }
            dest.reopen()
            val cold = ArchiveFixture(dest)
            assertEquals(before.revision + 2, receipt.revision)
            assertEquals(before.copy(revision = receipt.revision), cold.workspace())
            assertEquals(archived, cold.chat.observeMessages(thread).first())
        }
    }
}
