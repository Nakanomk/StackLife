package io.github.nakanomk.stacklife.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaySlotTest {
    @get:Rule val store = WorkspaceFixture()
    private val date = LocalDate.of(2026, 9, 7)
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(h: Int, m: Int) = date.atTime(h, m).atZone(zone).toOffsetDateTime()
    private suspend fun day(d: LocalDate = date, z: ZoneId = zone) = store.api.observe(PlannerQuery.Day(d, z)).first() as DayPlan
    private suspend fun reject(vararg commands: PlannerCommand): String {
        val preview = store.api.preview(commands.toList())
        assertTrue("Unexpected preview: $preview", preview is PreviewResult.Rejected)
        return (preview as PreviewResult.Rejected).message
    }

    @Test fun orderedConflictAndStaleDropNeverOverwriteAnotherTask() = runBlocking {
        store.apply(TaskAdd(TaskInput("报告提纲", ref = "a")), SlotSet("@a", at(15, 45), 30),
            TaskAdd(TaskInput("评审", ref = "b")), SlotSet("@b", at(16, 15), 30), TaskAdd(TaskInput("待排")))
        val (a, b, c) = store.state().queue
        val before = day()
        assertEquals(listOf(c.id), before.tray.map { it.id })
        val conflict = reject(SlotSet(c.id, at(16, 0), 35, before.revision))
        for (part in listOf("报告提纲", "评审", "15:45", "16:15", "16:45")) assertTrue(conflict, part in conflict)
        // The first command moved B in the overlay; the second conflicts with that new position. Neither is applied.
        assertTrue("评审" in reject(SlotSet(b.id, at(17, 0), 30), SlotSet(a.id, at(16, 50), 30)))
        assertEquals(before, day())
        store.apply(SlotClear(b.id), SlotSet(a.id, at(16, 0), 30), SlotSet(b.id, at(16, 30), 15))
        assertEquals(listOf(a.id, b.id), day().blocks.map { it.taskId })
        assertEquals(at(16, 0).toInstant().toEpochMilli(), day().blocks.first().startMillis)
        val moved = day()
        reject(SlotClear(a.id, before.revision)); reject(SlotSet(c.id, at(15, 0), 5, before.revision))
        assertEquals(moved, day())
        store.apply(SlotClear(a.id, moved.revision))
        assertEquals(listOf(a.id, c.id), day().tray.map { it.id })
        assertEquals(listOf(a.id, b.id, c.id), store.state().queue.map { it.id })
    }

    @Test fun competingReservationsAndStorageFailureLeaveNoPartialState() = runBlocking {
        store.apply(TaskAdd(TaskInput("A")), TaskAdd(TaskInput("B")))
        val (a, b) = store.state().queue
        val first = store.proposal(SlotSet(a.id, at(15, 45), 30))
        val competing = store.proposal(SlotSet(b.id, at(15, 45), 30))
        assertTrue(store.api.commit(first.proposalId) is CommitResult.Applied)
        assertEquals(CommitFailure.STALE_PREVIEW, (store.api.commit(competing.proposalId) as CommitResult.Rejected).code)
        val before = day(); val workspace = store.state()
        assertEquals(listOf(a.id), before.blocks.map { it.taskId })
        val replace = store.proposal(SlotClear(a.id), FramePush("暂停", listOf(a.id)), SlotSet(b.id, at(15, 45), 30))
        store.db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_reservation BEFORE INSERT ON time_blocks
            BEGIN SELECT RAISE(ABORT, 'acceptance: reservation failed'); END
        """.trimIndent())
        assertEquals(CommitFailure.STORAGE, (store.api.commit(replace.proposalId) as CommitResult.Rejected).code)
        store.reopen(); assertEquals(before, day()); assertEquals(workspace, store.state())
    }

    @Test fun pushSwapAndRestoreKeepActualSlotAndTheSnapshotAtThatMoment() = runBlocking {
        store.apply(TaskAdd(TaskInput("A", ref = "a")), SlotSet("@a", at(15, 45), 30), FramePush("开会", listOf("@a")),
            TaskAdd(TaskInput("X", ref = "x")), SlotSet("@x", at(17, 0), 30), SetRestorePolicy(RestorePolicy.SWAP))
        val snapshot = store.state().frames.single()
        val a = snapshot.items.single().taskId
        val x = store.state().queue.single().id
        val frameBlock = day().blocks.first()
        assertEquals(ScheduledState.FRAME, frameBlock.state)
        assertEquals(frameBlock.startMillis, snapshot.items.single().scheduledStartMillis)
        assertEquals(frameBlock.endMillis, snapshot.items.single().scheduledEndMillis)
        assertTrue("A" in reject(SlotSet(x, at(16, 0), 30)))
        store.apply(SlotSet(a, at(16, 0), 35), FrameRestore(snapshot.id, RestoreMode.ALL))
        val resumed = day()
        assertEquals(listOf(a), store.state().queue.map { it.id })
        assertEquals(listOf(ScheduledState.QUEUE, ScheduledState.FRAME), resumed.blocks.map { it.state })
        assertEquals(at(16, 0).toInstant().toEpochMilli(), resumed.blocks.first().startMillis)
        assertEquals(at(16, 35).toInstant().toEpochMilli(), resumed.blocks.first().endMillis)
        val frames = store.state().frames
        assertEquals(frameBlock.startMillis, frames.single { it.id == snapshot.id }.items.single().scheduledStartMillis)
        val savedX = frames.single { it.source == FrameSource.SWAP }.items.single()
        assertEquals(x, savedX.taskId); assertEquals(resumed.blocks.last().startMillis, savedX.scheduledStartMillis)
        store.reopen(); assertEquals(resumed, day())
        store.apply(TaskFinish(a), TaskRemove(x))
        assertEquals(listOf(a), day().blocks.map { it.taskId }); assertEquals(ScheduledState.DONE, day().blocks.single().state)
        reject(SlotClear(a)); reject(SlotSet(a, at(15, 0), 10))
        store.apply(TaskAdd(TaskInput("接替 X", ref = "new")), SlotSet("@new", at(17, 0), 30))
        assertEquals(listOf("A", "接替 X"), day().blocks.map { it.title })
    }

    @Test fun civilDayUsesTwoLocalMidnightsAndClipsOnlyAtPresentation() = runBlocking {
        val ny = ZoneId.of("America/New_York")
        val spring = LocalDate.of(2026, 3, 8); val fall = LocalDate.of(2026, 11, 1)
        val from = spring.minusDays(1).atTime(23, 45).atZone(ny).toOffsetDateTime()
        val midnight = spring.atStartOfDay(ny).toOffsetDateTime()
        val folds = ny.rules.getValidOffsets(fall.atTime(1, 30))
        store.apply(TaskAdd(TaskInput("跨午夜", ref = "night")), SlotSet("@night", from, 45),
            TaskAdd(TaskInput("回拨之前", ref = "first")), SlotSet("@first", fall.atTime(1, 30).atOffset(folds.first()), 30),
            TaskAdd(TaskInput("回拨之后", ref = "last")), SlotSet("@last", fall.atTime(1, 30).atOffset(folds.last()), 30))
        val firstDay = day(spring.minusDays(1), ny); val nextDay = day(spring, ny); val repeated = day(fall, ny)
        assertEquals(23 * 3_600_000L, nextDay.day.endMillis - nextDay.day.startMillis)
        assertEquals(25 * 3_600_000L, repeated.day.endMillis - repeated.day.startMillis)
        assertEquals(firstDay.blocks.single(), nextDay.blocks.single())
        assertEquals(listOf("回拨之前", "回拨之后"), repeated.blocks.map { it.title })
        assertEquals(3_600_000L, repeated.blocks.last().startMillis - repeated.blocks.first().startMillis)
        assertTrue(firstDay.tray.isEmpty()); assertTrue(day(fall.plusDays(1), ny).blocks.isEmpty())
        val id = firstDay.blocks.single().taskId
        store.apply(SlotSet(id, from, 15))
        assertTrue(day(spring, ny).blocks.isEmpty()) // End at midnight does not intersect the next day.
        store.apply(SlotSet(id, midnight, 15))
        assertTrue(day(spring.minusDays(1), ny).blocks.isEmpty())
        val apia = ZoneId.of("Pacific/Apia"); val skipped = LocalDate.of(2011, 12, 30)
        store.apply(SlotSet(id, skipped.minusDays(1).atTime(23, 45).atZone(apia).toOffsetDateTime(), 30))
        assertTrue(day(skipped, apia).blocks.isEmpty()); assertEquals(id, day(skipped.plusDays(1), apia).blocks.single().taskId)
    }
}
