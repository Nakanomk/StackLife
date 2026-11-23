package io.github.nakanomk.stacklife.courses

import androidx.compose.runtime.Composable
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.WorkspaceViewModel
import java.time.ZoneId
import io.github.nakanomk.stacklife.i18n.uiText
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

const val HUST_SOURCE = "hust-hub"

private object HustCalendar : CourseCalendar {
    override val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    override fun scheduleDescription(term: TermSpec): String = with(term) {
        (if (initial == BellSchedule.SUMMER) uiText("Summer schedule", "夏令作息") else uiText("Winter schedule", "冬令作息")) +
            (switchDate?.let { uiText({ ", switching on $it" }, { "，$it 起切换" }) } ?: "")
    }
    override fun periods(term: TermSpec, date: LocalDate): List<PeriodTime> = with(term) {
        if ((initial == BellSchedule.SUMMER) xor (switchDate != null && !date.isBefore(switchDate))) summer else winter
    }
}

private val periodPattern = Regex("([0-9]{1,2})(?:\\s*[-–—]\\s*([0-9]{1,2}))?")
private val weekPattern = Regex("[0-9]{1,2}")
private val dayKeys = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")

/** Preserve the provider's original value for explicit inspection, including malformed records. */
internal fun JsonArray.sourceValue(issue: ImportIssue): JsonElement {
    val row = this[issue.row - 1]
    val day = issue.day ?: return row
    val entries = (row as JsonObject).getValue(dayKeys[day - 1])
    return issue.item?.let { (entries as JsonArray)[it - 1] } ?: entries
}

// Quoted and unquoted integers are found in HUB exports. Names and rooms must be strings.
private fun JsonElement?.numberText() = (this as? JsonPrimitive)?.takeUnless { it === JsonNull }?.content.orEmpty()
private fun JsonElement?.text() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

/** KS is only a hint from the row actually labelled week one; don't extrapolate an official calendar. */
internal fun firstWeekHints(rows: JsonArray): List<String> = rows.asSequence().filterIsInstance<JsonObject>()
    .filter { it["ZC"].numberText().trim().let { w -> weekPattern.matches(w) && w.toInt() == 1 } }
    .map { it["KS"].text().trim() }.filter(String::isNotEmpty).distinct().toList()

data class CourseFields(val week: String, val day: Int, val title: String, val room: String, val period: String) {
    internal fun weekNumber(term: TermSpec): Int? =
        week.trim().takeIf(weekPattern::matches)?.toInt()?.takeIf { it in 1..term.weeks }

    internal fun periodRange(): IntRange? {
        val m = periodPattern.matchEntire(period.trim()) ?: return null
        val start = m.groupValues[1].toInt()
        val end = m.groups[2]?.value?.toInt() ?: start
        return if (start in 1..12 && end in start..12) start..end else null
    }

    fun problem(term: TermSpec): String? = when {
        weekNumber(term) == null -> uiText({ "ZC week number must be between 1 and ${term.weeks}." }, { "ZC 周次须在 1–${term.weeks} 之间" })
        day !in 1..7 -> uiText("Day of week must be between 1 and 7.", "星期须在 1–7 之间")
        periodRange() == null -> uiText("JC must be a single period or an ascending range within 1–12.", "JC 节次须为 1–12 中的单节或正序区间")
        else -> courseFieldProblem(title, room)
    }

    internal fun occurrence(): Occurrence {
        val range = requireNotNull(periodRange())
        return Occurrence(week.trim().toInt(), day, title, room, range.first, range.last)
    }
}

internal data class Occurrence(val week: Int, val day: Int, val title: String, val room: String, val first: Int, val last: Int)
data class ImportIssue(
    val row: Int, val day: Int?, val item: Int?, val reason: String, val fields: CourseFields?,
) {
    // Raw one-based coordinates identify a decision. Translated labels never do.
    val position = "$row/${day ?: 0}/${item ?: 0}"
    val label get() = buildString {
        append(uiText({ "Row $row" }, { "第 $row 行" }))
        if (day != null) append(" · ").append(weekday(day))
        if (item != null) append(uiText({ " · Item $item" }, { " · 第 $item 项" }))
    }
}
sealed interface IssueDecision {
    data object Exclude : IssueDecision
    data class Correct(val fields: CourseFields) : IssueDecision
}

internal class ImportDraft(val provider: SchoolImportProvider, val term: TermSpec, val valid: Set<Occurrence>, val issues: List<ImportIssue>) {
    val validCount: Int get() = valid.size

