package io.github.nakanomk.stacklife.fixtures

import io.github.nakanomk.stacklife.courses.BellSchedule
import io.github.nakanomk.stacklife.courses.TermSpec
import io.github.nakanomk.stacklife.courses.meeting
import io.github.nakanomk.stacklife.planner.TaskInput
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

internal fun sampleTask(n: Int) = TaskInput("基准任务 ${n.toString().padStart(2, '0')}", "滚动样本 $n", 20)

internal fun sampleTerm(id: String, today: LocalDate, weeks: Int) = TermSpec(id,
    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), weeks, BellSchedule.SUMMER)

internal fun sampleCourses(term: TermSpec, day: Int, week: Int) = (1..9 step 2).map { period ->
    term.meeting(week, day, "基准课程 $period", "", period, period + 1)
}
