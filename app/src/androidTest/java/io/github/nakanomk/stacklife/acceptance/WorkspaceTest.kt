package io.github.nakanomk.stacklife.acceptance

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import io.github.nakanomk.stacklife.storage.PlannerDatabase
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** File-backed and private to one test; never clear or seed the installed app's database. */
class WorkspaceFixture : ExternalResource() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "workspace-test-${UUID.randomUUID()}.db"
    lateinit var db: PlannerDatabase
        private set
    lateinit var api: PlannerApi
        private set

    override fun before() = reopen()
    fun reopen() {
        if (::db.isInitialized) db.close()
        db = Room.databaseBuilder(context, PlannerDatabase::class.java, name).build()
        api = RoomPlannerApi(db)
    }
    override fun after() {
        db.close()
        context.deleteDatabase(name)
    }
    suspend fun state() = api.observe(PlannerQuery.Workspace).first() as Workspace
    suspend fun proposal(vararg commands: PlannerCommand): PreviewResult.Ready =
        when (val preview = api.preview(commands.toList())) {
            is PreviewResult.Ready -> preview
            is PreviewResult.Rejected -> throw AssertionError(preview.message)
        }
    suspend fun apply(vararg commands: PlannerCommand) {
        val result = api.commit(proposal(*commands).proposalId)
        assertTrue("Command failed: $result", result is CommitResult.Applied)
    }
}

@RunWith(AndroidJUnit4::class)
class WorkspaceTest {
    @get:Rule val store = WorkspaceFixture()

    @Test fun interruptionPreservesIdentityOrderAndHistoricalTextAcrossReopen() = runBlocking {
        store.apply(TaskAdd(TaskInput("A")), TaskAdd(TaskInput("B", "原备注", 45)), TaskAdd(TaskInput("C", ref = "c")), TaskFinish("@c"))
        val (a, b) = store.state().queue
        store.apply(FramePush("去开会", listOf(a.id, b.id)), TaskAdd(TaskInput("X")))
        val interrupted = store.state()
        val x = interrupted.queue.single()
        val frame = interrupted.frames.single()
        assertEquals("去开会", frame.reason)
        assertEquals(listOf(a.id, b.id), frame.items.map { it.taskId })
        store.apply(FrameRestore(frame.id, RestoreMode.SELECTED, listOf(b.id)))
        val partial = store.state()
        assertEquals(listOf(b.id, x.id), partial.queue.map { it.id })
        assertEquals(listOf(true, false), partial.frames.single().items.map { it.restorable })
        store.apply(FrameRestore(frame.id, RestoreMode.ALL))
        assertEquals(listOf(a.id, b.id, x.id), store.state().queue.map { it.id })
        store.apply(TaskEdit(b.id, "新标题", "新备注", 10), FramePush("再中断", listOf(b.id)))
        val expected = store.state()
        store.reopen()
        assertEquals(expected, store.state())
        val history = expected.frames.single { it.id == frame.id }
        assertEquals(0, history.remaining)
        assertEquals(listOf("A", "B"), history.items.map { it.title })
        assertEquals("原备注", history.items.last().notes)
        assertEquals(45, history.items.last().minutes)
        assertEquals("新标题", expected.frames.single { it.id != frame.id }.items.single().title)
    }

