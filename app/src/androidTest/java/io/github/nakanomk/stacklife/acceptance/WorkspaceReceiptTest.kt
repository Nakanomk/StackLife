package io.github.nakanomk.stacklife.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import io.github.nakanomk.stacklife.planner.WorkspaceReceiptAction.*
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceReceiptTest {
    @get:Rule val store = WorkspaceFixture()
    private suspend fun issue(command: PlannerCommand, api: PlannerApi = store.api): CommitResult.Applied {
        val preview = api.preview(listOf(command)) as PreviewResult.Ready
        return api.commit(preview.proposalId) as CommitResult.Applied
    }
    private suspend fun undo(receipt: CommitResult.Applied) = store.api.applyWorkspaceReceipt(receipt.requestId, UNDO)

    @Test fun keepOnlyRestoresDeletedSnapshotThenUndoSwapPreservesLaterNotes() = runBlocking {
        store.apply(SetRestorePolicy(RestorePolicy.SWAP), FrameCreate("source", listOf(TaskInput("A", "old A"), TaskInput("B"))), TaskAdd(TaskInput("X")))
        val before = store.state(); val original = before.frames.single(); val x = before.queue.single()
        val restored = issue(FrameRestore(original.id, RestoreMode.ALL))
        val a = store.state().queue.first().id
        store.apply(TaskEdit(a, notes = "new A"), TaskEdit(x.id, notes = "new X"))
        val moved = store.state(); assertFalse(moved.frames.any { it.id == original.id })
        assertTrue(store.api.applyWorkspaceReceipt(restored.requestId, KEEP_HISTORY) is CommitResult.Applied)
        val kept = store.state(); assertEquals(moved.queue, kept.queue)
        assertEquals(original.items.map { it.copy(current = null) }, kept.frames.single { it.id == original.id }.items)
        assertTrue(store.api.applyWorkspaceReceipt(restored.requestId, KEEP_HISTORY) is CommitResult.Rejected)
        assertTrue(undo(restored) is CommitResult.Applied)
        val reversed = store.state(); assertEquals(listOf(x.copy(notes = "new X")), reversed.queue)
        assertEquals(original.id, reversed.frames.single().id)
        val entries = reversed.frames.single().items
        assertEquals(original.items.map { it.taskId }, entries.map { it.taskId })
        assertEquals("old A", entries.first().notes); assertEquals("new A", entries.first().current!!.notes)
        assertTrue(undo(restored) is CommitResult.Rejected)
        store.reopen(); assertEquals(reversed, store.state())
    }

    @Test fun undoPushReinsertsAtSurvivingNeighboursButRejectsLaterReasonAndMovedQueueOrder() = runBlocking {
        store.apply(*(listOf("A", "B", "C", "D").map { TaskAdd(TaskInput(it)) }.toTypedArray()))
        val (a, b, c, d) = store.state().queue.map { it.id }
        val push = issue(FramePush(taskIds = listOf(d, b)))
        store.apply(TaskAdd(TaskInput("E")), TaskEdit(b, notes = "keep edit"))
        assertTrue(undo(push) is CommitResult.Applied)
        val restored = store.state(); assertEquals(listOf("A", "B", "C", "D", "E"), restored.queue.map { it.title })
        assertEquals("keep edit", restored.queue[1].notes)
        val again = issue(FramePush(taskIds = listOf(b, d))); val frame = store.state().frames.single()
        store.apply(FrameAddReason(frame.id, "later reason")); val amended = store.state()
        assertTrue(undo(again) is CommitResult.Rejected); assertEquals(amended, store.state())
        val partial = issue(FrameRestore(frame.id, RestoreMode.SELECTED, listOf(b)))
        val order = store.state().queue.map { it.id }; assertEquals(listOf(b,a,c), order.take(3))
        store.apply(QueueOrder(order.toMutableList().apply { remove(b); add(b) })); val reordered = store.state()
        assertTrue(undo(partial) is CommitResult.Rejected); assertEquals(reordered, store.state())
    }

    @Test fun restoreRemovedSeriesWithExceptionCompletionAndRulesWithoutMovingNewOverlappingAppointment() = runBlocking {
        val zone = ZoneId.of("UTC"); val local = LocalDate.of(2027, 1, 4); val day = local.toEpochDay()
        val pattern = SchedulePattern.Daily(listOf(DailySegment(day, 540, zone.id, 30, day + 3)))
        store.apply(TaskAdd(TaskInput("series", ref="series")), SetSchedule("@series", pattern), TaskAdd(TaskInput("other")))
        val (a,b) = store.state().queue; val key = OccurrenceKey.Schedule(a.id, day + 1)
        val start = local.atTime(11,0).atZone(zone).toInstant().toEpochMilli(); val interval = ExactInterval(start, start + 300_000L)
        val customized = ReminderPolicy(false, listOf(ReminderRule("custom", ReminderWhen.BeforeStart(60_000))))
        store.apply(SetOccurrenceOverride(key, EditScope.THIS, interval), SetOccurrenceCompletion(key, true), SetReminders(ReminderSubject.Occurrence(key), customized))
        val removal = issue(TaskRemove(a.id)); assertNull((store.api.observe(PlannerQuery.Schedule(a.id)).first() as ScheduleView).summary)
        store.apply(SetSchedule(b.id, SchedulePattern.Once(interval)))
        val undone = undo(removal) as CommitResult.Applied; assertTrue(undone.overlappingSchedule)
        store.reopen(); val populated = store.api.observe(PlannerQuery.Day(local,zone)).first() as DayPlan
        assertEquals(setOf(a.id,b.id), populated.occurrences.filter { it.startMillis == start }.map { it.taskId }.toSet())
        assertTrue(populated.occurrences.single { it.key == key }.completed)
        val rules = store.api.observe(PlannerQuery.Reminders(ReminderSubject.Occurrence(key))).first() as ReminderView
        assertEquals(customized, rules.policy)
        assertEquals(listOf(a.id,b.id),store.state().queue.map { it.id })
        assertEquals(pattern, (store.api.observe(PlannerQuery.Schedule(a.id)).first() as ScheduleView).summary!!.pattern)
    }

    @Test fun batchHistoryUndoRollsBackEveryFrameOnStorageFailureAndExpiresWithoutReplay() = runBlocking {
        store.apply(FrameCreate("one", listOf(TaskInput("a", ref="a"))), TaskFinish("@a"), FrameCreate("two", listOf(TaskInput("b", ref="b"))), TaskFinish("@b"))
        val before = store.state(); var clock = 1_000L
        val api = RoomPlannerApi(store.db, receiptTimeoutMillis = { 2_000L }, elapsed = { clock })
        val removed = issue(DeleteFrameHistory(before.frames.map { it.id }), api); assertEquals(3_000L, removed.expiresAtElapsed)
        val after = store.state(); assertTrue(after.frames.isEmpty())
        val sql = store.db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_history BEFORE INSERT ON frames WHEN NEW.reason='one' BEGIN SELECT RAISE(ABORT,'blocked'); END")
        assertTrue(api.applyWorkspaceReceipt(removed.requestId, UNDO) is CommitResult.Rejected); assertEquals(after, store.state())
        sql.execSQL("DROP TRIGGER reject_history")
        assertTrue(api.applyWorkspaceReceipt(removed.requestId, UNDO) is CommitResult.Applied)
        assertEquals(before.frames,store.state().frames)
        val next = issue(DeleteFrameHistory(before.frames.map { it.id }), api); val deleted = store.state(); clock += 2_000L
        assertTrue(api.applyWorkspaceReceipt(next.requestId, UNDO) is CommitResult.Rejected)
        store.reopen(); assertTrue(store.api.applyWorkspaceReceipt(next.requestId, UNDO) is CommitResult.Rejected); assertEquals(deleted,store.state())
    }
}
