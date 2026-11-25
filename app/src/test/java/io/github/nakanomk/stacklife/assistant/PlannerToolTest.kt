package io.github.nakanomk.stacklife.assistant

import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlannerToolTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = AssistantPage.Day(LocalDate.parse("2026-11-19"), zone)
    private val current = obj("""{"kind":"current"}""")
    private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun cmd(raw: String) = obj("""{"commands":[$raw]}""")
    private fun remove(id: String) = cmd("""{"kind":"task_remove","taskId":"$id"}""")
    private inline fun denied(call: () -> Unit) {
        try { call(); fail("Expected tool rejection") } catch (_: IllegalArgumentException) { }
    }

    // Unexpected queries (including Workspace) and all writes are test failures, not canned successes.
    private class Reads(vararg datasets: Pair<PlannerQuery, QueryResult>) : PlannerApi {
        private val queries = datasets.toMap()
        override fun observe(query: PlannerQuery): Flow<QueryResult> = flowOf(queries.getValue(query))
        override suspend fun preview(commands: List<PlannerCommand>): PreviewResult = error("tool preview")
        override suspend fun commit(proposalId: String): CommitResult = error("tool commit")
        override suspend fun applyWorkspaceReceipt(requestId: String, action: WorkspaceReceiptAction): CommitResult = error("tool has no receipt action")
        override fun discard(proposalId: String): Unit = error("tool discard")
        override fun stageCourseImport(term: TermSpec, meetings: List<CourseMeeting>, source: String): String = error("tool import")
        override fun discardCourseImport(draftId: String): Unit = error("tool import discard")
    }

    @Test fun onlyReturnedIdsBecomeCapabilitiesEvenWithExtraReadArea() = runBlocking {
        val queue = QueueView(listOf(QueueTask("a", "A", "private-a", 15), QueueTask("b", "B", "private-b", 30)))
        val tools = PlannerTools(Reads(PlannerQuery.Queue to queue), day, Disclosure(extra = setOf(ReadArea.WORKSPACE)))
        denied { tools.proposal(remove("a")) }
        val first = tools.query(obj("""{"kind":"queue","limit":1}""")).jsonObject
        assertEquals(1, first.getValue("nextOffset").jsonPrimitive.int)
        assertFalse(first.toString().contains("private")); assertFalse(first.toString().contains("\"notes\""))
        assertEquals(ToolPlan.Commands(listOf(PlannerCommand.TaskRemove("a"))), tools.proposal(remove("a")))
        denied { tools.proposal(remove("b")) }
        tools.query(obj("""{"kind":"queue","offset":1,"limit":1}"""))
        assertEquals(ToolPlan.Commands(listOf(PlannerCommand.TaskRemove("b"))), tools.proposal(remove("b")))
        denied { tools.query(obj("""{"kind":"day","date":"2026-11-18"}""")) }
        denied { tools.proposal(cmd("""{"kind":"graph_remove","graphId":"a"}""")) }
    }

    @Test fun countdownScopesGrantOnlyReturnedCardsAndNeverTheirPrivateBackgroundFiles() = runBlocking {
        val photo = "dd957e19-e907-4e7c-a513-707fb834162b"
        val card = Countdown("c", "Trip", day.date.toEpochDay(), CountdownMode.COUNT_DOWN, true, 0, photo)
        val other = card.copy(id = "other", position = 1)
        val data = Reads(PlannerQuery.Countdowns to CountdownsView(7, listOf(card, other)))
        val tools = PlannerTools(data, AssistantPage.Countdowns, Disclosure())
        denied { PlannerTools(data, day, Disclosure()).query(obj("""{"kind":"countdowns"}""")) }
        val view = tools.query(obj("""{"kind":"current","limit":1}"""))
        assertFalse(photo in view.toString()); assertTrue(view.jsonObject.getValue("rows").jsonArray[0].jsonObject.getValue("hasBackground").jsonPrimitive.boolean)
        fun edit(id:String, field:String) = cmd("""{"kind":"countdown_edit","countdownId":"$id",$field}""")
        denied { tools.proposal(edit("other", "\"title\":\"changed\"")) }
        denied { tools.proposal(edit("c", "\"backgroundId\":\"$photo\"")) }
        assertEquals(ToolPlan.Commands(listOf(PlannerCommand.CountdownEdit("c", title="Revised"))), tools.proposal(edit("c", "\"title\":\"Revised\"")))
        assertEquals(ToolPlan.Commands(listOf(PlannerCommand.CountdownEdit("c", background=BackgroundChange(null)))), tools.proposal(edit("c", "\"removeBackground\":true")))
    }
    @Test fun dayUsesOnlyDayPlanAndCoursesStayAnonymous() = runBlocking {
        val read = PlannerQuery.Day(day.date, day.zone)
        val start = read.startMillis
        val course = ScheduledCourse("secret-term", "source", CourseMeeting(1, 4, "secret-title", "secret-room", 1, 2, start, start + 3600000), listOf("secret-conflict"))
        val key = OccurrenceKey.Course(CourseOccurrenceId.of(course.termId,course.source,course.meeting))
        val event = AgendaOccurrence(key,course.meeting.title,"",start,course.meeting.endMillis,false,7,course = course)
        val source = Reads(read to DayPlan(read,5,listOf(QueueTask("a","A","secret-note",30)),listOf(event)))
        val tools = PlannerTools(source, day, Disclosure())
        val response = tools.query(current).jsonObject; val result = response.toString()
        val token = response.getValue("rows").jsonArray.first().jsonObject.getValue("occurrenceId").jsonPrimitive.content
        val check = cmd("""{"kind":"set_occurrence_completion","occurrenceId":"$token","completed":true}""")
        assertEquals(ToolPlan.Commands(listOf(PlannerCommand.SetOccurrenceCompletion(key,true))),tools.proposal(check))
        denied { PlannerTools(source,day,Disclosure()).proposal(check) }
        assertTrue("busy" in result && "tray" in result && "start" in result); assertFalse("secret-" in result)
        denied { tools.query(obj("""{"kind":"task","taskId":"a"}""")) }
        denied { tools.query(obj("""{"kind":"terms"}""")) }
        val more = PlannerTools(source, day, Disclosure(notes = true, courseDetails = true)).query(current).toString()
        assertTrue("secret-note" in more && "secret-title" in more && "secret-room" in more)
        assertFalse("secret-conflict" in more || "secret-term" in more)
    }

    @Test fun frameListAndHistoryDoNotExposeCurrentTasksOutsideFrame() = runBlocking {
        val frame = FrameView("f", "private-reason", 0, FrameSource.PUSH, listOf(
            FrameEntry("active", 0, "A", "private-notes", 15, null, QueueTask("active", "Edited A", "private-live", 20)),
            FrameEntry("departed", 1, "B", "private-notes", 30, null, null)))
        val index = FrameIndex(listOf(FrameSummary("f", frame.reason, 0, frame.source, 2, 1)))
        val source = Reads(PlannerQuery.Frames to index, PlannerQuery.Frame("f") to FrameDetailResult(frame))
        val list = PlannerTools(source, AssistantPage.Frames, Disclosure())
        val summary = list.query(current).toString()
        assertFalse("private" in summary || "active" in summary); denied { list.query(obj("""{"kind":"frame","frameId":"f"}""")) }
        val detail = PlannerTools(source, AssistantPage.Frame("f"), Disclosure(reasons = true))
        val visible = detail.query(current).toString()
        assertTrue("private-reason" in visible && "Edited A" in visible)
        assertFalse("departed" in visible || "private-notes" in visible || "private-live" in visible)
        detail.proposal(remove("active")); denied { detail.proposal(remove("departed")) }
    }

    @Test fun pushReasonIsOptionalAndAmendmentRequiresAKnownFrame() {
        val tools = PlannerTools(Reads(), AssistantPage.Task("t"), Disclosure())
        assertEquals(ToolPlan.Commands(listOf(PlannerCommand.FramePush(taskIds = listOf("t"), ref = "saved"),
            PlannerCommand.FrameAddReason("@saved", "等回电"))), tools.proposal(cmd("""
            {"kind":"frame_push","taskIds":["t"],"ref":"saved"},
            {"kind":"frame_add_reason","frameId":"@saved","reason":"等回电"}
        """)))
        denied { tools.proposal(cmd("""{"kind":"frame_add_reason","frameId":"unknown","reason":"冒用"}""")) }
        denied { tools.proposal(cmd("""{"kind":"frame_create","tasks":[{"title":"A"}]}""")) }
    }

    @Test fun protocolRejectsMalformedAndPrivilegedInputs() = runBlocking {
        val tools = PlannerTools(Reads(), AssistantPage.Task("t"), Disclosure())
        for (input in listOf(
            """{"kind":"task_add","title":"X","minutes":"30"}""", """{"kind":"task_add","title":true}""",
            """{"kind":"task_finish","taskId":"t","apply":true}""", """{"kind":"commit","proposalId":"abc"}""",
            """{"kind":"queue_order","taskIds":[null]}""", """{"kind":"prefs_set","group":"privacy","restorePolicy":"SWAP"}""",
            """{"kind":"prefs_reset"}""", """{"kind":"set_schedule","scheduleId":"t","pattern":{"kind":"once","start":"2026-11-19T16:00","durationMinutes":5}}""",
            """{"kind":"task_add","title":"X","minutes":-1}""", """{"kind":"set_occurrence_completion","occurrenceId":"invented","completed":true}""",
            """{"kind":"frame_restore","frameId":"@f","mode":"selected"}""",
            """{"kind":"graph_patch","graphId":"@g","edits":[{"kind":"add_node","title":"n","script":"sh"}]}"""
        )) denied { tools.proposal(cmd(input)) }
        for (input in listOf("""{"kind":"current","sql":"all"}""", """{"kind":"current","limit":500}""",
            """{"kind":"day","date":"2026-02-30"}""", """{"kind":"task","taskId":false}""")) denied { tools.query(obj(input)) }
        assertEquals(listOf("planner_query", "planner_propose"), plannerToolDefinitions.map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content })
    }

    @Test fun appearanceScopeIsExplicitAndPatchesCannotResetUnmentionedFields() = runBlocking {
        val initial = Appearance(glass = true, highContrast = true, compact = true)
        val prefs = Reads(PlannerQuery.Settings to PolicyView(RestorePolicy.SWAP, initial))
        val offPage = PlannerTools(prefs, day, Disclosure())
        denied { offPage.query(obj("""{"kind":"settings"}""")) }
        val tools = PlannerTools(prefs, AssistantPage.Settings, Disclosure())
        val settings = tools.query(current).jsonObject.getValue("rows").jsonArray
        assertEquals(setOf("workspace", "color", "layout", "material"), settings.map { it.jsonObject.getValue("group").jsonPrimitive.content }.toSet())
        fun decode(raw: String) = ((tools.proposal(cmd(raw)) as ToolPlan.Commands).commands.single() as PlannerCommand.EditAppearance).patch
        assertEquals(initial.copy(glass = false), decode("""{"kind":"prefs_set","group":"material","values":{"glass":false}}""").applyTo(initial))
        assertEquals(initial.copy(compact = false), decode("""{"kind":"prefs_reset","group":"layout"}""").applyTo(initial))
        for (invalid in listOf(
            """{"kind":"prefs_set","group":"material","values":{}}""", """{"kind":"prefs_set","group":"material","values":{"glass":"false"}}""",
            """{"kind":"prefs_set","group":"layout","values":{"glass":true}}""", """{"kind":"prefs_set","group":"color","values":{"seed":"#AAA"}}""",
            """{"kind":"prefs_set","group":"layout","values":{"order":["TODAY","COURSES","GRAPHS","TODAY"]}}""",
        )) denied { tools.proposal(cmd(invalid)) }
    }

    @Test fun wallpaperChangesUseOnlyReturnedMaterialAndKeepUnmentionedPageTransforms() = runBlocking {
        val id = "dd957e19-e907-4e7c-a513-707fb834162b"
        val a = Appearance(background = WallpaperSpec(id, scale=2f), pageBackgrounds=mapOf(Destination.WORKSPACE to WallpaperSpec()))
        val tools = PlannerTools(Reads(PlannerQuery.Settings to PolicyView(RestorePolicy.PREPEND,a)), AssistantPage.Settings, Disclosure())
        fun propose(fields:String) = (tools.proposal(cmd("""{"kind":"prefs_set","group":"material","values":$fields}""")) as ToolPlan.Commands).commands.single() as PlannerCommand.EditAppearance
        denied { propose("""{"background":{"tint":0.4}}""") }
        tools.query(obj("""{"kind":"current","limit":1}""")); denied { propose("""{"background":{"imageId":"$id"}}""") }
        val view = tools.query(current).jsonObject
        assertFalse(id in view.toString())
        val material = view.getValue("rows").jsonArray.single { it.jsonObject["group"] == JsonPrimitive("material") }.jsonObject.getValue("values").jsonObject
        val token = material.getValue("background").jsonObject.getValue("imageId").jsonPrimitive.content
        val changes = propose("""{"background":{"tint":0.4},"pageBackgrounds":{"TODAY":{"imageId":"$token"}}}""")
        val next = changes.patch.applyTo(a)
        assertEquals(a,changes.expected); assertEquals(a.background.copy(tint=.4f), next.background)
        assertEquals(a.background,next.wallpaper(Destination.TODAY)); assertEquals(WallpaperSpec(),next.wallpaper(Destination.WORKSPACE))
        denied { propose("""{"background":{"imageId":"$id"}}""") }
        val builtIn = propose("""{"background":{"preset":"ORANGE"}}""").patch.applyTo(a)
        assertEquals(WallpaperSpec(preset = WallpaperPreset.ORANGE, scale = 2f), builtIn.background)
        assertEquals(WallpaperSpec(), builtIn.wallpaper(Destination.WORKSPACE))
        denied { propose("""{"background":{"preset":"unknown-file"}}""") }
    }
    @Test fun importerRequiresExactReviewedDraftAndCannotBeMixedWithCommands() = runBlocking {
        val tools = PlannerTools(Reads(), AssistantPage.CourseDraft("d", "term"), Disclosure())
        assertEquals(obj("""{"draftId":"d","termId":"term"}"""), tools.query(current))
        val import = cmd("""{"kind":"course_import","draftId":"d"}""")
        assertEquals(ToolPlan.Commands(listOf(PlannerCommand.CourseImport("d"))), tools.proposal(import))
        denied { tools.proposal(cmd("""{"kind":"course_import","draftId":"other"}""")) }
        denied { PlannerTools(Reads(), day, Disclosure(extra = setOf(ReadArea.COURSES))).proposal(import) }
        assertEquals(ToolPlan.OpenImporter, tools.proposal(obj("""{"action":"open_course_import"}""")))
        denied { tools.proposal(obj("""{"action":"open_course_import","commands":[]}""")) }
    }

    @Test fun linkingRequiresThisTurnsReturnedIdentityNotAPickerQueryOrACourseToken() = runBlocking {
        val q = PlannerQuery.Day(day.date, ZoneId.systemDefault())
        val k = OccurrenceKey.Schedule("repeat", day.date.toEpochDay())
        val c = ScheduledCourse("term","hust",CourseMeeting(1,1,"secret-class","room",1,2,q.startMillis,q.startMillis+60000))
        val event = AgendaOccurrence(k,"D","secret-note",q.startMillis,q.startMillis,false,1,taskId="repeat",owner=Owner.QUEUE)
        val course = event.copy(key=OccurrenceKey.Course(CourseOccurrenceId.of(c.termId,c.source,c.meeting)),course=c)
        val data = Reads(q to DayPlan(q,1,listOf(QueueTask("single","A","secret-note",0)),listOf(event,course)))
        fun fresh() = PlannerTools(data, AssistantPage.Graph("g"), Disclosure(extra=setOf(ReadArea.DAY)))
        val tools = fresh()
        fun link(source: String) = cmd("""{"kind":"graph_patch","graphId":"g","edits":[{"kind":"link_existing","source":$source}]}""")
        val ordinary = link("""{"kind":"task","taskId":"single"}""")
        denied { tools.proposal(ordinary) }; denied { tools.query(obj("""{"kind":"linkable_events"}""")) }
        val result = tools.query(obj("""{"kind":"day","date":"${day.date}"}""")).jsonObject
        assertFalse("secret-" in result.toString())
        assertEquals(ToolPlan.Commands(listOf(PlannerCommand.GraphPatch("g",listOf(GraphEdit.LinkExisting(GraphLink.Task("single")))))),tools.proposal(ordinary))
        val rows = result.getValue("rows").jsonArray
        fun fromRow(index: Int) = link("""{"kind":"occurrence","occurrenceId":${rows[index].jsonObject.getValue("occurrenceId")}}""")
        assertEquals(ToolPlan.Commands(listOf(PlannerCommand.GraphPatch("g",listOf(GraphEdit.LinkExisting(GraphLink.Occurrence("repeat",day.date.toEpochDay())))))),tools.proposal(fromRow(0)))
        denied { tools.proposal(fromRow(1)) }; denied { fresh().proposal(fromRow(0)) }
        denied { tools.proposal(link("""{"kind":"task","taskId":"@not_read"}""")) }
    }

    @Test fun selectedGraphContainsItsGoalButNotOtherGraphs() = runBlocking {
        val n = GraphNode("n", "selected", "private-note", 25, 0, 0f, 0f)
        val graph = GraphDetail(1, GraphSummary("g", "Graph"), listOf(n), emptyList())
        val tools = PlannerTools(Reads(PlannerQuery.Graph("g") to graph, PlannerQuery.Goal("g", "n") to GoalOrder(1, listOf(n))),
            AssistantPage.Graph("g", "n"), Disclosure())
        val selected = tools.query(current).jsonObject
        assertEquals("n", selected.getValue("selectedNodeId").jsonPrimitive.content)
        assertFalse("private-note" in selected.toString())
        val goal = tools.query(obj("""{"kind":"goal","graphId":"g","goalId":"n"}""")).jsonObject
        assertEquals("n", goal.getValue("rows").jsonArray.single().jsonObject.getValue("nodeId").jsonPrimitive.content)
        denied { tools.query(obj("""{"kind":"graph","graphId":"elsewhere"}""")) }
        denied { tools.query(obj("""{"kind":"graphs"}""")) }
    }

    @Test fun weekCannotReadAnotherWeekWithoutAnAreaGrant() = runBlocking {
        val term = TermSpec("spring", LocalDate.parse("2026-03-02"), 18, BellSchedule.SUMMER)
        val meeting = ScheduledCourse(term.id, "private-source", CourseMeeting(2, 1, "Physics", "private-room", 1, 2, 1000, 2000))
        val source = Reads(PlannerQuery.Week(term.id, 2) to CourseWeek(term, 2, listOf(meeting)))
        val tools = PlannerTools(source, AssistantPage.Week(term.id, 2), Disclosure())
        val result = tools.query(current).toString()
        assertTrue("busy" in result && term.id in result); assertFalse("Physics" in result || "private-" in result)
        denied { tools.query(obj("""{"kind":"week","termId":"spring","week":3}""")) }
        denied { tools.query(obj("""{"kind":"terms"}""")) }
        denied { tools.proposal(cmd("""{"kind":"term_remove","termId":"@invented"}""")) }
    }

    @Test fun manualCourseProposalRequiresCourseDetailsExactWeekAndAnAuthorizedSemester() {
        val api = Reads()
        val page = AssistantPage.Week("fall", 2)
        fun add(weeks: String, term: String = "fall", newTerm: String = "") = cmd(
            """{"kind":"manual_course_add","termId":"$term","title":"物理","room":"B","weeks":[$weeks],"day":1,"firstPeriod":3,"lastPeriod":4$newTerm}""")
        denied { PlannerTools(api, page, Disclosure()).proposal(add("2")) }
        val local = PlannerTools(api, page, Disclosure(courseDetails = true))
        denied { local.proposal(add("1,2")) }
        denied { local.proposal(add("2", "other")) }
        val same = ((local.proposal(add("2")) as ToolPlan.Commands).commands.single() as PlannerCommand.ManualCourse)
        assertEquals(setOf(2), same.after!!.weeks); assertNull(same.before); assertNull(same.createTerm)
        val expanded = PlannerTools(api, page, Disclosure(extra = setOf(ReadArea.COURSES), courseDetails = true))
        denied { expanded.proposal(add("1,2", "never-disclosed")) }
        val early = PlannerTools(api, AssistantPage.Terms, Disclosure(courseDetails = true))
        val fresh = ((early.proposal(add("1,3", "new", ""","newTerm":{"firstMonday":"2026-09-07","totalWeeks":18,"initial":"SUMMER"}"""))
            as ToolPlan.Commands).commands.single() as PlannerCommand.ManualCourse)
        assertEquals(TermSpec("new", LocalDate.parse("2026-09-07"), 18, BellSchedule.SUMMER), fresh.createTerm)
    }

    @Test fun editKindsAndBatchReferencesDecodeWithoutWrites() {
        val tools = PlannerTools(Reads(), day, Disclosure())
        val result = tools.proposal(cmd("""
            {"kind":"graph_create","title":"G","ref":"g"},
            {"kind":"graph_patch","graphId":"@g","edits":[
              {"kind":"rename","title":"renamed"},{"kind":"add_node","title":"a","ref":"a"},
              {"kind":"add_node","title":"b","ref":"b"},{"kind":"edit_node","nodeId":"@a","title":"edited","minutes":12,"notes":"note"},
              {"kind":"move_node","nodeId":"@a","x":3,"y":4},{"kind":"add_edge","from":"@a","to":"@b"},
              {"kind":"remove_edge","from":"@a","to":"@b"},{"kind":"remove_node","nodeId":"@b"}]},
            {"kind":"graph_frame","graphId":"@g","goalId":"@a","ref":"f","taskRefs":{"@a":"t"}},
            {"kind":"frame_restore","frameId":"@f","mode":"selected","taskIds":["@t"]},
            {"kind":"prefs_set","group":"workspace","restorePolicy":"SWAP"},{"kind":"prefs_reset","group":"workspace"}
        """)) as ToolPlan.Commands
        assertEquals(listOf(
            PlannerCommand.GraphCreate("G", "g"),
            PlannerCommand.GraphPatch("@g", listOf(GraphEdit.Rename("renamed"), GraphEdit.AddNode("a", ref = "a"),
                GraphEdit.AddNode("b", ref = "b"), GraphEdit.EditNode("@a", "edited", "note", 12), GraphEdit.MoveNode("@a", 3f, 4f),
                GraphEdit.AddEdge("@a", "@b"), GraphEdit.RemoveEdge("@a", "@b"), GraphEdit.RemoveNode("@b"))),
            PlannerCommand.GraphFrame("@g", "@a", ref = "f", taskRefs = mapOf("@a" to "t")),
            PlannerCommand.FrameRestore("@f", RestoreMode.SELECTED, listOf("@t")),
            PlannerCommand.SetRestorePolicy(RestorePolicy.SWAP), PlannerCommand.SetRestorePolicy(RestorePolicy.PREPEND)
        ), result.commands)
    }
}
