package io.github.nakanomk.stacklife.assistant

import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.courses.BellSchedule
import io.github.nakanomk.stacklife.courses.CoursePattern
import io.github.nakanomk.stacklife.courses.ManualSeries
import io.github.nakanomk.stacklife.courses.TermSpec
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

// This ledger belongs to one turn/consent only. Do not reuse it after the page or grant changes.
internal class PlannerTools(private val api: PlannerApi, val page: AssistantPage, val disclosure: Disclosure) {
    private val seen = ToolIds(page)

    suspend fun query(arguments: JsonObject): JsonElement {
        checkPlannerQuery(arguments)
        val kind = arguments.string("kind")
        val zone = (page as? AssistantPage.Day)?.zone ?: ZoneId.systemDefault()
        val target = when (kind) {
            "current" -> page
            "queue" -> AssistantPage.Queue
            "task" -> AssistantPage.Task(arguments.string("taskId"))
            "frames" -> AssistantPage.Frames
            "frame" -> AssistantPage.Frame(arguments.string("frameId"))
            "graphs" -> AssistantPage.Graphs
            "graph", "goal" -> AssistantPage.Graph(arguments.string("graphId"))
            "node" -> AssistantPage.Graph(arguments.string("graphId"), arguments.string("nodeId"))
            "terms" -> AssistantPage.Terms
            "week" -> AssistantPage.Week(arguments.string("termId"), arguments.int("week"))
            "day" -> AssistantPage.Day(date(arguments.string("date")), zone)
            "settings" -> AssistantPage.Settings
            else -> throw IllegalArgumentException("不支持的查询。")
        }
        val area = when (target) {
            is AssistantPage.Day -> ReadArea.DAY
            AssistantPage.Queue, AssistantPage.Frames, is AssistantPage.Task, is AssistantPage.Frame -> ReadArea.WORKSPACE
            AssistantPage.Graphs, is AssistantPage.Graph -> ReadArea.GRAPHS
            AssistantPage.Terms, is AssistantPage.Week, is AssistantPage.CourseDraft -> ReadArea.COURSES
            AssistantPage.Settings -> ReadArea.SETTINGS
        }
        val withinGraph = target is AssistantPage.Graph && page is AssistantPage.Graph && page.id == target.id
        require(target == page || withinGraph || area in disclosure.extra) { "此查询超出当前页面；请由用户先授权相应范围。" }
        if (target is AssistantPage.CourseDraft) return buildJsonObject {
            put("draftId", target.draftId); put("termId", target.termId)
        }
        val read = when (target) {
            is AssistantPage.Day -> PlannerQuery.Day(target.date, target.zone)
            AssistantPage.Queue -> PlannerQuery.Queue
            is AssistantPage.Task -> PlannerQuery.Task(target.id)
            AssistantPage.Frames -> PlannerQuery.Frames
            is AssistantPage.Frame -> PlannerQuery.Frame(target.id)
            AssistantPage.Graphs -> PlannerQuery.Graphs
            is AssistantPage.Graph -> if (kind == "goal") PlannerQuery.Goal(target.id, arguments.string("goalId")) else PlannerQuery.Graph(target.id)
            AssistantPage.Terms -> PlannerQuery.Terms
            is AssistantPage.Week -> PlannerQuery.Week(target.termId, target.week)
            AssistantPage.Settings -> PlannerQuery.Settings
            is AssistantPage.CourseDraft -> error("草稿只提供本地 ID。")
        }
        val data = api.observe(read).first()
        return ToolProjection(disclosure, seen, arguments.optInt("offset") ?: 0, arguments.optInt("limit") ?: TOOL_PAGE_SIZE)
            .project(target, read, data, nodeOnly = kind == "node")
    }

    fun proposal(arguments: JsonObject): ToolPlan {
        checkPlannerProposal(arguments)
        if ("action" in arguments) return ToolPlan.OpenImporter
        return ToolPlan.Commands(arguments.objects("commands").map(::command))
    }

