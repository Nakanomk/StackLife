package io.github.nakanomk.stacklife.assistant

import io.github.nakanomk.stacklife.i18n.uiText
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
        if (kind == "reminders") {
            val subject = subject(arguments.getValue("subject").jsonObject)
            val read = PlannerQuery.Reminders(subject)
            return ToolProjection(disclosure, seen, arguments.optInt("offset") ?: 0, arguments.optInt("limit") ?: TOOL_PAGE_SIZE)
                .project(page, read, api.observe(read).first(), false)
        }
        val target = when (kind) {
            "current" -> page
            "queue" -> AssistantPage.Queue
            "task" -> AssistantPage.Task(arguments.string("taskId"))
            "frames" -> AssistantPage.Frames
            "frame" -> AssistantPage.Frame(arguments.string("frameId"))
            "graphs" -> AssistantPage.Graphs
            "graph", "goal" -> AssistantPage.Graph(arguments.string("graphId"))
            "node" -> AssistantPage.Graph(arguments.string("graphId"), arguments.string("nodeId"))
            "countdowns" -> AssistantPage.Countdowns
            "countdown" -> AssistantPage.Countdown(arguments.string("countdownId"))
            "terms" -> AssistantPage.Terms
            "week" -> AssistantPage.Week(arguments.string("termId"), arguments.int("week"))
            "day" -> AssistantPage.Day(date(arguments.string("date")), zone)
            "settings" -> AssistantPage.Settings
            else -> throw IllegalArgumentException(uiText("Unsupported query.", "不支持的查询。"))
        }
        val area = when (target) {
            is AssistantPage.Day -> ReadArea.DAY
            AssistantPage.Queue, AssistantPage.Frames, is AssistantPage.Task, is AssistantPage.Frame -> ReadArea.WORKSPACE
            AssistantPage.Graphs, is AssistantPage.Graph -> ReadArea.GRAPHS
            AssistantPage.Terms, is AssistantPage.Week, is AssistantPage.CourseDraft -> ReadArea.COURSES
            AssistantPage.Countdowns, is AssistantPage.Countdown -> ReadArea.COUNTDOWNS
            AssistantPage.Settings -> ReadArea.SETTINGS
        }
        val withinGraph = target is AssistantPage.Graph && page is AssistantPage.Graph && page.id == target.id
        require(target == page || withinGraph || (page == AssistantPage.Countdowns && target is AssistantPage.Countdown && target.id in seen.countdowns) || area in disclosure.extra) { uiText("This query is outside the current page. The user must authorize that scope first.", "此查询超出当前页面；请由用户先授权相应范围。") }
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
            AssistantPage.Countdowns -> PlannerQuery.Countdowns
            is AssistantPage.Countdown -> PlannerQuery.Countdown(target.id)
            AssistantPage.Settings -> PlannerQuery.Settings
            is AssistantPage.CourseDraft -> error(uiText("Drafts provide only a local ID.", "草稿只提供本地 ID。"))
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
            "frame_push" -> PlannerCommand.FramePush(o.optString("reason").orEmpty(), taskIds(), ref)
            "frame_add_reason" -> PlannerCommand.FrameAddReason(seen.frame(o.string("frameId")), o.string("reason"))
            "frame_restore" -> {
                val mode = if (o.string("mode") == "all") RestoreMode.ALL else RestoreMode.SELECTED
                val selection = if ("taskIds" in o) taskIds() else emptyList()
                require((mode == RestoreMode.ALL) == selection.isEmpty()) { uiText("Restoring a whole frame cannot specify tasks. A partial restore needs nonempty taskIds.", "整帧恢复不能指定任务；选择恢复需要非空 taskIds。") }
                PlannerCommand.FrameRestore(seen.frame(o.string("frameId")), mode, selection)
            }
            "countdown_add" -> PlannerCommand.CountdownAdd(o.string("title"), date(o.string("date")).toEpochDay(),
                o.optString("mode")?.let(CountdownMode::valueOf) ?: CountdownMode.COUNT_DOWN,
                o["pinned"]?.jsonPrimitive?.boolean ?: false, maskOpacity = o["maskOpacity"]?.jsonPrimitive?.float ?: .3f, ref = ref)
            "countdown_edit" -> PlannerCommand.CountdownEdit(seen.countdown(o.string("countdownId")), o.optString("title"),
                o.optString("date")?.let { date(it).toEpochDay() }, o.optString("mode")?.let(CountdownMode::valueOf),
                o["maskOpacity"]?.jsonPrimitive?.float, if (o["removeBackground"]?.jsonPrimitive?.boolean == true) BackgroundChange(null) else null)
            "countdown_pin" -> PlannerCommand.CountdownSetPinned(seen.countdown(o.string("countdownId")),o.getValue("pinned").jsonPrimitive.boolean)
            "countdown_remove" -> PlannerCommand.CountdownRemove(seen.countdown(o.string("countdownId")))
            "countdown_order" -> PlannerCommand.CountdownOrder(o.getValue("pinned").jsonPrimitive.boolean,
                o.getValue("countdownIds").jsonArray.map { seen.countdown(it.jsonPrimitive.content) })
            "graph_create" -> PlannerCommand.GraphCreate(o.string("title"), ref)
            "graph_patch" -> PlannerCommand.GraphPatch(graphId(), o.objects("edits").map(::edit))
            "graph_remove" -> PlannerCommand.GraphRemove(graphId())
            "graph_queue" -> PlannerCommand.GraphQueue(graphId(), seen.node(o.string("goalId")))
            "graph_frame" -> PlannerCommand.GraphFrame(graphId(), seen.node(o.string("goalId")), o.optString("reason"), ref,
                o["taskRefs"]?.jsonObject?.entries?.associate { (node, alias) -> seen.node(node) to alias.jsonPrimitive.content }.orEmpty())
            "set_schedule" -> PlannerCommand.SetSchedule(seen.schedule(o.string("scheduleId")), pattern(o.getValue("pattern").jsonObject),
                o["enabled"]?.jsonPrimitive?.boolean ?: true)
            "set_occurrence_override" -> {
                val key = seen.occurrence(o.string("occurrenceId")) as? OccurrenceKey.Schedule
                    ?: throw IllegalArgumentException(uiText("Course times are edited in the timetable.", "请在课表中编辑课程时刻。"))
                val p = pattern(o.getValue("pattern").jsonObject)
                PlannerCommand.SetOccurrenceOverride(key,EditScope.valueOf(o.string("scope")),
                    (p as? SchedulePattern.Once)?.interval,(p as? SchedulePattern.Daily)?.segments?.single())
            }
            "set_occurrence_completion" -> PlannerCommand.SetOccurrenceCompletion(seen.occurrence(o.string("occurrenceId")),o.getValue("completed").jsonPrimitive.boolean)
            "set_reminders" -> PlannerCommand.SetReminders(subject(o.getValue("subject").jsonObject), ReminderPolicy(
                o.getValue("inherit").jsonPrimitive.boolean, o.objects("rules").map { r ->
                    ReminderRule(r.string("ruleId"), if (r.string("kind") == "at") ReminderWhen.AtInstant(instant(r.string("instant")))
                        else ReminderWhen.BeforeStart(r.int("leadMinutes") * 60_000L))
                }))
            "course_import" -> {
                val id = o.string("draftId")
                require(page is AssistantPage.CourseDraft && page.draftId == id) { uiText("Only the current import draft reviewed by the user can be used.", "只能使用当前已由用户核对的导入草稿。") }
                PlannerCommand.CourseImport(id)
            }
            "term_remove" -> {
                val id = seen.term(o.string("termId"))
                val source = o.optString("source")
                require(source == null || source in seen.sources[id].orEmpty()) { uiText("Query the permitted timetable sources first.", "请先查询已授权的课表来源。") }
                PlannerCommand.TermRemove(id, source)
            }
            "manual_course_add" -> {
                require(disclosure.courseDetails) { uiText("Authorize class names and locations first, or add the class manually in the timetable.", "请先授权课程名称与地点，或在课表中手动添加。") }
                val id = o.string("termId")
                val weeks = o.getValue("weeks").jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.int }
                val newTerm = o["newTerm"]?.jsonObject
                val sameWeek = page is AssistantPage.Week && page.termId == id && weeks.singleOrNull() == page.week
                require(ReadArea.COURSES in disclosure.extra || (newTerm == null && sameWeek) ||
                    (newTerm != null && page == AssistantPage.Terms)) { uiText("Authorize other terms and weekly timetables first, or add classes only for this week.", "请先授权其他学期与周课表，或仅添加本周的课程。") }
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
                else {
                    val group = AppearanceGroup.entries.first { it.key == o.string("group") }
                    val fields = if (reset) null else o.getValue("values").jsonObject
                    val photos = fields != null && ("background" in fields || "pageBackgrounds" in fields)
                    val baseline = if (photos) requireNotNull(seen.material) { uiText("Query the material settings first.", "请先查询材质设置。") } else null
                    PlannerCommand.EditAppearance(AppearanceFields(group, if (baseline == null) fields else seen.resolvePhotos(requireNotNull(fields), baseline)), baseline)
                }

            }
            else -> throw IllegalArgumentException(uiText("Unsupported write proposal.", "不支持的写提案。"))
        }
    }

    private fun subject(o: JsonObject): ReminderSubject = when (o.string("kind")) {
        "schedule" -> ReminderSubject.Schedule(seen.schedule(o.string("scheduleId")))
        "occurrence" -> ReminderSubject.Occurrence(seen.occurrence(o.string("occurrenceId")))
        else -> {
            require(disclosure.courseDetails && (page == AssistantPage.Terms || page is AssistantPage.Week || ReadArea.COURSES in disclosure.extra)) {
                uiText("Authorize timetable details before accessing course-wide reminders.", "请先授权课表详情，再访问课程默认提醒。") }
            ReminderSubject.CourseDefault
        }
    }
    private fun instant(s: String): Long {
        val at = try { OffsetDateTime.parse(s) } catch (_: DateTimeParseException) {
            throw IllegalArgumentException(uiText("Use an ISO date-time with a UTC offset.", "请使用带 UTC 偏移量的 ISO 日期时间。"))
        }
        require(at.year in 1..9999) { uiText("Choose a year from 1 to 9999.", "请选择 1–9999 年。") }
        return at.toInstant().toEpochMilli()
    }
    private fun pattern(o: JsonObject): SchedulePattern? = when (o.string("kind")) {
        "once" -> instant(o.string("start")).let { SchedulePattern.Once(ExactInterval(it,Math.addExact(it,o.int("durationMinutes") * 60_000L))) }
        "daily" -> SchedulePattern.Daily(listOf(DailySegment(date(o.string("fromDate")).toEpochDay(),o.int("minuteOfDay"),
            o.optString("zone"),o.int("durationMinutes"),o.optString("throughDate")?.let { date(it).toEpochDay() })))
        else -> null
    }
    private fun edit(o: JsonObject): GraphEdit = when (o.string("kind")) {
        "rename" -> GraphEdit.Rename(o.string("title"))
        "add_node" -> GraphEdit.AddNode(o.string("title"), o.optString("notes").orEmpty(), o.optInt("minutes") ?: 30,
            o["x"]?.jsonPrimitive?.float ?: 0f, o["y"]?.jsonPrimitive?.float ?: 0f, o.optString("ref"))
        "link_existing" -> {
            val s = o.getValue("source").jsonObject
            val link = if (s.string("kind") == "task") GraphLink.Task(seen.linkTask(s.string("taskId"))) else {
                val key = seen.occurrence(s.string("occurrenceId")) as? OccurrenceKey.Schedule
                require(key?.originalDay != null) { uiText("Select a dated daily occurrence, not a class or one-off.", "请选择每日系列的具体发生项，不可选课程或一次性时段。") }
                GraphLink.Occurrence(key.scheduleId, requireNotNull(key.originalDay))
            }
            GraphEdit.LinkExisting(link, o["x"]?.jsonPrimitive?.float ?: 0f, o["y"]?.jsonPrimitive?.float ?: 0f, o.optString("ref"))
        }
        "set_linked_completion" -> GraphEdit.SetLinkedCompletion(seen.node(o.string("nodeId")), o.getValue("completed").jsonPrimitive.boolean)
        "edit_node" -> GraphEdit.EditNode(seen.node(o.string("nodeId")), o.optString("title"), o.optString("notes"), o.optInt("minutes"))
        "move_node" -> GraphEdit.MoveNode(seen.node(o.string("nodeId")), o.getValue("x").jsonPrimitive.float, o.getValue("y").jsonPrimitive.float)
        "remove_node" -> GraphEdit.RemoveNode(seen.node(o.string("nodeId")))
        "add_edge" -> GraphEdit.AddEdge(seen.node(o.string("from")), seen.node(o.string("to")))
        "remove_edge" -> GraphEdit.RemoveEdge(seen.node(o.string("from")), seen.node(o.string("to")))
        else -> throw IllegalArgumentException(uiText("Unsupported graph edit.", "不支持的流程图编辑。"))
    }
}

