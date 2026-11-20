package io.github.nakanomk.stacklife.assistant

import io.github.nakanomk.stacklife.planner.PlannerCommand
import java.time.LocalDate
import java.time.ZoneId

internal enum class ReadArea(val label: String) {
    DAY("其他日期的日程"), WORKSPACE("队列与其他栈帧"), GRAPHS("其他流程图"), COURSES("其他学期与周课表"), SETTINGS("恢复设置")
}

internal sealed class AssistantPage(val label: String) {
    data class Day(val date: LocalDate, val zone: ZoneId) : AssistantPage("问今天")
    data object Queue : AssistantPage("问队列")
    data object Frames : AssistantPage("问栈")
    data class Task(val id: String) : AssistantPage("问这个任务")
    data class Frame(val id: String) : AssistantPage("问这个栈帧")
    data object Graphs : AssistantPage("问流程图")
    data class Graph(val id: String, val nodeId: String? = null) : AssistantPage(if (nodeId == null) "问这张图" else "问这个节点")
    data object Terms : AssistantPage("问课表")
    data class Week(val termId: String, val week: Int) : AssistantPage("问本周课表")
    data class CourseDraft(val draftId: String, val termId: String) : AssistantPage("问这份导入")
    data object Settings : AssistantPage("问设置")
}

internal data class Disclosure(val extra: Set<ReadArea> = emptySet(), val notes: Boolean = false,
    val reasons: Boolean = false, val courseDetails: Boolean = false)

internal sealed interface ToolPlan {
    data class Commands(val commands: List<PlannerCommand>) : ToolPlan
    data object OpenImporter : ToolPlan
}
