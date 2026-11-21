package io.github.nakanomk.stacklife.assistant

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.PlannerCommand
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class ReadArea(private val en: String, private val zh: String) {
    DAY("Schedules on other dates", "其他日期的日程"), WORKSPACE("Queue and other frames", "队列与其他栈帧"), GRAPHS("Other graphs", "其他流程图"), COURSES("Other terms and weekly timetables", "其他学期与周课表"), SETTINGS("Appearance and restore settings", "外观与恢复设置");

    val label: String get() = uiText(en, zh)
}

internal sealed class AssistantPage(private val en: String, private val zh: String) {
    val label: String get() = uiText(en, zh)

    data class Day(val date: LocalDate, val zone: ZoneId) : AssistantPage("Day schedule", "今日日程")
    data object Queue : AssistantPage("Queue", "队列")
    data object Frames : AssistantPage("Frames", "栈帧")
    data class Task(val id: String) : AssistantPage("Task", "任务")
    data class Frame(val id: String) : AssistantPage("Frame details", "栈帧详情")
    data object Graphs : AssistantPage("Graphs", "流程图")
    data class Graph(val id: String, val nodeId: String? = null) : AssistantPage(
        if (nodeId == null) "Current graph" else "Current node", if (nodeId == null) "当前流程图" else "当前节点")
    data object Terms : AssistantPage("Timetable", "课表")
    data class Week(val termId: String, val week: Int) : AssistantPage("Weekly timetable", "本周课表")
    data class CourseDraft(val draftId: String, val termId: String) : AssistantPage("Timetable import", "课表导入")
    data object Settings : AssistantPage("Settings", "设置")
}

internal fun AssistantPage.scopeName() = when (this) {
    is AssistantPage.Day -> "$date · ${zone.id}"
    is AssistantPage.Frame -> uiText({ "Current frame · ${id.take(8)}" }, { "当前栈帧 · ${id.take(8)}" })
    is AssistantPage.Task -> uiText({ "Current task · ${id.take(8)}" }, { "当前任务 · ${id.take(8)}" })
    is AssistantPage.Graph -> uiText({ "Current graph · ${id.take(8)}" }, { "当前流程图 · ${id.take(8)}" }) + (nodeId?.let { uiText({ " / Node ${it.take(8)}" }, { " / 节点 ${it.take(8)}" }) } ?: "")
    is AssistantPage.Week -> uiText({ "$termId · Week $week" }, { "$termId · 第 $week 周" })
    is AssistantPage.CourseDraft -> reviewedTermName(termId)
    AssistantPage.Queue -> uiText("Current queue", "当前队列")
    AssistantPage.Frames -> uiText("Frame list", "栈帧列表")
    AssistantPage.Graphs -> uiText("Graph list", "流程图列表")
    AssistantPage.Terms -> uiText("Term list", "学期列表")
    AssistantPage.Settings -> uiText("Appearance and workspace restore settings", "外观与工作台恢复设置")
}

private fun reviewedTermName(termId: String) = uiText({ "$termId · Locally reviewed draft" }, { "$termId · 本地已核对草稿" })

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
    AssistantPage.Terms -> HistoryLocation("terms")
    is AssistantPage.CourseDraft -> HistoryLocation("terms", termId)
})

internal fun historyPage(key: String): AssistantPage = Json.decodeFromString<HistoryLocation>(key).page()

private fun HistoryLocation.page(): AssistantPage = when (type) {
    "day" -> AssistantPage.Day(LocalDate.parse(id), ZoneId.of(detail))
    "week" -> AssistantPage.Week(requireNotNull(id), requireNotNull(detail).toInt())
    "task" -> AssistantPage.Task(requireNotNull(id))
    "frame" -> AssistantPage.Frame(requireNotNull(id))
    "graph" -> AssistantPage.Graph(requireNotNull(id), detail)
    "queue" -> AssistantPage.Queue
    "frames" -> AssistantPage.Frames
    "graphs" -> AssistantPage.Graphs
    "settings" -> AssistantPage.Settings
    "terms" -> AssistantPage.Terms
    else -> error(uiText("Unknown conversation scope", "未知的对话范围"))
}

internal fun ConversationRow.resumePage(fallback: AssistantPage): AssistantPage =
    scopeKey?.let(::historyPage) ?: legacyPage(scope) ?: fallback

internal fun ConversationRow.displayScope(): String {
    val location = scopeKey?.let { Json.decodeFromString<HistoryLocation>(it) }
    if (location?.type == "terms" && location.id != null) return reviewedTermName(location.id)
    return (location?.page() ?: legacyPage(scope))?.scopeName() ?: scope
}

private fun legacyPage(scope: String): AssistantPage? {
    // Version 6 stored display labels only. Never turn a shortened entity ID into a tool capability.
    val simple = when (scope) {
        "当前队列", "Current queue" -> AssistantPage.Queue
        "栈帧列表", "Frame list" -> AssistantPage.Frames
        "流程图列表", "Graph list" -> AssistantPage.Graphs
        "学期列表", "Term list" -> AssistantPage.Terms
        "外观与工作台恢复设置", "Appearance and workspace restore settings" -> AssistantPage.Settings
        else -> null
    }
    if (simple != null) return simple
    val split = scope.lastIndexOf(" · ")
    if (split < 0) return null
    val first = scope.substring(0, split)
    val second = scope.substring(split + 3)
    if (second.startsWith("第 ") && second.endsWith(" 周")) {
        second.removePrefix("第 ").removeSuffix(" 周").toIntOrNull()?.let { return AssistantPage.Week(first, it) }
    }
    if (second.startsWith("Week ")) {
        second.removePrefix("Week ").toIntOrNull()?.let { return AssistantPage.Week(first, it) }
    }
    return try { AssistantPage.Day(LocalDate.parse(first), ZoneId.of(second)) }
        catch (_: java.time.DateTimeException) { null }
}

internal data class Disclosure(val extra: Set<ReadArea> = emptySet(), val notes: Boolean = false,
    val reasons: Boolean = false, val courseDetails: Boolean = false)

internal sealed interface ToolPlan {
    data class Commands(val commands: List<PlannerCommand>) : ToolPlan
    data object OpenImporter : ToolPlan
}
