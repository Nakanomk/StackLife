package io.github.nakanomk.stacklife.benchmark

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import io.github.nakanomk.stacklife.StackLifeApplication
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.i18n.AppLocales
import io.github.nakanomk.stacklife.planner.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val RUNNER = "io.github.nakanomk.stacklife.macrobenchmark"

class FixtureProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = checkNotNull(context).applicationContext as StackLifeApplication
        val uid = Binder.getCallingUid()
        // Android admits a provider client with either read or write permission.
        // ContentProvider.call must enforce the appropriate caller policy itself.
        if (uid == Process.SHELL_UID || uid == Process.ROOT_UID) {
            app.enforceCallingPermission(Manifest.permission.DUMP, "Benchmark shell fixture")
        } else {
            app.enforceCallingPermission("${app.packageName}.permission.SEED_FIXTURE", "Benchmark runner fixture")
            val pm = app.packageManager
            if (callingPackage != RUNNER || pm.getPackageUid(RUNNER, 0) != uid ||
                pm.checkSignatures(app.packageName, RUNNER) != PackageManager.SIGNATURE_MATCH) {
                throw SecurityException("Fixture requires the signed benchmark runner")
            }
        }
        require(method == "seed") { "Unknown fixture method: $method" }
        val glass = requireNotNull(arg?.toBooleanStrictOrNull()) { "Expected glass argument true or false" }
        return runBlocking(Dispatchers.IO) { withTimeout(25_000) { seed(app, glass) } }
    }

    private suspend fun seed(app: StackLifeApplication, glass: Boolean): Bundle {
        val planner = app.planner
        check(app.connections.profile() == null) { "The .bench fixture must not have a model connection." }
        val queue = (planner.observe(PlannerQuery.Queue).first() as QueueView).tasks
        val date = LocalDate.now(campusZone)
        val term = TermSpec("benchmark-day", date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), 1, BellSchedule.SUMMER)
        val courses = (1..9 step 2).map { period -> term.meeting(1, date.dayOfWeek.value, "基准课程 $period", "", period, period + 1) }
        val expected = Appearance(start = Destination.WORKSPACE, glass = glass)
        val settings = planner.observe(PlannerQuery.Settings).first() as PolicyView
        val weekQuery = PlannerQuery.Week(term.id, 1)
        val week = planner.observe(weekQuery).first() as CourseWeek
        val countdowns = (planner.observe(PlannerQuery.Countdowns).first() as CountdownsView).items
        val graphs = (planner.observe(PlannerQuery.Graphs).first() as GraphList).graphs
        val staged = planner.stageCourseImport(term, courses)
        try {
            val commands = buildList {
                if (queue.isEmpty()) for (n in 1..30) add(PlannerCommand.TaskAdd(TaskInput("基准任务 ${n.toString().padStart(2, '0')}", "滚动样本 $n", 20)))
                else check(queue.size == 30 && queue.first().title == "基准任务 01") { "Unexpected data in dedicated .bench package" }
                if (week.term != term || week.classes.map { it.meeting } != courses) add(PlannerCommand.CourseImport(staged))
                if (countdowns.isEmpty()) {
                    add(PlannerCommand.CountdownAdd("基准日期 A", date.plusDays(7).toEpochDay()))
                    add(PlannerCommand.CountdownAdd("基准日期 B", date.plusDays(14).toEpochDay()))
                }
                if (graphs.isEmpty()) {
                    add(PlannerCommand.GraphCreate("基准流程", ref = "benchGraph"))
                    add(PlannerCommand.GraphPatch("@benchGraph", listOf(
                        GraphEdit.AddNode("基准起点", x = 16f, y = 16f, ref = "source"),
                        GraphEdit.AddNode("基准终点", x = 190f, y = 150f, ref = "target"),
                        GraphEdit.AddEdge("@source", "@target"),
                    )))
                }
                if (settings.appearance != expected) add(PlannerCommand.EditAppearance(AppearancePatch.Replace(expected)))
                if (settings.onboardingVersion != 1) add(PlannerCommand.CompleteOnboarding())
            }
            if (commands.isNotEmpty()) {
                val preview = planner.preview(commands)
                check(preview is PreviewResult.Ready) { "Fixture preview failed: $preview" }
                val receipt = planner.commit(preview.proposalId)
                check(receipt is CommitResult.Applied) { "Fixture commit failed: $receipt" }
            }
            val persisted = planner.observe(PlannerQuery.Settings).first() as PolicyView
            val stored = planner.observe(weekQuery).first() as CourseWeek
            check(persisted.onboardingVersion == 1)
            check(stored.term == term && stored.classes.map { it.meeting } == courses && persisted.appearance == expected)
            val count = (planner.observe(PlannerQuery.Queue).first() as QueueView).tasks.size
            check(count == 30)
            val locale = AppLocales.sync(app).toLanguageTag()
            return Bundle().apply { putString("receipt", "$count:${stored.classes.size}:${persisted.appearance.glass}:$locale") }
        } finally { planner.discardCourseImport(staged) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        throw UnsupportedOperationException()
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
}