    private fun command(o: JsonObject): PlannerCommand {
        fun taskId() = seen.task(o.string("taskId"))
        fun taskIds() = o.getValue("taskIds").jsonArray.map { seen.task(it.jsonPrimitive.content) }
        fun graphId() = seen.graph(o.string("graphId"))
        val ref = o.optString("ref")
        return when (o.string("kind")) {
            "task_add" -> PlannerCommand.TaskAdd(o.input())
            "task_edit" -> PlannerCommand.TaskEdit(taskId(), o.optString("title"), o.optString("notes"), o.optInt("minutes"))
            "task_finish" -> PlannerCommand.TaskFinish(taskId())
            "task_remove" -> PlannerCommand.TaskRemove(taskId())
            "queue_order" -> PlannerCommand.QueueOrder(taskIds())
            "frame_create" -> PlannerCommand.FrameCreate(o.string("reason"), o.objects("tasks").map { it.input() }, ref)
            "frame_push" -> PlannerCommand.FramePush(o.string("reason"), taskIds(), ref)
            "frame_restore" -> {
                val mode = if (o.string("mode") == "all") RestoreMode.ALL else RestoreMode.SELECTED
                val selection = if ("taskIds" in o) taskIds() else emptyList()
                require((mode == RestoreMode.ALL) == selection.isEmpty()) { "整帧恢复不能指定任务；选择恢复需要非空 taskIds。" }
                PlannerCommand.FrameRestore(seen.frame(o.string("frameId")), mode, selection)
            }
            "graph_create" -> PlannerCommand.GraphCreate(o.string("title"), ref)
            "graph_patch" -> PlannerCommand.GraphPatch(graphId(), o.objects("edits").map(::edit))
            "graph_remove" -> PlannerCommand.GraphRemove(graphId())
            "graph_frame" -> PlannerCommand.GraphFrame(graphId(), seen.node(o.string("goalId")), o.optString("reason"), ref,
                o["taskRefs"]?.jsonObject?.entries?.associate { (node, alias) -> seen.node(node) to alias.jsonPrimitive.content }.orEmpty())
            "slot_set" -> {
                val start = try { OffsetDateTime.parse(o.string("start")) } catch (_: DateTimeParseException) {
                    throw IllegalArgumentException("开始时间必须为带 UTC 偏移量的 ISO 日期时间。")
                }
                require(start.year in 1..9999) { "排程日期年份超出范围。" }
                PlannerCommand.SlotSet(taskId(), start, o.int("durationMinutes"))
            }
            "slot_clear" -> PlannerCommand.SlotClear(taskId())
            "course_import" -> {
                val id = o.string("draftId")
                require(page is AssistantPage.CourseDraft && page.draftId == id) { "只能使用当前已由用户核对的导入草稿。" }
                PlannerCommand.CourseImport(id)
            }
            "term_remove" -> PlannerCommand.TermRemove(seen.term(o.string("termId")))
            "manual_course_add" -> {
                require(disclosure.courseDetails) { "请先授权课程名称与地点，或在课表中手动添加。" }
                val id = o.string("termId")
                val weeks = o.getValue("weeks").jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.int }
                val newTerm = o["newTerm"]?.jsonObject
                val sameWeek = page is AssistantPage.Week && page.termId == id && weeks.singleOrNull() == page.week
                require(ReadArea.COURSES in disclosure.extra || (newTerm == null && sameWeek) ||
                    (newTerm != null && page == AssistantPage.Terms)) { "请先授权其他学期与周课表，或仅添加本周的课程。" }
                val term = newTerm?.let {
                    TermSpec(id, date(it.string("firstMonday")), it.int("totalWeeks"), BellSchedule.valueOf(it.string("initial")),
                        it.optString("switchDate")?.let(::date))
                }
                if (term == null) seen.term(id)
                PlannerCommand.ManualCourse(id, null, ManualSeries(CoursePattern(o.string("title"), o.optString("room").orEmpty(),
                    o.int("day"), o.int("firstPeriod"), o.int("lastPeriod")), weeks), createTerm = term)
            }
            "prefs_set", "prefs_reset" -> {
                val reset = o.string("kind") == "prefs_reset"
                if (o.string("group") == "workspace") PlannerCommand.SetRestorePolicy(
                    if (reset) RestorePolicy.PREPEND else RestorePolicy.valueOf(o.string("restorePolicy")))
                else PlannerCommand.EditAppearance(AppearanceFields(AppearanceGroup.entries.first { it.key == o.string("group") },
                    if (reset) null else o.getValue("values").jsonObject))
            }
            else -> throw IllegalArgumentException("不支持的写提案。")
        }
    }

    private fun edit(o: JsonObject): GraphEdit = when (o.string("kind")) {
        "rename" -> GraphEdit.Rename(o.string("title"))
        "add_node" -> GraphEdit.AddNode(o.string("title"), o.optString("notes").orEmpty(), o.optInt("minutes") ?: 30,
            o["x"]?.jsonPrimitive?.float ?: 0f, o["y"]?.jsonPrimitive?.float ?: 0f, o.optString("ref"))
        "edit_node" -> GraphEdit.EditNode(seen.node(o.string("nodeId")), o.optString("title"), o.optString("notes"), o.optInt("minutes"))
        "move_node" -> GraphEdit.MoveNode(seen.node(o.string("nodeId")), o.getValue("x").jsonPrimitive.float, o.getValue("y").jsonPrimitive.float)
        "remove_node" -> GraphEdit.RemoveNode(seen.node(o.string("nodeId")))
        "add_edge" -> GraphEdit.AddEdge(seen.node(o.string("from")), seen.node(o.string("to")))
        "remove_edge" -> GraphEdit.RemoveEdge(seen.node(o.string("from")), seen.node(o.string("to")))
        else -> throw IllegalArgumentException("不支持的流程图编辑。")
    }
}

