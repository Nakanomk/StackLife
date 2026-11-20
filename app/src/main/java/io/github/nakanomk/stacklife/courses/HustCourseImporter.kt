package io.github.nakanomk.stacklife.courses

import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// WadwaitaUp/models.py (MIT, see assets/licenses/WadwaitaUp-LICENSE.txt).
private fun periods(vararg times: String) = times.map {
    PeriodTime(LocalTime.parse(it.substring(0, 5)), LocalTime.parse(it.substring(6)))
}
private val morning = periods("08:00–08:45", "08:55–09:40", "10:10–10:55", "11:05–11:50")
private val summer = morning + periods("14:30–15:15", "15:20–16:05", "16:25–17:10", "17:15–18:00",
    "19:00–19:45", "19:50–20:35", "20:45–21:30", "21:35–22:20")
private val winter = morning + periods("14:00–14:45", "14:50–15:35", "15:55–16:40", "16:45–17:30",
    "18:30–19:15", "19:20–20:05", "20:15–21:00", "21:05–21:50")

fun TermSpec.periodsOn(date: LocalDate): List<PeriodTime> =
    if ((initial == BellSchedule.SUMMER) xor (switchDate != null && !date.isBefore(switchDate))) summer else winter

interface CourseImporter {
    val id: String
    fun parse(rows: JsonArray, term: TermSpec): ImportDraft
}

private val periodPattern = Regex("([0-9]{1,2})(?:\\s*[-–—]\\s*([0-9]{1,2}))?")
private val weekPattern = Regex("[0-9]{1,2}")
private val dayKeys = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")
private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
internal fun weekday(day: Int) = "周${weekdays[day - 1]}"

// Quoted and unquoted integers are found in HUB exports. Names and rooms must be strings.
private fun JsonElement?.numberText() = (this as? JsonPrimitive)?.takeUnless { it === JsonNull }?.content.orEmpty()
private fun JsonElement?.text() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

/** KS is only a hint from the row actually labelled week one; don't extrapolate an official calendar. */
internal fun firstWeekHints(rows: JsonArray): List<String> = rows.asSequence().filterIsInstance<JsonObject>()
    .filter { it["ZC"].numberText().trim().let { w -> weekPattern.matches(w) && w.toInt() == 1 } }
    .map { it["KS"].text().trim() }.filter(String::isNotEmpty).distinct().toList()

data class CourseFields(val week: String, val day: Int, val title: String, val room: String, val period: String) {
    private fun bounds(): IntRange? {
        val m = periodPattern.matchEntire(period.trim()) ?: return null
        val start = m.groupValues[1].toInt()
        val end = m.groups[2]?.value?.toInt() ?: start
        return if (start in 1..12 && end in start..12) start..end else null
    }

    fun problem(term: TermSpec): String? = when {
        !weekPattern.matches(week.trim()) || week.trim().toInt() !in 1..term.weeks -> "ZC 周次须在 1–${term.weeks} 之间"
        day !in 1..7 -> "星期须在 1–7 之间"
        bounds() == null -> "JC 节次须为 1–12 中的单节或正序区间"
        else -> courseFieldProblem(title, room)
    }

    internal fun occurrence(): Occurrence {
        val range = requireNotNull(bounds())
        return Occurrence(week.trim().toInt(), day, title, room, range.first, range.last)
    }
}

internal data class Occurrence(val week: Int, val day: Int, val title: String, val room: String, val first: Int, val last: Int)
data class ImportIssue(val position: String, val reason: String, val fields: CourseFields?)
sealed interface IssueDecision {
    data object Exclude : IssueDecision
    data class Correct(val fields: CourseFields) : IssueDecision
}

class ImportDraft internal constructor(val term: TermSpec, private val valid: Set<Occurrence>, val issues: List<ImportIssue>) {
    val validCount: Int get() = valid.size

    /** The only path to meetings: a decision for each source problem, and at least one actual class. */
    fun resolve(decisions: Map<String, IssueDecision>): List<CourseMeeting> {
        require(decisions.size == issues.size) { "须逐项修正或明确排除所有待修正项" }
        val all = valid.toMutableSet()
        for (issue in issues) when (val decision = decisions[issue.position]) {
            null -> throw IllegalArgumentException("${issue.position} 尚未处理")
            IssueDecision.Exclude -> Unit
            is IssueDecision.Correct -> {
                require(issue.fields != null) { "结构错误须重新选择文件或排除此项" }
                decision.fields.problem(term)?.let { throw IllegalArgumentException(it) }
                all += decision.fields.occurrence()
            }
        }
        require(all.isNotEmpty()) { "没有有效课程，不能覆盖已有课表" }
        return all.sortedWith(compareBy(Occurrence::week, Occurrence::day, Occurrence::first, Occurrence::last,
            Occurrence::title, Occurrence::room)).map { term.meeting(it.week, it.day, it.title, it.room, it.first, it.last) }
    }
}

object HustCourseImporter : CourseImporter {
    override val id = HUST_SOURCE

    override fun parse(rows: JsonArray, term: TermSpec): ImportDraft {
        term.problem()?.let { throw IllegalArgumentException(it) }
        require(rows.size <= 2048) { "周记录过多，请选择单个学期的数据" }
        var records = 0
        val valid = mutableSetOf<Occurrence>()
        val issues = mutableListOf<ImportIssue>()
        for ((rowIndex, rawRow) in rows.withIndex()) {
            val row = rawRow as? JsonObject
            val rowName = "第 ${rowIndex + 1} 行"
            if (row == null || dayKeys.none { it in row }) {
                issues += ImportIssue(rowName, "不是含星期字段的 HUB 周记录", null)
                continue
            }
            val week = row["ZC"].numberText()
            var empty = true
            for ((i, key) in dayKeys.withIndex()) {
                val day = i + 1
                val entries = row[key] ?: continue
                if (entries === JsonNull) continue
                val dayName = "$rowName · ${weekday(day)}"
                if (entries !is JsonArray) {
                    issues += ImportIssue(dayName, "星期字段不是课程数组", null)
                    empty = false
                    continue
                }
                require(entries.size <= 16000 - records) { "课程项过多，请选择单个学期的数据" }
                records += entries.size
                for ((index, raw) in entries.withIndex()) {
                    empty = false
                    val position = "$dayName · 第 ${index + 1} 项"
                    val item = raw as? JsonObject
                    if (item == null) {
                        issues += ImportIssue(position, "课程项不是对象", null)
                        continue
                    }
                    val fields = CourseFields(week, day, item["KCMC"].text(), item["JSMC"].text(), item["JC"].numberText())
                    val room = item["JSMC"]
                    val problem = if (room != null && room !== JsonNull && (room !is JsonPrimitive || !room.isString))
                        "JSMC 地点不是文字，请重新填写或明确留空" else fields.problem(term)
                    if (problem == null) valid += fields.occurrence() else issues += ImportIssue(position, problem, fields)
                }
            }
            if (empty && (!weekPattern.matches(week.trim()) || week.trim().toInt() !in 1..term.weeks))
                issues += ImportIssue(rowName, "空周记录的 ZC 超出本学期范围", null)
        }
        return ImportDraft(term, valid, issues)
    }
}