private fun date(value: String): LocalDate {
    val date = try { LocalDate.parse(value) } catch (_: DateTimeParseException) { throw IllegalArgumentException(uiText("Enter a valid date in YYYY-MM-DD format.", "日期须为有效的 YYYY-MM-DD。")) }
    require(date.year in 1..9999) { uiText("The year is outside the supported range.", "日期年份超出范围。") }; return date
}
private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.optString(key: String) = this[key]?.jsonPrimitive?.content
private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
private fun JsonObject.optInt(key: String) = this[key]?.jsonPrimitive?.int
private fun JsonObject.objects(key: String) = getValue(key).jsonArray.map { it.jsonObject }
private fun JsonObject.input() = TaskInput(string("title"), optString("notes").orEmpty(), optInt("minutes") ?: 30, optString("ref"))

internal class ToolIds(page: AssistantPage) {
    val tasks = mutableSetOf<String>()
    val returnedTasks = mutableSetOf<String>()
    var material: Appearance? = null
    private val imageTokens = mutableMapOf<String,String>()
    private val privateImages = mutableMapOf<String,String>()
    fun projectedPhoto(spec: JsonObject): JsonObject {
        val id = spec["imageId"]?.jsonPrimitive?.contentOrNull ?: return spec
        val token = imageTokens.getOrPut(id) { java.util.UUID.randomUUID().toString().also { privateImages[it] = id } }
        return JsonObject(spec + ("imageId" to JsonPrimitive(token)))
    }
    fun resolvePhotos(fields: JsonObject, baseline: Appearance): JsonObject {
        val initial = AppearanceGroup.MATERIAL.project(baseline)
        val result = fields.toMutableMap()
        fun merge(old: JsonObject, patch: JsonObject): JsonObject {
            val changed = patch.toMutableMap()
            // A source change replaces the other source; a crop-only patch preserves both fields.
            if ("imageId" in patch && "preset" !in patch) changed["preset"] = JsonNull
            if (patch["preset"]?.jsonPrimitive?.contentOrNull != null && "imageId" !in patch) changed["imageId"] = JsonNull
            patch["imageId"]?.jsonPrimitive?.contentOrNull?.let { token -> changed["imageId"] = JsonPrimitive(
                requireNotNull(privateImages[token]) { uiText("Use a photo reference from this turn only.", "只能使用本轮披露的图片引用。") }) }
            return JsonObject(old + changed)
        }
        fields["background"]?.let { result["background"] = merge(initial.getValue("background").jsonObject, it.jsonObject) }
        fields["pageBackgrounds"]?.let { edits ->
            val pages = initial.getValue("pageBackgrounds").jsonObject.toMutableMap()
            for ((dest, patch) in edits.jsonObject) if (patch == JsonNull) pages.remove(dest)
                else pages[dest] = merge(pages[dest]?.jsonObject ?: initial.getValue("background").jsonObject, patch.jsonObject)
            result["pageBackgrounds"] = JsonObject(pages)
        }
        return JsonObject(result)
    }
    val countdowns = mutableSetOf<String>()
    val frames = mutableSetOf<String>()
    val graphs = mutableSetOf<String>()
    val nodes = mutableSetOf<String>()
    val terms = mutableSetOf<String>()
    val sources = mutableMapOf<String, Set<String>>()
    val schedules = mutableSetOf<String>()
    private val tokens = mutableMapOf<OccurrenceKey,String>()
    private val keys = mutableMapOf<String,OccurrenceKey>()
    fun disclose(key: OccurrenceKey): String = tokens.getOrPut(key) {
        java.util.UUID.randomUUID().toString().also { keys[it] = key }
    }
    fun occurrence(token: String) = requireNotNull(keys[token]) { uiText("Query this occurrence in the current scope first.", "请先在当前授权范围内查询本次事件。") }
    fun schedule(id: String) = if (id in schedules) id else task(id)
    init {
        when (page) {
            is AssistantPage.Task -> tasks += page.id
            is AssistantPage.Countdown -> countdowns += page.id
            is AssistantPage.Frame -> frames += page.id
            is AssistantPage.Graph -> { graphs += page.id; page.nodeId?.let(nodes::add) }
            is AssistantPage.Week -> terms += page.termId
            is AssistantPage.CourseDraft -> terms += page.termId
            else -> Unit
        }
    }
    private fun check(id: String, allowed: Set<String>, allowAlias: Boolean = true): String {
        require(id in allowed || (allowAlias && id.startsWith('@'))) { uiText("This proposal references an entity that has not been authorized and disclosed this turn. Obtain permission and query it first.", "提案引用了本轮未获准且未披露的实体；请先获授权并查询。") }
        return id
    }
    fun countdown(id: String) = check(id, countdowns)
    fun task(id: String) = check(id, tasks)
    fun linkTask(id: String) = check(id, returnedTasks, allowAlias = false)
    fun frame(id: String) = check(id, frames)
    fun graph(id: String) = check(id, graphs)
    fun node(id: String) = check(id, nodes)
    fun term(id: String) = check(id, terms, allowAlias = false)
}
