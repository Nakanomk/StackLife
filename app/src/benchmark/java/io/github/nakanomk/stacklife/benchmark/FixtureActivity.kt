package io.github.nakanomk.stacklife.benchmark

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.nakanomk.stacklife.StackLifeApplication
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FixtureActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val status = TextView(this)
        setContentView(status)
        val glass = intent.getBooleanExtra("glass", false)
        val planner = (application as StackLifeApplication).planner
        lifecycleScope.launch {
            val queue = (planner.observe(PlannerQuery.Queue).first() as QueueView).tasks
            val date = LocalDate.now(campusZone)
            val term = TermSpec("benchmark-day", date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), 1, BellSchedule.SUMMER)
            val courses = (1..9 step 2).map { period -> term.meeting(1, date.dayOfWeek.value, "基准课程 $period", "", period, period + 1) }
            val staged = planner.stageCourseImport(term, courses)
            try {
                val commands = buildList {
                    if (queue.isEmpty()) for (n in 1..30) add(PlannerCommand.TaskAdd(TaskInput("基准任务 ${n.toString().padStart(2, '0')}", "滚动样本 $n", 20)))
                    else check(queue.size == 30 && queue.first().title == "基准任务 01") { "请清理专用 .bench 包后再运行。" }
                    add(PlannerCommand.CourseImport(staged))
                    add(PlannerCommand.EditAppearance(AppearancePatch.Replace(Appearance(start = Destination.WORKSPACE, glass = glass))))
                }
                val preview = planner.preview(commands)
                if (preview is PreviewResult.Ready) check(planner.commit(preview.proposalId) is CommitResult.Applied)
                val persisted = planner.observe(PlannerQuery.Settings).first() as PolicyView
                val stored = planner.observe(PlannerQuery.Week(term.id, 1)).first() as CourseWeek
                check(stored.classes.map { it.meeting } == courses && persisted.appearance.start == Destination.WORKSPACE && persisted.appearance.glass == glass)
                check((planner.observe(PlannerQuery.Queue).first() as QueueView).tasks.size == 30)
                status.text = "fixture:30:5:$glass"
            } finally { planner.discardCourseImport(staged) }
        }
    }
}