    @Test fun optionalPushReasonAmendsOnlyTheCommittedFrameAcrossReopen() = runBlocking {
        store.apply(TaskAdd(TaskInput("A")), TaskAdd(TaskInput("B")), TaskAdd(TaskInput("C")))
        val (a, b, c) = store.state().queue
        val push = FramePush("  ", listOf(c.id, a.id), ref = "held")
        val preview = store.proposal(push)
        val id = preview.refs.getValue("held")
        assertTrue(preview.changes.any { it.entityId == id && it.field == "压栈原因" && it.after == "未填写" && it.entityLabel == "暂存的队列" })
        assertTrue(store.api.commit(preview.proposalId) is CommitResult.Applied)
        assertTrue(store.api.commit(preview.proposalId) is CommitResult.Rejected)
        assertTrue(store.api.preview(listOf(push)) is PreviewResult.Rejected)
        val first = store.state().frames.single()
        assertEquals(id, first.id)
        assertEquals("", first.reason)
        assertEquals(listOf(a.id, c.id), first.items.map { it.taskId })

        store.apply(FramePush(taskIds = listOf(b.id)))
        store.reopen()
        val before = store.state()
        assertEquals(first, before.frames.last())
        assertTrue(store.api.preview(listOf(FrameAddReason(id, "  "))) is PreviewResult.Rejected)
        assertTrue(store.api.preview(listOf(FrameAddReason("不存在", "补充"))) is PreviewResult.Rejected)
        val reason = FrameAddReason(id, "  临时开会  ")
        val change = store.proposal(reason)
        assertEquals(before, store.state())
        assertTrue(store.api.commit(change.proposalId) is CommitResult.Applied)
        assertTrue(store.api.commit(change.proposalId) is CommitResult.Rejected)
        assertTrue(store.api.preview(listOf(FrameAddReason(id, "覆盖"))) is PreviewResult.Rejected)
        store.reopen()
        assertEquals(before.copy(revision = before.revision + 1,
            frames = listOf(before.frames.first(), first.copy(reason = "临时开会"))), store.state())
    }

    @Test fun prependKeepsCurrentQueueBehindSourceOrder() = checkRestore(RestorePolicy.PREPEND, listOf("A", "B", "X"))
    @Test fun appendKeepsCurrentQueueInFront() = checkRestore(RestorePolicy.APPEND, listOf("X", "A", "B"))
    @Test fun swapSavesCurrentQueueOnce() = checkRestore(RestorePolicy.SWAP, listOf("A", "B"))

    private fun checkRestore(policy: RestorePolicy, order: List<String>) = runBlocking {
        store.apply(SetRestorePolicy(policy), FrameCreate("计划", listOf(TaskInput("A"), TaskInput("B"))), TaskAdd(TaskInput("X")))
        val before = store.state()
        val frame = before.frames.single()
        val command = FrameRestore(frame.id, RestoreMode.ALL)
        val preview = store.proposal(command)
        assertEquals(before, store.state())
        assertEquals(before.revision, preview.baseRevision)
        assertTrue(store.api.commit(preview.proposalId) is CommitResult.Applied)
        assertTrue(store.api.commit(preview.proposalId) is CommitResult.Rejected)
        val restored = store.state()
        assertEquals(order, restored.queue.map { it.title })
        assertEquals(0, restored.frames.single { it.id == frame.id }.remaining)
        if (policy == RestorePolicy.SWAP) {
            val saved = restored.frames.single { it.source == FrameSource.SWAP }
            assertEquals("切换栈帧前自动保存", saved.reason)
            assertEquals(before.queue.single().id, saved.items.single().taskId)
            assertTrue(saved.items.single().restorable)
        } else assertEquals(listOf(frame.id), restored.frames.map { it.id })
        // A fresh tap on an already-empty frame must not save the current queue, even in SWAP.
        val empty = store.api.preview(listOf(command))
        if (empty is PreviewResult.Ready) store.api.commit(empty.proposalId)
        assertEquals(restored.frames, store.state().frames)
        assertEquals(restored.queue, store.state().queue)
        store.reopen()
        assertEquals(policy, store.state().restorePolicy)
    }

    @Test fun partialSwapUsesSourceOrderAndDoesNotResurrectRemovedItems() = runBlocking {
        store.apply(SetRestorePolicy(RestorePolicy.SWAP), FrameCreate("计划", listOf(TaskInput("A"), TaskInput("B"), TaskInput("C"))), TaskAdd(TaskInput("X")))
        val frame = store.state().frames.single()
        val (a, b, c) = frame.items.map { it.taskId }
        store.apply(FrameRestore(frame.id, RestoreMode.SELECTED, listOf(c, a)))
        assertEquals(listOf("A", "C", "X"), store.state().queue.map { it.title })
        assertEquals(listOf(b), store.state().frames.single().items.filter { it.restorable }.map { it.taskId })
        store.apply(TaskFinish(c), TaskRemove(a), FrameRestore(frame.id, RestoreMode.ALL))
        assertEquals(listOf(b), store.state().queue.map { it.id })
        assertEquals(listOf("X"), store.state().frames.single { it.source == FrameSource.SWAP }.items.map { it.title })
    }

