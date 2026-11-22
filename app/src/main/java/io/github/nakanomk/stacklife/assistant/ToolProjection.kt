package io.github.nakanomk.stacklife.assistant

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.courses.ScheduledCourse
import io.github.nakanomk.stacklife.courses.calendarFor
import io.github.nakanomk.stacklife.planner.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*

/** No generic model serialization: adding a domain field cannot accidentally disclose it. */
internal class ToolProjection(private val grant: Disclosure, private val seen: ToolIds, private val offset: Int, private val limit: Int) {
    private val rows = ArrayList<JsonElement>(limit)
    private var count = 0L
    private var clipped = false

    // Share one cursor across sections of a day/graph. Project, and grant IDs, only for returned rows.
    private inline fun <T> section(values: List<T>, encode: (T) -> JsonObject) {
        val first = (offset.toLong() - count).coerceIn(0, values.size.toLong()).toInt()
        val end = (offset.toLong() + limit - count).coerceIn(0, values.size.toLong()).toInt()
        for (i in first until end) rows += encode(values[i])
        count += values.size
    }
    private fun JsonObjectBuilder.field(name: String, value: String) {
        if (value.length <= 384) put(name, value) else {
            // Do not split a supplementary code point at the output boundary.
            val prefix = if (value[383].isHighSurrogate()) 383 else 384
            put(name, value.substring(0, prefix) + "…"); clipped = true
        }
    }
    private fun JsonObjectBuilder.id(key: String, value: String, disclosed: MutableSet<String>) {
        put(key, value); disclosed += value
        if (disclosed === seen.tasks) seen.returnedTasks += value
    }
    private fun JsonObjectBuilder.interval(start: Long?, end: Long?, zone: ZoneId) {
        if (start != null && end != null) {
            put("start", Instant.ofEpochMilli(start).atZone(zone).toOffsetDateTime().toString())
            put("end", Instant.ofEpochMilli(end).atZone(zone).toOffsetDateTime().toString())
        }
    }
    private fun JsonObjectBuilder.schedule(s: ScheduleSummary?, zone: ZoneId) {
        if (s == null) return
        put("enabled",s.enabled)
        when (val p = s.pattern) {
            is SchedulePattern.Once -> interval(p.interval.startMillis,p.interval.endMillis,zone)
            is SchedulePattern.Daily -> {
                put("recurrence","DAILY")
                val rows = p.segments.takeLast(TOOL_PAGE_SIZE)
                put("segmentCount",p.segments.size)
                put("latestSegments",buildJsonArray { for (r in rows) add(buildJsonObject {
                    put("fromDate",java.time.LocalDate.ofEpochDay(r.fromDay).toString()); put("minuteOfDay",r.minute)
                    put("zone",r.zone ?: zone.id); put("durationMinutes",r.minutes)
                    r.endDay?.let { put("throughDate",java.time.LocalDate.ofEpochDay(it).toString()) }
                }) })
            }
        }
    }
    private fun task(t: QueueTask, zone: ZoneId, kind: String = "task") = buildJsonObject {
        put("kind", kind); id("taskId", t.id, seen.tasks); field("title", t.title); put("minutes", t.minutes)
        schedule(t.schedule,zone)
        if (grant.notes) field("notes", t.notes)
    }
    private fun countdown(c: Countdown) = buildJsonObject {
        id("countdownId", c.id, seen.countdowns); field("title", c.title)
        put("date", java.time.LocalDate.ofEpochDay(c.targetDay).toString()); put("mode", c.mode.name)
        put("pinned", c.pinned); put("position", c.position); put("hasBackground", c.backgroundId != null)
        put("maskOpacity", c.maskOpacity)
    }
    private fun node(n: GraphNode) = buildJsonObject {
        put("kind", "node"); id("nodeId", n.id, seen.nodes); field("title", n.title); put("minutes", n.minutes)
        put("nodeType", when (n.link) { null -> "template"; is GraphLink.Task -> "taskLink"; is GraphLink.Occurrence -> "occurrenceLink" })
        n.linkState?.let { s -> put("sourceRemoved", s.missing); put("completed", s.completed); put("scheduled", s.scheduled); s.owner?.let { put("owner", it.name) } }
        if (grant.notes) field("notes", n.notes)
    }
    private fun course(c: ScheduledCourse, zone: ZoneId) = buildJsonObject {
        put("kind", "busy"); interval(c.meeting.startMillis, c.meeting.endMillis, zone)
        put("occurrenceId",seen.disclose(OccurrenceKey.Course(CourseOccurrenceId.of(c.termId,c.source,c.meeting))))
        put("completed",c.completed)
        if (grant.courseDetails) { field("title", c.meeting.title); field("room", c.meeting.room) }
        // Never send source rows, conflict labels, or other attendees' data.
    }