private fun date(value: String): LocalDate {
    val date = try { LocalDate.parse(value) } catch (_: DateTimeParseException) { throw IllegalArgumentException("日期须为有效的 YYYY-MM-DD。") }
    require(date.year in 1..9999) { "日期年份超出范围。" }; return date
}
private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.optString(key: String) = this[key]?.jsonPrimitive?.content
private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
private fun JsonObject.optInt(key: String) = this[key]?.jsonPrimitive?.int
private fun JsonObject.objects(key: String) = getValue(key).jsonArray.map { it.jsonObject }
private fun JsonObject.input() = TaskInput(string("title"), optString("notes").orEmpty(), optInt("minutes") ?: 30, optString("ref"))

internal class ToolIds(page: AssistantPage) {
    val tasks = mutableSetOf<String>()
    val frames = mutableSetOf<String>()
    val graphs = mutableSetOf<String>()
    val nodes = mutableSetOf<String>()
    val terms = mutableSetOf<String>()
    init {
        when (page) {
            is AssistantPage.Task -> tasks += page.id
            is AssistantPage.Frame -> frames += page.id
            is AssistantPage.Graph -> { graphs += page.id; page.nodeId?.let(nodes::add) }
            is AssistantPage.Week -> terms += page.termId
            is AssistantPage.CourseDraft -> terms += page.termId
            else -> Unit
        }
    }
    private fun check(id: String, allowed: Set<String>, allowAlias: Boolean = true): String {
        require(id in allowed || (allowAlias && id.startsWith('@'))) { "提案引用了本轮未获准且未披露的实体；请先获授权并查询。" }
        return id
    }
    fun task(id: String) = check(id, tasks)
    fun frame(id: String) = check(id, frames)
    fun graph(id: String) = check(id, graphs)
    fun node(id: String) = check(id, nodes)
    fun term(id: String) = check(id, terms, allowAlias = false)
}
