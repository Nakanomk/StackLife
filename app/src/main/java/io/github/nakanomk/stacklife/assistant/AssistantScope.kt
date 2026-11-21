package io.github.nakanomk.stacklife.assistant

import io.github.nakanomk.stacklife.planner.PlannerCommand
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class ReadArea(val label: String) {
    DAY("其他日期的日程"), WORKSPACE("队列与其他栈帧"), GRAPHS("其他流程图"), COURSES("其他学期与周课表"), SETTINGS("外观与恢复设置")
}

internal sealed class AssistantPage(val label: String) {
    data class Day(val date: LocalDate, val zone: ZoneId) : AssistantPage("今日日程")
    data object Queue : AssistantPage("队列")
    data object Frames : AssistantPage("栈帧")
    data class Task(val id: String) : AssistantPage("任务")
    data class Frame(val id: String) : AssistantPage("栈帧详情")
    data object Graphs : AssistantPage("流程图")
    data class Graph(val id: String, val nodeId: String? = null) : AssistantPage(if (nodeId == null) "当前流程图" else "当前节点")
    data object Terms : AssistantPage("课表")
    data class Week(val termId: String, val week: Int) : AssistantPage("本周课表")
    data class CourseDraft(val draftId: String, val termId: String) : AssistantPage("课表导入")
    data object Settings : AssistantPage("设置")
}

internal fun AssistantPage.scopeName() = when (this) {
    is AssistantPage.Day -> "$date · ${zone.id}"
    is AssistantPage.Frame -> "当前栈帧 · ${id.take(8)}"
    is AssistantPage.Task -> "当前任务 · ${id.take(8)}"
    is AssistantPage.Graph -> "当前流程图 · ${id.take(8)}" + (nodeId?.let { " / 节点 ${it.take(8)}" } ?: "")
    is AssistantPage.Week -> "$termId · 第 $week 周"
    is AssistantPage.CourseDraft -> "$termId · 本地已核对草稿"
    AssistantPage.Queue -> "当前队列"
    AssistantPage.Frames -> "栈帧列表"
    AssistantPage.Graphs -> "流程图列表"
    AssistantPage.Terms -> "学期列表"
    AssistantPage.Settings -> "外观与工作台恢复设置"
}

@Serializable
private data class HistoryLocation(val type: String, val id: String? = null, val detail: String? = null)

internal fun AssistantPage.historyKey(): String = Json.encodeToString(when (this) {
    is AssistantPage.Day -> HistoryLocation("day", date.toString(), zone.id)
    is AssistantPage.Week -> HistoryLocation("week", termId, week.toString())
    is AssistantPage.Task -> HistoryLocation("task", id)
    is AssistantPage.Frame -> HistoryLocation("frame", id)
    is AssistantPage.Graph -> HistoryLocation("graph", id, nodeId)
    AssistantPage.Queue -> HistoryLocation("queue")
    AssistantPage.Frames -> HistoryLocation("frames")
    AssistantPage.Graphs -> HistoryLocation("graphs")
    AssistantPage.Settings -> HistoryLocation("settings")
    AssistantPage.Terms, is AssistantPage.CourseDraft -> HistoryLocation("terms")
})

internal fun historyPage(key: String): AssistantPage {
    val location = Json.decodeFromString<HistoryLocation>(key)
    return when (location.type) {
        "day" -> AssistantPage.Day(LocalDate.parse(location.id), ZoneId.of(location.detail))
        "week" -> AssistantPage.Week(requireNotNull(location.id), requireNotNull(location.detail).toInt())
        "task" -> AssistantPage.Task(requireNotNull(location.id))
        "frame" -> AssistantPage.Frame(requireNotNull(location.id))
        "graph" -> AssistantPage.Graph(requireNotNull(location.id), location.detail)
        "queue" -> AssistantPage.Queue
        "frames" -> AssistantPage.Frames
        "graphs" -> AssistantPage.Graphs
        "settings" -> AssistantPage.Settings
        "terms" -> AssistantPage.Terms
        else -> error("未知的对话范围")
    }
}

internal fun ConversationRow.resumePage(fallback: AssistantPage): AssistantPage {
    scopeKey?.let { return historyPage(it) }
    // Version 6 stored display labels only. Never turn a shortened entity ID into a tool capability.
    val simple = when (scope) {
        "当前队列" -> AssistantPage.Queue
        "栈帧列表" -> AssistantPage.Frames
        "流程图列表" -> AssistantPage.Graphs
        "学期列表" -> AssistantPage.Terms
        "外观与工作台恢复设置" -> AssistantPage.Settings
        else -> null
    }
    if (simple != null) return simple
    val split = scope.lastIndexOf(" · ")
    if (split < 0) return fallback
    val first = scope.substring(0, split)
    val second = scope.substring(split + 3)
    if (second.startsWith("第 ") && second.endsWith(" 周")) {
        second.removePrefix("第 ").removeSuffix(" 周").toIntOrNull()?.let { return AssistantPage.Week(first, it) }
    }
    return try { AssistantPage.Day(LocalDate.parse(first), ZoneId.of(second)) }
        catch (_: java.time.DateTimeException) { fallback }
}

internal data class Disclosure(val extra: Set<ReadArea> = emptySet(), val notes: Boolean = false,
    val reasons: Boolean = false, val courseDetails: Boolean = false)

internal sealed interface ToolPlan {
    data class Commands(val commands: List<PlannerCommand>) : ToolPlan
    data object OpenImporter : ToolPlan
}