    /** The only path to meetings: a decision for each source problem, and at least one actual class. */
    fun resolve(decisions: Map<String, IssueDecision>): ReviewedTimetable {
        require(decisions.size == issues.size) { uiText("Correct or explicitly exclude every flagged entry.", "须逐项修正或明确排除所有待修正项") }
        val all = valid.toMutableSet()
        for (issue in issues) when (val decision = decisions[issue.position]) {
            null -> throw IllegalArgumentException(uiText({ "${issue.label} still needs review." }, { "${issue.label} 尚未处理" }))
            IssueDecision.Exclude -> Unit
            is IssueDecision.Correct -> {
                require(issue.fields != null) { uiText("Select a new file or exclude this malformed entry.", "结构错误须重新选择文件或排除此项") }
                decision.fields.problem(term)?.let { throw IllegalArgumentException(it) }
                all += decision.fields.occurrence()
            }
        }
        require(all.isNotEmpty()) { uiText("There are no valid classes. The existing timetable cannot be replaced.", "没有有效课程，不能覆盖已有课表") }
        val meetings = all.sortedWith(compareBy(Occurrence::week, Occurrence::day, Occurrence::first, Occurrence::last,
            Occurrence::title, Occurrence::room)).map { term.meeting(it.week, it.day, it.title, it.room, it.first, it.last, provider.calendar) }
        return ReviewedTimetable(provider.id, term, meetings)
    }
}

internal object HustCourseImporter : SchoolImportProvider {
    override val id = HUST_SOURCE
    override val label = R.string.courses_hub_source
    override val help = R.string.courses_hub_help
    override val calendar: CourseCalendar = HustCalendar
    @Composable override fun Import(model: WorkspaceViewModel, entry: ImportEntry, onBack: () -> Unit, onCancel: () -> Unit,
        onFileEntry: () -> Unit, onImported: (String) -> Unit) =
        HustImportScreen(model, entry, onBack, onCancel, onFileEntry, onImported)

    fun parse(rows: JsonArray, term: TermSpec): ImportDraft {
        term.problem()?.let { throw IllegalArgumentException(it) }
        require(rows.size <= 2048) { uiText("Too many week records. Select data for a single term.", "周记录过多，请选择单个学期的数据") }
        var records = 0
        val valid = mutableSetOf<Occurrence>()
        val issues = mutableListOf<ImportIssue>()
        for ((rowIndex, rawRow) in rows.withIndex()) {
            val row = rawRow as? JsonObject
            val rowNumber = rowIndex + 1
            if (row == null || dayKeys.none { it in row }) {
                issues += ImportIssue(rowNumber, null, null, uiText("Not a HUB week record with weekday fields.", "不是含星期字段的 HUB 周记录"), null)
                continue
            }
            val week = row["ZC"].numberText()
            var empty = true
            for ((i, key) in dayKeys.withIndex()) {
                val day = i + 1
                val entries = row[key] ?: continue
                if (entries === JsonNull) continue
                if (entries !is JsonArray) {
                    issues += ImportIssue(rowNumber, day, null, uiText("The weekday field is not a course array.", "星期字段不是课程数组"), null)
                    empty = false
                    continue
                }
                require(entries.size <= 16000 - records) { uiText("Too many course entries. Select data for a single term.", "课程项过多，请选择单个学期的数据") }
                records += entries.size
                for ((index, raw) in entries.withIndex()) {
                    empty = false
                    val item = raw as? JsonObject
                    if (item == null) {
                        issues += ImportIssue(rowNumber, day, index + 1, uiText("Course entry is not an object.", "课程项不是对象"), null)
                        continue
                    }
                    val fields = CourseFields(week, day, item["KCMC"].text(), item["JSMC"].text(), item["JC"].numberText())
                    val room = item["JSMC"]
                    val problem = if (room != null && room !== JsonNull && (room !is JsonPrimitive || !room.isString))
                        uiText("JSMC location is not text. Enter a location or explicitly leave it blank.", "JSMC 地点不是文字，请重新填写或明确留空") else fields.problem(term)
                    if (problem == null) valid += fields.occurrence() else issues += ImportIssue(rowNumber, day, index + 1, problem, fields)
                }
            }
            if (empty && (!weekPattern.matches(week.trim()) || week.trim().toInt() !in 1..term.weeks))
                issues += ImportIssue(rowNumber, null, null, uiText("The ZC value of the empty week record falls outside the term.", "空周记录的 ZC 超出本学期范围"), null)
        }
        return ImportDraft(this, term, valid, issues)
    }
}
