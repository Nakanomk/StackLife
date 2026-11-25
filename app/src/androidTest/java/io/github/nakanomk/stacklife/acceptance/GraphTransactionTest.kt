package io.github.nakanomk.stacklife.acceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.courses.BellSchedule
import io.github.nakanomk.stacklife.courses.TermSpec
import io.github.nakanomk.stacklife.courses.campusZone
import io.github.nakanomk.stacklife.courses.meeting
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.GraphEdit.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphTransactionTest {
    @get:Rule val store = WorkspaceFixture()

    private suspend fun graph(id: String) = store.api.observe(PlannerQuery.Graph(id)).first() as GraphDetail
    private suspend fun goal(g: String, n: String) = store.api.observe(PlannerQuery.Goal(g, n)).first() as GoalOrder
    private suspend fun accept(p: PreviewResult.Ready) = assertTrue(store.api.commit(p.proposalId) is CommitResult.Applied)
    private suspend fun source(link: GraphLink) = (store.api.observe(PlannerQuery.Source(link)).first() as SourceView).source
    private suspend fun page(g: String, date: LocalDate, filter: LinkFilter = LinkFilter.ALL, search: String = "", offset: Int = 0) =
        store.api.observe(PlannerQuery.LinkableEvents(g, search, filter, date, offset, 50)).first() as LinkablePage

    @Test fun iconFollowsLiveTaskAndOccurrenceWhileOwnedAndLinkedSnapshotsKeepTheSavedGlyph() = runBlocking {
        val date = LocalDate.of(2026, 9, 7)
        val zone = ZoneId.of("Asia/Shanghai")
        val setup = store.proposal(KeepRestoredFrames(true), TaskAdd(TaskInput("Review", ref = "a", icon = TaskIcon.PERSON)),
            exact("@a", date.atTime(14, 0).atZone(zone).toOffsetDateTime(), 0),
            GraphCreate("Plan", "g"), GraphPatch("@g", listOf(LinkExisting(GraphLink.Task("@a"), ref = "linked"),
                AddNode("Class", ref = "goal", icon = TaskIcon.SCHOOL), AddEdge("@linked", "@goal"))),
            GraphFrame("@g", "@goal", ref = "history"), FramePush("owned", listOf("@a"), ref = "owned"))
        accept(setup)
        val a = setup.refs.getValue("a"); val g = setup.refs.getValue("g")
        store.apply(TaskEdit(a, notes = "Later"), GraphPatch(g, listOf(EditNode(setup.refs.getValue("linked"), icon = TaskIcon.EVENT))),
            FrameRestore(setup.refs.getValue("owned"), RestoreMode.ALL), GraphQueue(g, setup.refs.getValue("goal")))
        store.reopen()
        val workspace = store.state()
        assertEquals(listOf(TaskIcon.EVENT, TaskIcon.SCHOOL), workspace.queue.map { it.icon })
        assertEquals("Later", workspace.queue.first().notes)
        assertEquals(listOf(TaskIcon.PERSON, TaskIcon.SCHOOL), workspace.frames.single { it.id == setup.refs["history"] }.items.map { it.icon })
        val old = workspace.frames.single { it.id == setup.refs["owned"] }.items.single()
        assertEquals(TaskIcon.PERSON, old.icon); assertNull(old.current)
        assertEquals(TaskIcon.EVENT, graph(g).nodes.single { it.link != null }.icon)
        val occurrence = (store.api.observe(PlannerQuery.Day(date, zone)).first() as DayPlan).occurrences.single()
        assertEquals(TaskIcon.EVENT, occurrence.icon); assertTrue(occurrence.point)
        store.apply(TaskEdit(a, icon = TaskIcon.AUTO))
        assertEquals(TaskIcon.AUTO, source(GraphLink.Task(a))!!.icon)
        assertEquals(TaskIcon.AUTO, graph(g).nodes.single { it.link != null }.icon)
    }

    @Test fun pagedPickerSeparatesSameNamedSourcesWithoutDuplicatingOneOffsOrListingCourses() = runBlocking {
        val monday = LocalDate.of(2026, 9, 7)
        val term = TermSpec("Link picker", monday, 1, BellSchedule.SUMMER)
        val label = "候选 %_same"
        val course = term.meeting(1, 1, label, "", 1, 2)
        val start = Instant.ofEpochMilli(course.startMillis).atZone(campusZone)
        val ready = store.proposal(GraphCreate("关联", "g"),
            *Array(53) { TaskAdd(TaskInput(label, ref = "t$it")) },
            exact("@t0", start.toOffsetDateTime(), 0),
            SetSchedule("@t1", SchedulePattern.Daily(listOf(DailySegment(monday.toEpochDay(), start.hour * 60 + start.minute,
                campusZone.id, 0, monday.toEpochDay())))),
            FramePush("旧栈", listOf("@t2")), TaskFinish("@t3"), TaskRemove("@t52"),
            CourseImport(store.api.stageCourseImport(term, listOf(course))))
        accept(ready)
        val r = ready.refs
        val g = r.getValue("g")
        val daily = GraphLink.Occurrence(r.getValue("t1"), monday.toEpochDay())
        val once = GraphLink.Task(r.getValue("t0"))
        store.apply(GraphPatch(g, listOf(LinkExisting(daily), LinkExisting(once))))
        val first = page(g, monday, search = "%_SAME")
        val second = page(g, monday, search = label, offset = 50)
        assertEquals(52, first.total); assertEquals(52, second.total)
        assertEquals(50, first.items.size); assertEquals(2, second.items.size)
        val all = first.items + second.items
        assertEquals(52, all.map { it.link }.toSet().size)
        assertEquals(setOf(once, daily), all.filter { it.linked }.map { it.link }.toSet())
        val displayDate = start.withZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
        assertEquals(setOf(once, daily), page(g, displayDate, LinkFilter.DAY, label).items.map { it.link }.toSet())
        assertEquals(listOf(GraphLink.Task(r.getValue("t2"))), page(g, monday, LinkFilter.FRAME, label).items.map { it.link })
        assertEquals(listOf(GraphLink.Task(r.getValue("t3"))), page(g, monday, LinkFilter.DONE, label).items.map { it.link })
        val queue = page(g, monday, LinkFilter.QUEUE, label)
        assertEquals(50, queue.total); assertTrue(queue.items.any { it.link == daily })
        assertEquals(0, page(g, displayDate.plusWeeks(1), LinkFilter.DAY, label).total)
        assertEquals(0, page(g, monday, search = "no such title or ID").total)
        assertTrue(page(g, monday, search = label, offset = 52).items.isEmpty())
        store.reopen()
        assertEquals(first, page(g, monday, search = label))
        assertEquals(second, page(g, monday, search = label, offset = 50))
    }

    @Test fun mixedGraphGeneratesOnlyTemplatesAndReferencesRemainReadOnlyAfterRestore() = runBlocking {
        val setup = store.proposal(TaskAdd(TaskInput("同名", "队列", ref = "q")),
            FrameCreate("原栈", listOf(TaskInput("同名", "栈内", ref = "fTask")), "oldFrame"),
            TaskAdd(TaskInput("同名", "完成", ref = "done")), TaskFinish("@done"),
            exact("@q", OffsetDateTime.parse("2026-09-07T11:00:00+08:00"), 25),
            GraphCreate("依赖", "g"), GraphCreate("另一个图", "g2"),
            GraphPatch("@g", listOf(LinkExisting(GraphLink.Task("@q"), ref = "a"), LinkExisting(GraphLink.Task("@fTask"), ref = "b"),
                LinkExisting(GraphLink.Task("@done"), ref = "c"), AddNode("模板目标", ref = "d"),
                AddEdge("@a", "@d"), AddEdge("@b", "@d"), AddEdge("@c", "@d"))),
            GraphPatch("@g2", listOf(LinkExisting(GraphLink.Task("@q"), ref = "other"))))
        accept(setup)
        val r = setup.refs
        val q = r.getValue("q"); val held = r.getValue("fTask"); val done = r.getValue("done")
        val g = r.getValue("g"); val d = r.getValue("d")
        val scheduled = store.state().queue.single().schedule
        val oldFrame = store.state().frames.single()
        assertEquals(0, source(GraphLink.Task(q))!!.queuePosition)
        assertEquals(oldFrame.id, source(GraphLink.Task(held))!!.frameId)
        val invalid = listOf(GraphFrame(g, d, taskRefs = mapOf(r.getValue("a") to "copy")),
            GraphQueue(g, r.getValue("a")), GraphPatch(g, listOf(LinkExisting(GraphLink.Task(q)))),
            GraphPatch(g, listOf(SetLinkedCompletion(r.getValue("a"), true), AddEdge(d, r.getValue("a")))),
            GraphPatch(g, listOf(SetLinkedCompletion(d, true))),
            GraphPatch(g, listOf(AddNode("不应保存"), LinkExisting(GraphLink.Task("unknown")))))
        val initial = store.state(); val initialGraph = graph(g)
        for (cmd in invalid) assertTrue("$cmd", store.api.preview(listOf(TaskAdd(TaskInput("不应保存")), cmd)) is PreviewResult.Rejected)
        assertEquals(initial, store.state()); assertEquals(initialGraph, graph(g))

        val stale = store.proposal(GraphQueue(g, d, initial.revision))
        val generate = store.proposal(GraphPatch(g, listOf(MoveNode(r.getValue("b"), 80f, 50f))),
            TaskEdit(q, title = "同批改名", notes = "同批笔记"), GraphQueue(g, d),
            GraphFrame(g, d, "完整顺序", ref = "mixed", taskRefs = mapOf(d to "newOwned")),
            GraphFrame(g, r.getValue("a"), "纯引用", ref = "onlyRefs"))
        val order = generate.changes.mapNotNull { it.after as? GoalGenerationSnapshot }.single { !it.frame }.order
        assertEquals(listOf("a", "b", "c", "d").map(r::getValue), order.map { it.id })
        assertEquals(listOf(Owner.QUEUE, Owner.FRAME, Owner.DONE, null), order.map { it.linkState?.owner })
        assertEquals("同批改名", order.first().title)
        assertTrue(order.first().linkState!!.scheduled); assertTrue(order[2].linkState!!.completed)
        val change = generate.changes.single { it.entityId == g }
        assertEquals("同名", (change.before as GraphSnapshot).nodes.first().title)
        assertEquals("同批改名", (change.after as GraphSnapshot).nodes.first().title)
        accept(generate)
        assertEquals(CommitFailure.STALE_PREVIEW, (store.api.commit(stale.proposalId) as CommitResult.Rejected).code)
        store.reopen()
        val result = store.state()
        assertEquals(listOf("同批改名", "模板目标"), result.queue.map { it.title })
        assertEquals(q, result.queue.first().id); assertEquals(scheduled, result.queue.first().schedule)
        assertEquals(oldFrame, result.frames.single { it.id == oldFrame.id })
        assertEquals(Owner.DONE, source(GraphLink.Task(done))!!.owner)
        val mixed = result.frames.single { it.id == generate.refs.getValue("mixed") }
        assertEquals(listOf(q, held, done, generate.refs.getValue("newOwned")), mixed.items.map { it.taskId })
        assertEquals(listOf("同批笔记", "栈内", "完成", ""), mixed.items.map { it.notes })
        assertEquals(listOf(true, true, true, false), mixed.items.map { it.referenceOnly })
        assertEquals(listOf(false, false, false, true), mixed.items.map { it.restorable })
        val refsOnly = result.frames.single { it.id == generate.refs.getValue("onlyRefs") }
        assertEquals(0, refsOnly.remaining); assertEquals(GraphLink.Task(q), refsOnly.items.single().link)
        assertTrue(store.api.preview(listOf(FrameRestore(refsOnly.id, RestoreMode.ALL))) is PreviewResult.Rejected)
        assertTrue(store.api.preview(listOf(FrameRestore(mixed.id, RestoreMode.SELECTED, listOf(held)))) is PreviewResult.Rejected)
        store.apply(KeepRestoredFrames(true))
        store.apply(FrameRestore(mixed.id, RestoreMode.ALL),
            GraphPatch(g, listOf(EditNode(r.getValue("a"), notes = "共享笔记"), SetLinkedCompletion(r.getValue("b"), true))))
        val restored = store.state()
        assertEquals(listOf(generate.refs.getValue("newOwned")) + result.queue.map { it.id }, restored.queue.map { it.id })
        assertEquals("共享笔记", graph(r.getValue("g2")).nodes.single().notes)
        assertEquals("同批笔记", restored.frames.single { it.id == mixed.id }.items.first().notes)
        assertTrue(source(GraphLink.Task(held))!!.state.completed)
        store.apply(GraphPatch(g, listOf(SetLinkedCompletion(r.getValue("b"), false), RemoveNode(r.getValue("b")))),
            GraphRemove(r.getValue("g2")))
        assertEquals(oldFrame, store.state().frames.single { it.id == oldFrame.id })
        assertEquals("共享笔记", source(GraphLink.Task(q))!!.notes)
        store.apply(TaskRemove(q))
        assertNull(source(GraphLink.Task(q)))
        val missing = graph(g).nodes.single { it.id == r.getValue("a") }
        assertEquals(GraphLink.Task(q), missing.link); assertTrue(missing.linkState!!.missing)
        for (cmd in listOf(GraphQueue(g,d), GraphFrame(g,d))) {
            val error = store.api.preview(listOf(cmd)) as PreviewResult.Rejected
            assertTrue(error.message, error.message.contains(missing.title))
        }
    }

    @Test fun datedLinksKeepOriginalDayAcrossMovesAndCompleteExactlyOneOccurrence() = runBlocking {
        val date = LocalDate.of(2026, 9, 7)
        val firstDay = date.toEpochDay()
        val daily = SchedulePattern.Daily(listOf(DailySegment(firstDay, 12 * 60, campusZone.id, 30, firstDay + 1)))
        val setup = store.proposal(TaskAdd(TaskInput("系列", ref = "series")), SetSchedule("@series", daily),
            GraphCreate("两次发生项", "g"), GraphPatch("@g", listOf(
                LinkExisting(GraphLink.Occurrence("@series", firstDay), ref = "first"),
                LinkExisting(GraphLink.Occurrence("@series", firstDay + 1), ref = "second"), AddEdge("@first", "@second"))))
        accept(setup)
        val r = setup.refs
        val g = r.getValue("g"); val id = r.getValue("series")
        val first = GraphLink.Occurrence(id, firstDay); val second = GraphLink.Occurrence(id, firstDay + 1)
        for (bad in listOf(GraphLink.Task(id), first, GraphLink.Occurrence(id, firstDay + 2))) {
            assertTrue(store.api.preview(listOf(GraphPatch(g, listOf(AddNode("撤销"), LinkExisting(bad))))) is PreviewResult.Rejected)
        }
        val moved = date.plusDays(1).atTime(13,0).atZone(campusZone)
        val instant = moved.toInstant().toEpochMilli()
        store.apply(SetOccurrenceOverride(first.key(), EditScope.THIS, ExactInterval(instant, instant)),
            GraphPatch(g, listOf(EditNode(r.getValue("second"), title = "共享标题"), SetLinkedCompletion(r.getValue("first"), true))),
            GraphFrame(g, r.getValue("second"), "发生项快照"))
        val actual = page(g, moved.withZoneSameInstant(ZoneId.systemDefault()).toLocalDate(), LinkFilter.DAY, "共享标题")
        assertTrue(actual.items.any { it.link == first && it.startMillis == instant && it.state.completed && it.linked })
        val originalDay = page(g, date, LinkFilter.QUEUE, "共享标题").items.single()
        assertEquals(first, originalDay.link); assertEquals(instant, originalDay.startMillis)
        assertTrue(originalDay.linked && originalDay.state.completed)
        assertEquals(second, page(g, date.plusDays(1), LinkFilter.QUEUE, "共享标题").items.single().link)
        val series = goal(g, r.getValue("second")).nodes
        assertEquals(listOf(first, second), series.map { it.link })
        assertEquals(listOf("共享标题", "共享标题"), series.map { it.title })
        assertEquals(listOf(true, false), series.map { it.linkState!!.completed })
        val snapshot = store.state().frames.single()
        assertEquals(listOf(first, second), snapshot.items.map { it.link }); assertEquals(0, snapshot.remaining)
        store.apply(SetOccurrenceOverride(first.key(), EditScope.THIS))
        store.reopen()
        assertNull(source(first)); assertNotNull(source(second))
        assertEquals(listOf(true, false), graph(g).nodes.map { it.linkState!!.missing })
        assertEquals(snapshot, store.state().frames.single())
        assertEquals(1, store.state().queue.size)
        assertTrue(store.api.preview(listOf(GraphFrame(g, r.getValue("second")))) is PreviewResult.Rejected)
        store.apply(GraphPatch(g, listOf(RemoveNode(r.getValue("first")))))
        assertEquals(listOf(second), goal(g, r.getValue("second")).nodes.map { it.link })
        assertFalse(source(second)!!.state.completed)
        assertEquals(0, source(second)!!.queuePosition)
        store.apply(FramePush("系列原栈", listOf(id)))
        assertEquals(store.state().frames.single { it.reason == "系列原栈" }.id, source(second)!!.frameId)
        assertNull(source(second)!!.queuePosition)
    }

    @Test fun systemOccurrencesHaveNoFakeTaskAndLinkKindNeverChangesWithTheSourceRule() = runBlocking {
        val date = LocalDate.of(2026, 9, 7)
        val day = date.toEpochDay()
        val daily = SchedulePattern.Daily(listOf(DailySegment(day, 420, campusZone.id, 0)))
        val setup = store.proposal(GraphCreate("来源类型", "g"), TaskAdd(TaskInput("独立任务", ref = "t")),
            GraphPatch("@g", listOf(LinkExisting(GraphLink.Task("@t"), ref = "n"))))
        accept(setup)
        val g = setup.refs.getValue("g"); val id = setup.refs.getValue("t")
        val routine = GraphLink.Occurrence(RoutineKind.WAKE.scheduleId, day)
        store.apply(SetSchedule(id, daily), SetSchedule(routine.scheduleId, daily),
            GraphPatch(g, listOf(LinkExisting(routine, ref = "r"))), GraphFrame(g, "@r"))
        val oldLink = graph(g).nodes.single { it.id == setup.refs.getValue("n") }
        assertEquals(GraphLink.Task(id), oldLink.link)
        assertFalse(oldLink.linkState!!.missing)
        val system = graph(g).nodes.single { it.link == routine }
        assertTrue(system.linkState!!.system); assertNull(source(routine)!!.taskId)
        assertTrue(page(g, date).items.any { it.link == routine && it.linked && it.state.system })
        val frame = store.state().frames.single()
        assertEquals(routine, frame.items.single().link)
        assertEquals(0, frame.remaining); assertEquals(listOf(id), store.state().queue.map { it.id })
        assertTrue(store.api.preview(listOf(GraphPatch(g, listOf(EditNode(system.id, "不是任务"))))) is PreviewResult.Rejected)
        store.apply(GraphPatch(g, listOf(SetLinkedCompletion(system.id, true))))
        assertTrue(source(routine)!!.state.completed)
        assertFalse(source(routine.copy(originalDate = day + 1))!!.state.completed)
        val rejected = store.api.preview(listOf(TaskAdd(TaskInput("临时来源", ref = "temporary")),
            GraphPatch(g, listOf(LinkExisting(GraphLink.Task("@temporary")))), TaskRemove("@temporary")))
        assertTrue(rejected is PreviewResult.Rejected)
        store.apply(SetSchedule(routine.scheduleId, null))
        store.reopen()
        assertEquals(2, graph(g).nodes.size)
        assertTrue(graph(g).nodes.single { it.id == system.id }.linkState!!.missing)
        assertEquals(frame.items, store.state().frames.single().items)
    }

    @Test fun diamondSkipsUnrelatedTemplatesAndNeverRewritesFramesOrQueue() = runBlocking {
        store.apply(TaskAdd(TaskInput("眼前的任务")))
        val queue = store.state().queue
        val plan = store.proposal(GraphCreate("展示准备", "g"), GraphPatch("@g", listOf(
            AddNode("A", "原始备注", 15, ref = "a"), AddNode("B", ref = "b"), AddNode("C", ref = "c"),
            AddNode("D", ref = "d"), AddNode("X", ref = "x"), AddEdge("@a", "@c"), AddEdge("@a", "@b"),
            AddEdge("@c", "@d"), AddEdge("@b", "@d"))))
        val ids = plan.refs
        val g = ids.getValue("g"); val a = ids.getValue("a"); val b = ids.getValue("b")
        val c = ids.getValue("c"); val d = ids.getValue("d"); val x = ids.getValue("x")
        assertNull(graph(g).graph)
        accept(plan)
        val order = goal(g, d)
        assertEquals(listOf(a, b, c, d), order.nodes.map { it.id })
        val stale = store.proposal(GraphFrame(g, d, expectedRevision = order.revision))
        store.apply(GraphPatch(g, listOf(MoveNode(c, -400f, 900f))))
        assertEquals(CommitFailure.STALE_PREVIEW, (store.api.commit(stale.proposalId) as CommitResult.Rejected).code)
        assertTrue(store.api.preview(listOf(GraphFrame(g, d, expectedRevision = order.revision))) is PreviewResult.Rejected)
        assertTrue(store.api.preview(listOf(GraphPatch(g, listOf(MoveNode(c, 0f, 0f)), order.revision))) is PreviewResult.Rejected)
        assertTrue(store.state().frames.isEmpty())
        assertEquals(order.nodes.map { it.id }, goal(g, d).nodes.map { it.id })
        val moved = graph(g).nodes.single { it.id == c }
        assertEquals(-400f, moved.x, 0f)
        assertEquals(900f, moved.y, 0f)
        val ready = store.proposal(GraphFrame(g, d, ref = "f"))
        assertTrue(store.state().frames.isEmpty())
        accept(ready)
        assertEquals(CommitFailure.UNKNOWN_PROPOSAL, (store.api.commit(ready.proposalId) as CommitResult.Rejected).code)
        val frame = store.state().frames.single()
        assertEquals(ready.refs.getValue("f"), frame.id)
        assertEquals(FrameSource.GRAPH, frame.source)
        assertEquals("为目标〈D〉生成", frame.reason)
        assertEquals(listOf("A", "B", "C", "D"), frame.items.map { it.title })
        assertEquals(listOf(0, 1, 2, 3), frame.items.map { it.originalPosition })
        assertEquals("原始备注", frame.items.first().notes)
        assertEquals(15, frame.items.first().minutes)
        assertTrue(frame.items.all { it.restorable && it.taskId !in ids.values })
        store.apply(GraphFrame(g, d, "重做展示"), GraphFrame(g, x, "只做孤点"))
        val again = store.state().frames.single { it.reason == "重做展示" }
        assertTrue(again.items.none { item -> frame.items.any { it.taskId == item.taskId } })
        assertEquals(listOf("X"), store.state().frames.single { it.reason == "只做孤点" }.items.map { it.title })
        val saved = store.state().frames
        store.apply(GraphPatch(g, listOf(EditNode(a, "新标题", "新备注", 90), RemoveNode(b), Rename("新图名"))))
        assertEquals(setOf(GraphEdge(a, c), GraphEdge(c, d)), graph(g).edges.toSet())
        assertEquals(listOf("新标题", "C", "D"), goal(g, d).nodes.map { it.title })
        val removal = store.proposal(GraphPatch(g, listOf(RemoveEdge(c, d))))
        val change = removal.changes.single()
        assertEquals(g, change.entityId)
        val original = change.before as GraphSnapshot
        assertEquals("新图名", original.title)
        assertEquals(original.copy(edges = original.edges.filterNot { it == GraphEdge(c, d) }), change.after)
        accept(removal)
        assertEquals(listOf(d), goal(g, d).nodes.map { it.id })
        store.apply(GraphRemove(g))
        store.reopen()
        assertNull(graph(g).graph)
        assertNotNull(goal(g, d).error)
        assertEquals(saved, store.state().frames)
        assertEquals(queue, store.state().queue)
    }

    @Test fun oneBatchCanInstantiateRestoreAndScheduleTheSameTaskByAlias() = runBlocking {
        val time = OffsetDateTime.parse("2026-09-07T11:00:00+08:00")
        val p = store.proposal(GraphCreate("路径", "g"), GraphPatch("@g", listOf(
            AddNode("准备", ref = "start"), AddNode("交付", ref = "goal"), AddEdge("@start", "@goal"))),
            GraphFrame("@g", "@goal", ref = "f", taskRefs = mapOf("@start" to "task")),
            FrameRestore("@f", RestoreMode.SELECTED, listOf("@task")), exact("@task", time, 25))
        val r = p.refs
        assertTrue(store.state().queue.isEmpty())
        accept(p)
        store.reopen()
        val state = store.state(); val task = state.queue.single(); val frame = state.frames.single()
        assertEquals(p.baseRevision + 1, state.revision)
        assertEquals(r.getValue("task"), task.id)
        assertEquals(time.toInstant().toEpochMilli(), (task.schedule!!.pattern as SchedulePattern.Once).interval.startMillis)
        assertEquals(r.getValue("f"), frame.id)
        assertEquals(task.id, frame.items.first().taskId)
        assertEquals(listOf(false, true), frame.items.map { it.restorable })
        assertNull(frame.items.first().schedule)
        assertEquals(listOf(r.getValue("start"), r.getValue("goal")), goal(r.getValue("g"), r.getValue("goal")).nodes.map { it.id })
    }

    @Test fun rejectedEdgesAndStorageFailuresPreserveGraphAndFrameAtomically() = runBlocking {
        val p = store.proposal(GraphCreate("本图", "g"), GraphCreate("另图", "h"), GraphPatch("@h", listOf(AddNode("外部", ref = "x"))),
            GraphPatch("@g", listOf(AddNode("A", ref = "a"), AddNode("B", ref = "b"), AddEdge("@a", "@b"))))
        accept(p)
        val g = p.refs.getValue("g"); val a = p.refs.getValue("a"); val b = p.refs.getValue("b"); val x = p.refs.getValue("x")
        val before = graph(g); val state = store.state()
        val bad = listOf(GraphPatch(g, listOf(AddEdge(b, a))), GraphPatch(g, listOf(AddEdge(a, b))),
            GraphPatch(g, listOf(AddEdge(a, a))), GraphPatch(g, listOf(AddEdge(a, x))), GraphPatch(g, listOf(AddEdge("missing", b))),
            GraphFrame(g, a, " "), GraphFrame(g, a, taskRefs = mapOf(b to "outside")),
            GraphFrame(g, b, taskRefs = mapOf(a to "same", b to "same")), GraphPatch(g, listOf(MoveNode(a, Float.NaN, 0f))),
            GraphPatch(g, listOf(AddNode("N", ref = "n"), AddEdge("@n", "@undeclared"))))
        for (cmd in bad) {
            val result = store.api.preview(listOf(TaskAdd(TaskInput("不可留下")), cmd))
            assertTrue("$cmd returned $result", result is PreviewResult.Rejected)
        }
        assertEquals(before, graph(g)); assertEquals(state, store.state())
        val fail = store.proposal(GraphPatch(g, listOf(AddNode("C", ref = "c"), AddEdge(b, "@c"))), GraphFrame(g, "@c"))
        store.db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_graph_edge BEFORE INSERT ON graph_edges
            BEGIN SELECT RAISE(ABORT, 'injected edge failure'); END""")
        assertEquals(CommitFailure.STORAGE, (store.api.commit(fail.proposalId) as CommitResult.Rejected).code)
        store.reopen()
        assertEquals(before, graph(g)); assertEquals(state, store.state())
    }
}