    fun project(target: AssistantPage, query: PlannerQuery, result: QueryResult, nodeOnly: Boolean): JsonObject = buildJsonObject {
        val zone = (target as? AssistantPage.Day)?.zone ?: ZoneId.systemDefault()
        when (query) {
            PlannerQuery.Countdowns -> section((result as CountdownsView).items, ::countdown)
            is PlannerQuery.Countdown -> section(listOf(requireNotNull((result as CountdownView).value) {
                uiText("The countdown no longer exists.", "倒数日已不存在。") }), ::countdown)
            PlannerQuery.Queue -> section((result as QueueView).tasks) { task(it, zone) }
            is PlannerQuery.Task -> {
                val t = requireNotNull((result as TaskView).task) { uiText("The task does not exist or is outside this scope.", "任务不存在或已不在此范围。") }
                section(listOf(t)) { task(it, zone) }
            }
            PlannerQuery.Frames -> section((result as FrameIndex).frames) { f -> buildJsonObject {
                id("frameId", f.id, seen.frames); put("originalCount", f.originalCount); put("remaining", f.remaining)
                put("savedAt", Instant.ofEpochMilli(f.savedAtMillis).toString()); if (grant.reasons) field("reason", if (f.source == FrameSource.SWAP) swapReason() else f.reason)
            } }
            is PlannerQuery.Frame -> {
                val f = requireNotNull((result as FrameDetailResult).frame) { uiText("The frame does not exist.", "栈帧不存在。") }
                id("frameId", f.id, seen.frames); put("remaining", f.remaining)
                if (grant.reasons) field("reason", if (f.source == FrameSource.SWAP) swapReason() else f.reason)
                section(f.items) { item -> buildJsonObject {
                    // History is not a capability to modify a task that has since left this frame.
                    if (item.restorable) id("taskId", item.taskId, seen.tasks)
                    val live = item.current
                    field("title", live?.title ?: item.title); put("minutes", live?.minutes ?: item.minutes); put("position", item.originalPosition)
                    put("restorable", item.restorable); put("referenceOnly", item.referenceOnly)
                    schedule(if (live == null) item.schedule else live.schedule, zone)
                    if (grant.notes) field("notes", live?.notes ?: item.notes)
                } }
            }
            is PlannerQuery.Day -> {
                val day = result as DayPlan
                put("date", day.day.date.toString()); put("zone", zone.id)
                section(day.occurrences) { o ->
                    if (o.course != null) course(o.course, zone) else buildJsonObject {
                        put("kind", if (o.role != null) "routine" else "event")
                        id("scheduleId",(o.key as OccurrenceKey.Schedule).scheduleId,seen.schedules)
                        o.taskId?.let { id("taskId",it,seen.tasks) }
                        put("occurrenceId",seen.disclose(o.key)); field("title",o.title)
                        put("daily",o.daily); put("point",o.point); put("completed",o.completed)
                        interval(o.startMillis,o.endMillis,zone)
                        if (grant.notes) field("notes",o.notes)
                    }
                }
                section(day.tray) { task(it, zone, "tray") }
            }
            PlannerQuery.Graphs -> section((result as GraphList).graphs) { g -> buildJsonObject {
                id("graphId", g.id, seen.graphs); field("title", g.title)
            } }
            is PlannerQuery.Graph -> {
                val data = result as GraphDetail
                val graph = requireNotNull(data.graph) { uiText("The graph does not exist.", "流程图不存在。") }
                val selected = (target as AssistantPage.Graph).nodeId
                val selectedNode = selected?.let { id -> requireNotNull(data.nodes.find { it.id == id }) { uiText("The selected node does not exist.", "选中的节点不存在。") } }
                id("graphId", graph.id, seen.graphs); field("title", graph.title)
                if (selectedNode != null) id("selectedNodeId", selectedNode.id, seen.nodes)
                if (nodeOnly) section(listOf(requireNotNull(selectedNode)), ::node) else {
                    section(data.nodes, ::node); section(data.edges) { e -> buildJsonObject {
                        put("kind", "edge"); id("from", e.from, seen.nodes); id("to", e.to, seen.nodes)
                    } }
                }
            }
            is PlannerQuery.Goal -> {
                val goal = result as GoalOrder
                require(goal.error == null && goal.nodes.isNotEmpty()) { uiText("No ordered goal list could be found in this graph.", "该图中找不到可排序的目标清单。") }
                id("graphId", query.graphId, seen.graphs); id("goalId", query.goalId, seen.nodes); section(goal.nodes, ::node)
            }
            PlannerQuery.Terms -> section((result as Terms).terms) { term -> buildJsonObject {
                id("termId", term.id, seen.terms); put("weeks", term.weeks); put("firstMonday", term.firstMonday.toString())
                if (grant.courseDetails) {
                    val imports = result.importedSources[term.id].orEmpty()
                    seen.sources[term.id] = imports.toSet()
                    put("importSources", buildJsonArray { imports.forEach { add(it) } })
                }
            } }
            is PlannerQuery.Week -> {
                val week = result as CourseWeek
                val term = requireNotNull(week.term) { uiText("The term does not exist.", "学期不存在。") }
                require(week.week in 1..term.weeks) { uiText("This week is outside the selected term.", "该周不在所选学期内。") }
                id("termId", term.id, seen.terms); put("week", week.week)
                section(week.classes) { course(it, calendarFor(it.source)?.zone ?: zone) }
            }
            PlannerQuery.Settings -> {
                val settings = result as PolicyView
                section(listOf(Unit)) { buildJsonObject { put("group", "workspace"); put("restorePolicy", settings.policy.name) } }
                section(AppearanceGroup.entries) { group -> buildJsonObject {
                    val fields = group.project(settings.appearance)
                    val projected = if (group != AppearanceGroup.MATERIAL) fields else {
                        seen.material = settings.appearance
                        JsonObject(fields + mapOf("background" to seen.projectedPhoto(fields.getValue("background").jsonObject),
                            "pageBackgrounds" to JsonObject(fields.getValue("pageBackgrounds").jsonObject.mapValues { seen.projectedPhoto(it.value.jsonObject) })))
                    }
                    put("group", group.key); put("values", projected)
                } }

            }
            is PlannerQuery.Reminders -> {
                val data = result as ReminderView
                put("inherit", data.policy.inherit)
                section(data.effective) { rule -> buildJsonObject {
                    field("ruleId",rule.id)
                    when (val w = rule.time) {
                        is ReminderWhen.BeforeStart -> { put("kind","before"); put("leadMillis",w.leadMillis) }
                        is ReminderWhen.AtInstant -> { put("kind","at"); put("instant",Instant.ofEpochMilli(w.instantMillis).toString()) }
                    }
                } }
            }
            is PlannerQuery.LinkableEvents, is PlannerQuery.Source -> error("The event picker and source resolver are local-only.")
            is PlannerQuery.Occurrence, is PlannerQuery.Schedule -> error("Read occurrences through a scoped day, week, task or frame.")
            PlannerQuery.Workspace -> error(uiText("The assistant cannot read the entire workspace.", "不允许通过助理读取整个工作台。"))
            is PlannerQuery.CourseWeeks -> error(uiText("Course detail recurrence is a local-only read.", "课程详情的重复范围仅在本机读取。"))
        }
        put("rows", JsonArray(rows)); put("offset", offset); put("total", count)
        when {
            offset.toLong() + rows.size < count -> { put("nextOffset", offset + rows.size); put("message", uiText("More items are available. Query the next page with nextOffset.", "还有未返回的条目；使用 nextOffset 查询下一页。")) }
            rows.isEmpty() -> put("message", uiText("This page is empty.", "本页没有条目。"))
        }
        if (clipped) put("textNotice", uiText("Only the prefix of text longer than 384 characters is provided. Do not rewrite the original using this prefix.", "超过 384 字符的文本仅提供前缀，不能据此前缀重写原文。"))
    }
}
