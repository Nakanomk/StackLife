package io.github.nakanomk.stacklife.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StarterPlanTransactionTest {
    @get:Rule val store = WorkspaceFixture()
    private val today = LocalDate.of(2027, 12, 29)
    private val optIn = CompleteOnboarding(starterDay = today)

    private suspend fun settings() = store.api.observe(PlannerQuery.Settings).first() as PolicyView
    private suspend fun graphs() = (store.api.observe(PlannerQuery.Graphs).first() as GraphList).graphs
    private suspend fun countdowns() = (store.api.observe(PlannerQuery.Countdowns).first() as CountdownsView).items

    @Test fun confirmationAppendsOrderedLessonsAndARestorableFrameWithoutScheduling() = runBlocking {
        store.apply(TaskAdd(TaskInput("Call Zoë", "Private notes", 15)), TaskAdd(TaskInput("Book appointment")))
        val original = store.state().queue
        val visible = setOf(Destination.WORKSPACE, Destination.GRAPHS)
        assertEquals(0, settings().onboardingVersion)
        store.apply(optIn.copy(visible = visible, baseline = settings().appearance))
        val seeded = store.state()
        val lessons = seeded.queue.drop(original.size)
        assertEquals(original, seeded.queue.take(original.size))
        assertEquals(4, lessons.size)
        assertEquals(4, lessons.map { it.title }.distinct().size)
        assertEquals(4, lessons.map { it.notes }.distinct().size)
        // Check the topic sequence, not a particular translation or the exact wording of a title.
        val topics = listOf("队列|queue", "栈|frame", "流程图|依赖|graph|prerequisite", "倒数|倒计|countdown")
        for ((lesson, topic) in lessons.zip(topics)) {
            assertTrue(lesson.title, Regex(topic, RegexOption.IGNORE_CASE).containsMatchIn("${lesson.title} ${lesson.notes}"))
            assertTrue(lesson.title, lesson.notes.isNotBlank() && lesson.notes != lesson.title)
            assertNull(lesson.schedule)
        }
        assertTrue(Regex("笔记|备注|note", RegexOption.IGNORE_CASE).containsMatchIn(lessons.first().notes))
        val frame = seeded.frames.single()
        assertEquals(FrameSource.MANUAL, frame.source)
        assertEquals(2, frame.items.size)
        assertEquals(2, frame.remaining)
        val held = frame.items.map { requireNotNull(it.current) }
        assertEquals(2, held.map { it.title }.distinct().size)
        val allTasks = seeded.queue + held
        assertEquals(allTasks.size, allTasks.distinctBy { it.id }.size)
        assertTrue(held.all { it.schedule == null })

        val countdown = countdowns().single()
        val newYearDate = LocalDate.of(2028, 1, 5)
        assertEquals(newYearDate, LocalDate.ofEpochDay(countdown.targetDay))
        assertEquals(DayAmount(7, DayDirection.UNTIL), countdown.amount(today))
        assertEquals(CountdownMode.COUNT_DOWN, countdown.mode)
        assertFalse(countdown.pinned)
        for (date in listOf(today, newYearDate)) {
            val day = store.api.observe(PlannerQuery.Day(date, ZoneId.of("Pacific/Auckland"))).first() as DayPlan
            assertTrue("Unexpected events on $date: ${day.occurrences}", day.occurrences.isEmpty())
            assertEquals(seeded.queue.map { it.id }, day.tray.map { it.id })
        }
        store.reopen()
        assertEquals(1, settings().onboardingVersion)
        assertEquals(visible, settings().appearance.visible)
        assertEquals(seeded, store.state())
        assertEquals(listOf(countdown), countdowns())
        store.apply(FrameRestore(frame.id, RestoreMode.ALL))
        assertEquals(held + seeded.queue, store.state().queue)
        assertEquals(0, store.state().frames.single().remaining)
    }

    @Test fun bothPrerequisitesGenerateFreshQueueAndFrameTasksInStableOrder() = runBlocking {
        store.apply(optIn)
        val id = graphs().single().id
        val graph = store.api.observe(PlannerQuery.Graph(id)).first() as GraphDetail
        assertEquals(3, graph.nodes.size)
        assertEquals(2, graph.edges.size)
        assertTrue(graph.nodes.all { it.link == null })
        val goal = graph.nodes.single { node -> graph.edges.none { it.from == node.id } }
        val sources = graph.nodes.filter { it.id != goal.id }.sortedBy { it.creationOrder }
        assertTrue(sources[0].creationOrder < sources[1].creationOrder)
        assertEquals(sources.map { GraphEdge(it.id, goal.id) }.toSet(), graph.edges.toSet())
        val order = sources + goal
        val expected = order.map { TaskInput(it.title, it.notes, it.minutes) }
        val before = store.state()
        store.reopen()
        val reviewed = store.api.observe(PlannerQuery.Goal(id, goal.id)).first() as GoalOrder
        assertNull(reviewed.error)
        assertEquals(order, reviewed.nodes)

        store.apply(GraphFrame(id, goal.id))
        val withFrame = store.state()
        assertEquals(before.queue, withFrame.queue)
        val generated = withFrame.frames.single { it.source == FrameSource.GRAPH }
        assertEquals(before.frames, withFrame.frames.filterNot { it.id == generated.id })
        assertEquals(3, generated.remaining)
        assertEquals(expected, generated.items.map { TaskInput(it.title, it.notes, it.minutes) })
        store.apply(GraphQueue(id, goal.id))
        val withQueue = store.state()
        assertEquals(withFrame.frames, withQueue.frames)
        assertEquals(before.queue, withQueue.queue.take(before.queue.size))
        val added = withQueue.queue.drop(before.queue.size)
        assertEquals(expected, added.map { TaskInput(it.title, it.notes, it.minutes) })
        val taskIds = withQueue.queue.map { it.id } + withQueue.frames.flatMap { f -> f.items.map { it.taskId } }
        assertEquals(taskIds.size, taskIds.distinct().size)
        assertTrue(taskIds.none { task -> order.any { node -> task == node.id } })
    }

    @Test fun reopenedGuideDoesNotReplaceEditsOrRepopulateAClearedQueue() = runBlocking {
        store.apply(optIn)
        val initial = store.state()
        val frame = initial.frames.single()
        val task = frame.items.first().taskId
        val graph = graphs().single()
        val editedNotes = "My revised plan"
        store.apply(TaskEdit(task, notes = editedNotes, expectedFrameId = frame.id),
            GraphPatch(graph.id, listOf(GraphEdit.Rename("My workflow"))), CountdownRemove(countdowns().single().id),
            *initial.queue.map { TaskRemove(it.id) }.toTypedArray())
        val edited = store.state()
        assertTrue(edited.queue.isEmpty())
        assertEquals(editedNotes, edited.frames.single().items.single { it.taskId == task }.current?.notes)
        val expectedGraphs = listOf(graph.copy(title = "My workflow"))
        store.reopen()
        assertEquals(edited, store.state())
        assertEquals(expectedGraphs, graphs())
        assertTrue(countdowns().isEmpty())
        store.apply(optIn.copy(starterDay = today.plusMonths(1)))
        store.reopen()
        val actual = store.state()
        assertEquals(edited.queue, actual.queue)
        assertEquals(edited.frames, actual.frames)
        assertEquals(expectedGraphs, graphs())
        assertTrue(countdowns().isEmpty())
        assertEquals(1, settings().onboardingVersion)
    }

    @Test fun abandonedAndStaleConfirmationsLeaveOnlyTheWinningChanges() = runBlocking {
        val initial = store.state()
        val originalSettings = settings()
        assertEquals(0, originalSettings.onboardingVersion)
        val command = optIn.copy(visible = setOf(Destination.WORKSPACE), baseline = originalSettings.appearance)
        suspend fun assertUntouched(expected: Workspace, settingsBefore: PolicyView) {
            assertEquals(expected, store.state())
            assertEquals(settingsBefore, settings())
            assertTrue(graphs().isEmpty())
            assertTrue(countdowns().isEmpty())
        }
        val abandoned = store.proposal(command)
        assertUntouched(initial, originalSettings)
        store.api.discard(abandoned.proposalId)
        assertEquals(CommitFailure.UNKNOWN_PROPOSAL, (store.api.commit(abandoned.proposalId) as CommitResult.Rejected).code)
        val stale = store.proposal(command)
        store.apply(TaskAdd(TaskInput("Recorded during the guide")))
        val personal = store.state()
        assertEquals(CommitFailure.STALE_PREVIEW, (store.api.commit(stale.proposalId) as CommitResult.Rejected).code)
        store.reopen()
        assertUntouched(personal, originalSettings)

        val competing = store.proposal(command)
        store.apply(CompleteOnboarding())
        assertEquals(CommitFailure.STALE_PREVIEW, (store.api.commit(competing.proposalId) as CommitResult.Rejected).code)
        store.apply(optIn)
        store.reopen()
        val after = store.state()
        assertEquals(personal.queue, after.queue)
        assertTrue(after.frames.isEmpty())
        assertTrue(graphs().isEmpty())
        assertTrue(countdowns().isEmpty())
        assertEquals(originalSettings.copy(onboardingVersion = 1), settings())
    }
}
