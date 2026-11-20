package io.github.nakanomk.stacklife.assistant

import io.github.nakanomk.stacklife.courses.ScheduledCourse
import io.github.nakanomk.stacklife.courses.campusZone
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
    }
    private fun JsonObjectBuilder.interval(start: Long?, end: Long?, zone: ZoneId) {
        if (start != null && end != null) {
            put("start", Instant.ofEpochMilli(start).atZone(zone).toOffsetDateTime().toString())
            put("end", Instant.ofEpochMilli(end).atZone(zone).toOffsetDateTime().toString())
        }
    }
    private fun task(t: QueueTask, zone: ZoneId, kind: String = "task") = buildJsonObject {
        put("kind", kind); id("taskId", t.id, seen.tasks); field("title", t.title); put("minutes", t.minutes)
        interval(t.scheduledStartMillis, t.scheduledEndMillis, zone)
        if (grant.notes) field("notes", t.notes)
    }
    private fun node(n: GraphNode) = buildJsonObject {
        put("kind", "node"); id("nodeId", n.id, seen.nodes); field("title", n.title); put("minutes", n.minutes)
        if (grant.notes) field("notes", n.notes)
    }
    private fun course(c: ScheduledCourse, zone: ZoneId) = buildJsonObject {
        put("kind", "busy"); interval(c.meeting.startMillis, c.meeting.endMillis, zone)
        if (grant.courseDetails) { field("title", c.meeting.title); field("room", c.meeting.room) }
        // Never send source rows, conflict labels, or other attendees' data.
    }

    fun project(target: AssistantPage, query: PlannerQuery, result: QueryResult, nodeOnly: Boolean): JsonObject = buildJsonObject {
        val zone = (target as? AssistantPage.Day)?.zone ?: ZoneId.systemDefault()
        when (query) {
            PlannerQuery.Queue -> section((result as QueueView).tasks) { task(it, zone) }
            is PlannerQuery.Task -> {
                val t = requireNotNull((result as TaskView).task) { "任务不存在或已不在此范围。" }
                section(listOf(t)) { task(it, zone) }
            }
            PlannerQuery.Frames -> section((result as FrameIndex).frames) { f -> buildJsonObject {
                id("frameId", f.id, seen.frames); put("originalCount", f.originalCount); put("remaining", f.remaining)
                put("savedAt", Instant.ofEpochMilli(f.savedAtMillis).toString()); if (grant.reasons) field("reason", f.reason)
            } }
            is PlannerQuery.Frame -> {
                val f = requireNotNull((result as FrameDetailResult).frame) { "栈帧不存在。" }
                id("frameId", f.id, seen.frames); put("remaining", f.remaining)
                if (grant.reasons) field("reason", f.reason)
                section(f.items) { item -> buildJsonObject {
                    // History is not a capability to modify a task that has since left this frame.
                    if (item.restorable) id("taskId", item.taskId, seen.tasks)
                    field("title", item.title); put("minutes", item.minutes); put("position", item.originalPosition)
                    put("restorable", item.restorable); interval(item.scheduledStartMillis, item.scheduledEndMillis, zone)
                    if (grant.notes) field("notes", item.notes)
                } }
            }
            is PlannerQuery.Day -> {
                val day = result as DayPlan
                put("date", day.day.date.toString()); put("zone", zone.id)
                section(day.blocks) { block -> buildJsonObject {
                    put("kind", "slot"); id("taskId", block.taskId, seen.tasks); field("title", block.title)
                    put("state", block.state.name); interval(block.startMillis, block.endMillis, zone)
                } }
                section(day.courses) { course(it, zone) }; section(day.tray) { task(it, zone, "tray") }
            }
            PlannerQuery.Graphs -> section((result as GraphList).graphs) { g -> buildJsonObject {
                id("graphId", g.id, seen.graphs); field("title", g.title)
            } }
            is PlannerQuery.Graph -> {
                val data = result as GraphDetail
                val graph = requireNotNull(data.graph) { "流程图不存在。" }
                val selected = (target as AssistantPage.Graph).nodeId
                val selectedNode = selected?.let { id -> requireNotNull(data.nodes.find { it.id == id }) { "选中的节点不存在。" } }
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
                require(goal.error == null && goal.nodes.isNotEmpty()) { "该图中找不到可排序的目标清单。" }
                id("graphId", query.graphId, seen.graphs); id("goalId", query.goalId, seen.nodes); section(goal.nodes, ::node)
            }
            PlannerQuery.Terms -> section((result as Terms).terms) { term -> buildJsonObject {
                id("termId", term.id, seen.terms); put("weeks", term.weeks); put("firstMonday", term.firstMonday.toString())
            } }
            is PlannerQuery.Week -> {
                val week = result as CourseWeek
                val term = requireNotNull(week.term) { "学期不存在。" }
                require(week.week in 1..term.weeks) { "该周不在所选学期内。" }
                id("termId", term.id, seen.terms); put("week", week.week); put("zone", campusZone.id)
                section(week.classes) { course(it, campusZone) }
            }
            PlannerQuery.Settings -> {
                val settings = result as PolicyView
                section(listOf(buildJsonObject { put("group", "workspace"); put("restorePolicy", settings.policy.name) }) +
                    AppearanceGroup.entries.map { group -> buildJsonObject { put("group", group.key); put("values", group.project(settings.appearance)) } }) { it }
            }
            PlannerQuery.Workspace -> error("不允许通过助理读取整个工作台。")
        }
        put("rows", JsonArray(rows)); put("offset", offset); put("total", count)
        when {
            offset.toLong() + rows.size < count -> { put("nextOffset", offset + rows.size); put("message", "还有未返回的条目；使用 nextOffset 查询下一页。") }
            rows.isEmpty() -> put("message", "本页没有条目。")
        }
        if (clipped) put("textNotice", "超过 384 字符的文本仅提供前缀，不能据此前缀重写原文。")
    }
}