    @Test fun partialPushFollowsQueueRatherThanCheckOrderAndValidatesWholeBatch() = runBlocking {
        store.apply(*(listOf("A", "B", "C", "D").map { TaskAdd(TaskInput(it)) }.toTypedArray()))
        val (a, b, c, d) = store.state().queue.map { it.id }
        store.apply(QueueOrder(listOf(d, b, c, a)))
        val before = store.state()
        val invalid = listOf(
            FrameCreate("  ", listOf(TaskInput("A"))), FramePush("停", emptyList()), FramePush("停", listOf(a, "missing")),
            QueueOrder(listOf(a, b, b, d)), TaskAdd(TaskInput(" ")), TaskEdit(a, "A", minutes = 0),
            FramePush("停", listOf("@not-declared")),
        )
        for (bad in invalid) {
            assertTrue(store.api.preview(listOf(bad)) is PreviewResult.Rejected)
            assertEquals(before, store.state())
        }
        val badBatch = listOf(TaskAdd(TaskInput("不应留下", ref = "p")), FramePush("停", listOf("@undeclared")))
        assertTrue(store.api.preview(badBatch) is PreviewResult.Rejected)
        assertEquals(before, store.state())
        store.apply(FramePush("停", listOf(a, d)))
        val after = store.state()
        assertEquals(listOf(b, c), after.queue.map { it.id })
        assertEquals(listOf(d, a), after.frames.single().items.map { it.taskId })
    }

    @Test fun batchAliasesResolveToOneTaskWithoutEditingItsSnapshot() = runBlocking {
        store.apply(
            FrameCreate("准备", listOf(TaskInput("原题", ref = "a"), TaskInput("另一项")), ref = "f"),
            FrameRestore("@f", RestoreMode.SELECTED, listOf("@a")),
            TaskEdit("@a", "新题"),
        )
        val state = store.state()
        val a = state.queue.single()
        val history = state.frames.single()
        assertEquals("新题", a.title)
        assertEquals(a.id, history.items.first().taskId)
        assertEquals("原题", history.items.first().title)
        assertEquals(listOf(false, true), history.items.map { it.restorable })
    }

    @Test fun proposalsAreLocalConsumableAndRejectOtherRevision() = runBlocking {
        val stale = store.proposal(TaskAdd(TaskInput("不应留下")))
        store.apply(TaskAdd(TaskInput("X")))
        val current = store.state()
        assertEquals(CommitFailure.STALE_PREVIEW, (store.api.commit(stale.proposalId) as CommitResult.Rejected).code)
        val cancelled = store.proposal(TaskRemove(current.queue.single().id))
        store.api.discard(cancelled.proposalId)
        assertTrue(store.api.commit(cancelled.proposalId) is CommitResult.Rejected)
        val abandoned = store.proposal(TaskAdd(TaskInput("未确认")))
        store.reopen()
        assertTrue(store.api.commit(abandoned.proposalId) is CommitResult.Rejected)
        assertEquals(current, store.state())
    }

    @Test fun sqliteFailureInSecondSnapshotRollsBackEntirePush() = runBlocking {
        store.apply(TaskAdd(TaskInput("A")), TaskAdd(TaskInput("B")))
        val before = store.state()
        val preview = store.proposal(FramePush("开会", before.queue.map { it.id }))
        store.db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_second_snapshot BEFORE INSERT ON frame_items
            WHEN (SELECT COUNT(*) FROM frame_items) = 1
            BEGIN SELECT RAISE(ABORT, 'acceptance: second snapshot'); END
        """.trimIndent())
        assertEquals(CommitFailure.STORAGE, (store.api.commit(preview.proposalId) as CommitResult.Rejected).code)
        store.reopen()
        assertEquals(before, store.state())
    }
}
