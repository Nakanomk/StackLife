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

    @Test fun twentyCommonStartsSurviveReopenAndAStaleDropChangesNone() = runBlocking {
        val commands = (0 until 20).flatMap { n -> listOf(TaskAdd(TaskInput("Task$n",minutes = n,ref = "t$n")),
            exact("@t$n",at(16,0),if (n < 3) 0 else n)) }
        store.apply(*commands.toTypedArray())
        val before = day()
        assertEquals(20,before.occurrences.size)
        assertEquals(20,before.occurrences.map { it.key }.toSet().size)
        assertEquals(3,before.occurrences.count { it.point })
        assertTrue(before.tray.isEmpty())
        val ids = store.state().queue.map { it.id }
        val key = OccurrenceKey.Schedule(ids.first())
        store.apply(SetOccurrenceCompletion(key,true,before.revision))
        reject(SetSchedule(ids.last(),null,expectedRevision = before.revision))
        store.reopen(); val after = day()
        assertEquals(before.occurrences.map { it.key },after.occurrences.map { it.key })
        assertEquals(listOf(key),after.occurrences.filter { it.completed }.map { it.key })
        store.apply(SetSchedule(ids.last(),null))
        assertEquals(listOf(ids.last()),day().tray.map { it.id })
        assertEquals(ids.drop(1),store.state().queue.map { it.id })
    }

    @Test fun competingReservationsAndStorageFailureLeaveNoPartialState() = runBlocking {
        store.apply(TaskAdd(TaskInput("A")), TaskAdd(TaskInput("B")))
        val (a, b) = store.state().queue
        val first = store.proposal(exact(a.id, at(15, 45), 30))
        val competing = store.proposal(exact(b.id, at(15, 45), 30))
        assertTrue(store.api.commit(first.proposalId) is CommitResult.Applied)
        assertEquals(CommitFailure.STALE_PREVIEW, (store.api.commit(competing.proposalId) as CommitResult.Rejected).code)
        val before = day(); val workspace = store.state()
        assertEquals(listOf(a.id), before.occurrences.map { it.taskId })
        val replace = store.proposal(SetSchedule(a.id,null), FramePush("暂停", listOf(a.id)), exact(b.id, at(15, 45), 30))
        store.db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_reservation BEFORE INSERT ON agenda_schedules
            BEGIN SELECT RAISE(ABORT, 'acceptance: reservation failed'); END
        """.trimIndent())
        assertEquals(CommitFailure.STORAGE, (store.api.commit(replace.proposalId) as CommitResult.Rejected).code)
        store.reopen(); assertEquals(before, day()); assertEquals(workspace, store.state())
    }

    @Test fun pushSwapAndRestoreKeepActualSlotAndTheSnapshotAtThatMoment() = runBlocking {
        store.apply(KeepRestoredFrames(true))
        store.apply(TaskAdd(TaskInput("A", ref = "a")), exact("@a", at(15, 45), 30), FramePush("开会", listOf("@a")),
            TaskAdd(TaskInput("X", ref = "x")), exact("@x", at(17, 0), 30), SetRestorePolicy(RestorePolicy.SWAP))
        val snapshot = store.state().frames.single()
        val a = snapshot.items.single().taskId
        val x = store.state().queue.single().id
        val frameBlock = day().occurrences.first()
        assertEquals(Owner.FRAME, frameBlock.owner)
        assertEquals(frameBlock.startMillis, (snapshot.items.single().schedule!!.pattern as SchedulePattern.Once).interval.startMillis)
        assertEquals(frameBlock.endMillis, (snapshot.items.single().schedule!!.pattern as SchedulePattern.Once).interval.endMillis)
        val overlap = store.proposal(exact(x,at(16,0),30)); assertEquals("A", overlap.changes.mapNotNull { it.after as? ScheduleSnapshot }.single().overlaps.single().title); store.api.discard(overlap.proposalId)
        store.apply(exact(a, at(16, 0), 35), FrameRestore(snapshot.id, RestoreMode.ALL))
        val resumed = day()
        assertEquals(listOf(a), store.state().queue.map { it.id })
        assertEquals(listOf(Owner.QUEUE, Owner.FRAME), resumed.occurrences.map { it.owner })
        assertEquals(at(16, 0).toInstant().toEpochMilli(), resumed.occurrences.first().startMillis)
        assertEquals(at(16, 35).toInstant().toEpochMilli(), resumed.occurrences.first().endMillis)
        val frames = store.state().frames
        assertEquals(frameBlock.startMillis, (frames.single { it.id == snapshot.id }.items.single().schedule!!.pattern as SchedulePattern.Once).interval.startMillis)
        val savedX = frames.single { it.source == FrameSource.SWAP }.items.single()
        assertEquals(x, savedX.taskId); assertEquals(resumed.occurrences.last().startMillis, (savedX.schedule!!.pattern as SchedulePattern.Once).interval.startMillis)
        store.reopen(); assertEquals(resumed, day())
        store.apply(TaskFinish(a), TaskRemove(x))
        assertEquals(listOf(a), day().occurrences.map { it.taskId }); assertEquals(Owner.DONE, day().occurrences.single().owner)
        reject(SetSchedule(a,null)); reject(exact(a, at(15, 0), 10))
        store.apply(TaskAdd(TaskInput("接替 X", ref = "new")), exact("@new", at(17, 0), 30))
        assertEquals(listOf("A", "接替 X"), day().occurrences.map { it.title })
    }

    @Test fun civilDayUsesTwoLocalMidnightsAndClipsOnlyAtPresentation() = runBlocking {
        val ny = ZoneId.of("America/New_York")
        val spring = LocalDate.of(2026, 3, 8); val fall = LocalDate.of(2026, 11, 1)
        val from = spring.minusDays(1).atTime(23, 45).atZone(ny).toOffsetDateTime()
        val midnight = spring.atStartOfDay(ny).toOffsetDateTime()
        val folds = ny.rules.getValidOffsets(fall.atTime(1, 30))
        store.apply(TaskAdd(TaskInput("跨午夜", ref = "night")), exact("@night", from, 45),
            TaskAdd(TaskInput("回拨之前", ref = "first")), exact("@first", fall.atTime(1, 30).atOffset(folds.first()), 30),
            TaskAdd(TaskInput("回拨之后", ref = "last")), exact("@last", fall.atTime(1, 30).atOffset(folds.last()), 30))
        val firstDay = day(spring.minusDays(1), ny); val nextDay = day(spring, ny); val repeated = day(fall, ny)
        assertEquals(23 * 3_600_000L, nextDay.day.endMillis - nextDay.day.startMillis)
        assertEquals(25 * 3_600_000L, repeated.day.endMillis - repeated.day.startMillis)
        assertEquals(firstDay.occurrences.single(), nextDay.occurrences.single())
        assertEquals(listOf("回拨之前", "回拨之后"), repeated.occurrences.map { it.title })
        assertEquals(3_600_000L, repeated.occurrences.last().startMillis - repeated.occurrences.first().startMillis)
        assertTrue(firstDay.tray.isEmpty()); assertTrue(day(fall.plusDays(1), ny).occurrences.isEmpty())
        val id = requireNotNull(firstDay.occurrences.single().taskId)
        store.apply(exact(id, from, 15))
        assertTrue(day(spring, ny).occurrences.isEmpty()) // End at midnight does not intersect the next day.
        store.apply(exact(id, midnight, 15))
        assertTrue(day(spring.minusDays(1), ny).occurrences.isEmpty())
        val apia = ZoneId.of("Pacific/Apia"); val skipped = LocalDate.of(2011, 12, 30)
        store.apply(exact(id, skipped.minusDays(1).atTime(23, 45).atZone(apia).toOffsetDateTime(), 30))
        assertTrue(day(skipped, apia).occurrences.isEmpty()); assertEquals(id, day(skipped.plusDays(1), apia).occurrences.single().taskId)
    }
}
