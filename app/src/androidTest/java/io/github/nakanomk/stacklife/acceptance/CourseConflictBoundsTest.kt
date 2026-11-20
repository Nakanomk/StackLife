package io.github.nakanomk.stacklife.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseConflictBoundsTest {
    @get:Rule val store = WorkspaceFixture()
    private val term = TermSpec("甲", LocalDate.of(2026, 9, 7), 18, BellSchedule.SUMMER)
    private val monday = term.firstMonday.atStartOfDay(campusZone).toOffsetDateTime()
    private suspend fun day() = store.api.observe(PlannerQuery.Day(term.firstMonday, campusZone)).first() as DayPlan
    private suspend fun preview(spec: TermSpec, rows: List<CourseMeeting>, vararg before: PlannerCommand): PreviewResult {
        val id = store.api.stageCourseImport(spec, rows)
        return try { store.api.preview(before.toList() + CourseImport(id)) } finally { store.api.discardCourseImport(id) }
    }
    private fun simultaneous(spec: TermSpec, count: Int) = List(count) { spec.meeting(1, 1, "课$it", "A", 1, 1) }
    private suspend fun commit(preview: PreviewResult) {
        assertTrue(preview is PreviewResult.Ready)
        assertTrue(store.api.commit((preview as PreviewResult.Ready).proposalId) is CommitResult.Applied)
    }

    @Test fun mixedTermsCountExactPartnersAndRejectAnUnreviewableBatchWithoutWrites() = runBlocking {
        store.apply(TaskAdd(TaskInput("重叠任务", ref = "a")), SlotSet("@a", monday.plusHours(8), 10),
            TaskAdd(TaskInput("端点任务", ref = "b")), SlotSet("@b", monday.plusHours(8).plusMinutes(45), 10))
        commit(preview(term, simultaneous(term, 9)))
        val other = term.copy(id = "乙")
        val second = preview(other, simultaneous(other, 7)) as PreviewResult.Ready
        // 7 new courses: each meets 9 existing courses and one task, plus 21 new/new pairs.
        assertEquals(91, second.changes.count { it.field.startsWith("冲突 ·") }); commit(second)
        val before = day(); val work = store.state()
        assertEquals(16, before.courses.size)
        assertTrue(before.courses.all { it.conflicts.single() == "共 16 个重叠的课程或任务，未逐项列出" })
        assertEquals(setOf(work.queue.first { it.title == "重叠任务" }.id), before.conflictingTaskIds)
        val week = store.api.observe(PlannerQuery.Week(other.id, 1)).first() as CourseWeek
        assertEquals(before.courses.filter { it.termId == other.id }, week.classes)

        val candidate = term.copy(id = "丙")
        val denied = preview(candidate, simultaneous(candidate, 14), TaskAdd(TaskInput("不应留下"))) as PreviewResult.Rejected
        assertTrue(denied.message, "超过 256 对" in denied.message)
        val moving = work.queue.first { it.title == "端点任务" }.id
        val rejectedSlot = store.api.preview(listOf(SlotSet(moving, monday.plusHours(8), 10))) as PreviewResult.Rejected
        assertTrue(rejectedSlot.message, "另有 9 个冲突未列出" in rejectedSlot.message)
        assertEquals(8, rejectedSlot.message.count { it == '「' })
        assertEquals(before, day()); assertEquals(work, store.state())
        assertNull((store.api.observe(PlannerQuery.Week(candidate.id, 1)).first() as CourseWeek).term)
        store.apply(TermRemove(other.id))
        assertEquals(9, day().courses.size)
    }

    @Test fun manyDisjointRowsDoNotConsumeTheConflictBudget() = runBlocking {
        val rows = buildList {
            for (week in 1..13) for (day in 1..7) for (period in 1..3) {
                add(term.meeting(week, day, "第 $period 节", "室", period, period))
            }
        }
        val ready = preview(term, rows) as PreviewResult.Ready
        assertEquals(273, ready.changes.count { it.field.startsWith("新增课程") })
        assertFalse(ready.changes.any { it.field.startsWith("冲突") }); commit(ready)
        val last = store.api.observe(PlannerQuery.Week(term.id, 13)).first() as CourseWeek
        assertEquals(21, last.classes.size); assertTrue(last.classes.all { it.conflicts.isEmpty() })
    }

    @Test fun oversizedSourceFieldsAreRejectedAndLegacyLabelsDoNotMultiplyFullText() = runBlocking {
        val accepted = "甲".repeat(256)
        val rows = listOf(term.meeting(1, 1, accepted, "室", 1, 1), term.meeting(1, 1, "短名", "室", 1, 1))
        commit(preview(term, rows)); val before = day()
        assertTrue(before.courses.any { it.meeting.title == accepted })
        val long = "长".repeat(4096)
        for (row in listOf(rows.first().copy(title = long), rows.first().copy(room = long))) {
            val denied = preview(term, listOf(row)) as PreviewResult.Rejected
            assertTrue(denied.message, "超过 256" in denied.message)
            assertFalse(long in denied.message)
        }
        assertEquals(before, day())
        // A previous app version could store this value. Reading a diagnosis must not rewrite it;
        // it must remain possible to preview removal even when reimporting this row is disallowed.
        store.db.openHelper.writableDatabase.execSQL("UPDATE course_meetings SET title=? WHERE title=?", arrayOf(long, accepted))
        val old = day().courses
        assertEquals(long, old.first { it.meeting.title != "短名" }.meeting.title)
        val caption = old.first { it.meeting.title == "短名" }.conflicts.single()
        assertTrue(caption, "…" in caption && caption.length < 300)
        store.apply(TermRemove(term.id)); assertTrue(day().courses.isEmpty())
    }
}
