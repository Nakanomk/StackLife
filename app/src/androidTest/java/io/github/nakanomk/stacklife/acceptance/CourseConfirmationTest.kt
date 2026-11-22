package io.github.nakanomk.stacklife.acceptance

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.SavedStateHandle
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

internal fun pickerReturning(file: File): ActivityResultRegistryOwner = object : ActivityResultRegistryOwner {
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(contract is ActivityResultContracts.OpenDocument)
            dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
        }
    }
}

@RunWith(AndroidJUnit4::class)
class CourseConfirmationTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)

    private val term = TermSpec("B-reviewed", LocalDate.of(2026, 9, 7), 2, BellSchedule.SUMMER)
    private val meeting = term.meeting(1, 1, "回执课", "A", 5, 6)

    // Stand in only for the system picker returning the test file. Reading, preview,
    // navigation, the retained activity ViewModel and the file-backed Room are all real.
    private fun attach(file: File? = null) = ui.activityRule.scenario.onActivity { activity ->
        val owner = file?.let(::pickerReturning) ?: activity
        activity.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
            }
        }
    }

    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(timeoutMillis = 10_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return ui.onNode(matcher)
    }

    private suspend fun withWriterHeld(block: () -> Unit) = coroutineScope {
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = launch(Dispatchers.IO) { store.db.withTransaction { locked.complete(Unit); release.await() } }
        try {
            withTimeout(10_000) { locked.await() }
            block()
        } finally {
            release.complete(Unit)
            writer.join()
        }
    }

    private suspend fun seed(spec: TermSpec, course: CourseMeeting) {
        val draft = store.api.stageCourseImport(spec, listOf(course))
        try { store.apply(PlannerCommand.CourseImport(draft)) } finally { store.api.discardCourseImport(draft) }
    }

    private suspend fun week(id: String) = store.api.observe(PlannerQuery.Week(id, 1)).first() as CourseWeek
    private suspend fun terms() = (store.api.observe(PlannerQuery.Terms).first() as Terms).terms

    @Test fun freshStateHolderReopensPrivateInputAndUnfinishedCorrectionFromOnlyAnId() {
        val root = java.nio.file.Files.createTempDirectory(ui.activity.noBackupFilesDir.toPath(), "restore-import-").toFile()
        try {
            val source = """[{"ZC":1,"MONDAY":[42,{"KCMC":"本地课程","JC":"未识别"}]}]"""
            val first = ui.runOnUiThread {
                ImportReviewState(SavedStateHandle(), root).also { state ->
                    state.receive(false) { source.byteInputStream().use(::readWeekFile) }
                }
            }
            ui.waitUntil(10_000) { ui.runOnUiThread { first.page == ImportPage.CALENDAR } }
            ui.runOnUiThread { first.selectedId = term.id; first.review(term.asForm()) }
            ui.waitUntil(10_000) { ui.runOnUiThread { first.page == ImportPage.ENTRIES } }
            val restored = ui.runOnUiThread {
                val (malformed, correction) = first.draft!!.issues
                first.decisions[malformed.position] = IssueDecision.Exclude
                first.editing = correction.position
                first.working = correction.fields!!.copy(period = "5-6", room = "未保存的教室")
                first.page = ImportPage.FIX
                val bundle = first.checkpoint()
                assertEquals(setOf("id"), bundle.keySet())
                java.util.UUID.fromString(bundle.getString("id"))
                ImportReviewState(SavedStateHandle(mapOf("import-review" to bundle)), root)
            }
            ui.waitUntil(10_000) { ui.runOnUiThread { !restored.busy } }
            ui.runOnUiThread {
                assertEquals(ImportPage.FIX, restored.page)
                assertEquals(first.rows, restored.rows)
                assertEquals(term, restored.draft!!.term)
                assertEquals(first.selectedId, restored.selectedId)
                assertEquals(first.decisions.toMap(), restored.decisions.toMap())
                assertEquals(first.editing, restored.editing)
                assertEquals(first.working, restored.working)
                restored.reset()
                assertTrue(root.listFiles()!!.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidSourceWeekAndPracticeRequireExplicitWheelConfirmations() = runBlocking {
        val before = store.state().revision
        val file = File.createTempFile("course-correction", ".json", ui.activity.cacheDir)
        try {
            file.writeText("""[{"ZC":1,"KS":"2026-09-07","MONDAY":[]},{"ZC":"99","MONDAY":[{"KCMC":"实验","JC":"实践","JSMC":"A"}]}]""")
            attach(file)
            await(hasTestTag("nav:courses")).performClick()
            await(hasTestTag("course-import")).performClick()
            await(hasTestTag("importCourseFile")).performClick()
            await(hasTestTag("importTerm:new")).assertIsSelected()
            ui.chooseNumber("termWeeks", term.weeks)
            ui.onNodeWithTag("reviewCourseRows").performScrollTo().performClick()
            await(hasTestTag("previewCourseImportTop")).assertIsNotEnabled()
            ui.onNodeWithTag("courseIssues").performScrollToNode(hasTestTag("correctIssue-0"))
            ui.onNodeWithTag("correctIssue-0").performClick()

            val unset = ui.activity.getString(R.string.wheel_unset)
            val save = ui.onNodeWithText(ui.activity.getString(R.string.courses_save_correction))
            ui.onNodeWithTag("fixOriginal").assertDoesNotExist()
            await(hasTestTag("fixOriginal-toggle")).performScrollTo().performClick()
            ui.onNodeWithTag("fixOriginal").assertTextContains("实践", substring = true)
            ui.onNodeWithTag("fixOriginal-toggle").performClick()
            ui.onNodeWithTag("fixWeek").assertTextContains(unset).performScrollTo().performClick()
            ui.adjustWheel("wheel-number", 2)
            ui.onNodeWithTag("wheel-cancel").performClick()
            ui.onNodeWithTag("fixWeek").assertTextContains(unset)
            save.assertIsNotEnabled()
            ui.chooseNumber("fixWeek", 2)
            ui.onNodeWithTag("fixPeriod").assertTextContains(unset).performScrollTo().performClick()
            ui.adjustWheel("wheel-first", 12)
            ui.onNodeWithTag("wheel-last").assert(hasStateDescription("12"))
            ui.onNodeWithTag("wheel-cancel").performClick()
            ui.onNodeWithTag("fixPeriod").assertTextContains(unset)
            save.assertIsNotEnabled()
            ui.onNodeWithTag("fixPeriod").performScrollTo().performClick()
            ui.adjustWheel("wheel-first", 5)
            ui.adjustWheel("wheel-last", 6)
            ui.onNodeWithTag("wheel-confirm").performClick()
            ui.activityRule.scenario.recreate()
            attach(file)
            await(hasTestTag("fixPeriod"))
            ui.onNodeWithTag("fixOriginal").assertDoesNotExist()
            save.assertIsEnabled()
            save.performScrollTo().performClick()

            await(hasTestTag("courseIssues")).performScrollToNode(hasTestTag("previewCourseImportTop"))
            await(hasTestTag("previewCourseImportTop") and isEnabled()).performClick()
            await(hasTestTag("confirmCourseImportTop"))
            assertEquals(before, store.state().revision)
            ui.onNodeWithTag("confirmCourseImportTop").performClick()
            await(hasTestTag("course-week") and hasText(term.dateRangeLabel()))
            val created = terms().single()
            assertEquals(term.copy(id = created.id), created)
            assertTrue(week(created.id).classes.isEmpty())
            val second = store.api.observe(PlannerQuery.Week(created.id, 2)).first() as CourseWeek
            assertEquals(listOf(created.meeting(2, 1, "实验", "A", 5, 6)), second.classes.map { it.meeting })
            assertEquals(before + 1, store.state().revision)
        } finally { file.delete() }
    }

    @Test fun recreatingDuringConfirmedImportOpensTheReviewedTermExactlyOnce() = runBlocking {
        // Without the receipt's target, CourseHome would default to this other term.
        val other = term.copy(id = "A-other", firstMonday = term.firstMonday.minusWeeks(12))
        seed(other, other.meeting(1, 2, "另一门", "B", 1, 1))
        val before = store.state().revision
        val file = File.createTempFile("course-confirmation", ".json", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeText("""[{"ZC":1,"KS":"2026-09-07","MONDAY":[{"KCMC":"回执课","JSMC":"A","JC":"5-6"}]}]""")
            attach(file)
            await(hasTestTag("nav:courses")).performClick()
            await(hasTestTag("course-week")).assertTextContains(other.dateRangeLabel())
            ui.onNodeWithContentDescription("课表选项").performClick()
            ui.onNodeWithTag("course-import").performClick()
            await(hasTestTag("importCourseFile")).performClick()
            await(hasTestTag("importTerm:new")).performScrollTo().performClick()
            ui.chooseNumber("termWeeks", term.weeks)
            ui.onNodeWithTag("reviewCourseRows").performScrollTo().performClick()
            await(hasTestTag("courseIssues")).performScrollToNode(hasTestTag("previewCourseImport"))
            ui.onNodeWithTag("previewCourseImport").performClick()
            val confirm = hasTestTag("confirmCourseImport")
            await(hasTestTag("courseImportDiff")).performScrollToNode(confirm)
            withWriterHeld {
                ui.onNode(confirm).performClick()
                ui.onNodeWithTag("cancelCourseImport").assertIsNotEnabled()
                ui.onNodeWithTag("confirmCourseImportTop").assertIsDisplayed().assertIsNotEnabled()
                ui.activityRule.scenario.recreate()
                attach(file)
                await(hasTestTag("cancelCourseImport")).assertIsNotEnabled()
                ui.onNodeWithTag("courseImportDiff").assertDoesNotExist()
                ui.onNodeWithTag("importCourseFile").assertDoesNotExist()
            }
            await(hasTestTag("course-week") and hasText(term.dateRangeLabel())).assertIsDisplayed()
            ui.onNodeWithTag("course-week").assertTextContains("第 1 周")
            val created = terms().single { it.firstMonday == term.firstMonday }
            assertEquals(listOf(meeting), week(created.id).classes.map { it.meeting })
            assertEquals(other, week(other.id).term)
            assertEquals(before + 1, store.state().revision)
        } finally { file.delete() }
    }

    @Test fun repeatedThreeWeekFileUsesTheChosenTimetableWithoutAddingMeetings() = runBlocking {
        val selected = term.copy(weeks = 3)
        val other = selected.copy(id = "other", firstMonday = selected.firstMonday.minusWeeks(4))
        seed(selected, selected.meeting(1, 4, "被替换", "", 3, 4))
        val untouched = other.meeting(1, 4, "保留", "B", 7, 8)
        seed(other, untouched)
        val file = File.createTempFile("three-weeks", ".json", ui.activity.cacheDir)
        try {
            file.writeText((1..3).joinToString(",", "[", "]") { w ->
                """{"ZC":$w,"MONDAY":[{"KCMC":"每周课程","JSMC":"A","JC":"1-2"}],"FRIDAY":[{"KCMC":"每周课程","JSMC":"B","JC":"3-4"}]}"""
            })
            attach(file)
            await(hasTestTag("nav:courses")).performClick()
            repeat(2) { pass ->
                val before = store.state().revision
                await(hasTestTag("course-options")).performClick()
                await(hasTestTag("course-import")).performClick()
                await(hasTestTag("importCourseFile")).performClick()
                await(hasTestTag("importTerm:${selected.id}")).performScrollTo().performClick()
                ui.onNodeWithTag("termWeeks").assertDoesNotExist()
                if (pass == 0) {
                    ui.activityRule.scenario.recreate()
                    attach(file)
                    await(hasTestTag("importTerm:${selected.id}")).assertIsSelected()
                }
                ui.onNodeWithTag("reviewCourseRows").performScrollTo().performClick()
                await(hasTestTag("previewCourseImportTop")).performClick()
                await(hasTestTag("confirmCourseImportTop"))
                if (pass == 1) await(hasText(ui.activity.getString(R.string.courses_unchanged)))
                ui.activityRule.scenario.recreate()
                attach(file)
                val list = await(hasTestTag("courseImportDiff"))
                list.performScrollToNode(hasTestTag("confirmCourseImport"))
                assertEquals(before, store.state().revision)
                await(hasTestTag("confirmCourseImportTop")).assertIsDisplayed().performClick()
                await(hasTestTag("course-week") and hasText(selected.dateRangeLabel()))
                assertEquals(setOf(selected, other), terms().toSet())
                assertEquals(listOf(untouched), week(other.id).classes.map { it.meeting })
                for (w in 1..3) {
                    val classes = (store.api.observe(PlannerQuery.Week(selected.id, w)).first() as CourseWeek).classes
                    assertEquals(listOf(selected.meeting(w, 1, "每周课程", "A", 1, 2), selected.meeting(w, 5, "每周课程", "B", 3, 4)),
                        classes.map { it.meeting })
                }
            }
        } finally { file.delete() }
    }

    @Test fun selectingOneImportedSourceNeverReviewsOrRemovesTheOther() = runBlocking {
        seed(term, meeting)
        val second = "test-campus"
        val api = RoomPlannerApi(store.db, SchoolImports.calendars + (second to HustCourseImporter.calendar))
        val id = api.stageCourseImport(term, listOf(meeting.copy(title = "第二来源")), second)
        val proposal = try { api.preview(listOf(PlannerCommand.CourseImport(id))) } finally { api.discardCourseImport(id) }
        assertTrue(proposal is PreviewResult.Ready)
        assertTrue(api.commit((proposal as PreviewResult.Ready).proposalId) is CommitResult.Applied)
        attach()
        await(hasTestTag("nav:courses")).performClick()
        await(hasTestTag("course-options")).performClick()
        await(hasTestTag("course-remove:$second")).performClick()
        await(hasText(ui.activity.getString(R.string.courses_remove_term_hint, second))).assertIsDisplayed()
        await(hasText("确认移除")).performClick()
        await(hasTestTag("course-week")).assertIsDisplayed()
        val remaining = week(term.id).classes.single()
        assertEquals(HUST_SOURCE, remaining.source)
        assertEquals(meeting, remaining.meeting)
    }

    @Test fun staleRemovalRequiresFreshPreviewThenSurvivesRecreationExactlyOnce() = runBlocking {
        seed(term, meeting)
        attach()
        await(hasTestTag("nav:courses")).performClick()
        await(hasContentDescription("课表选项")).performClick()
        await(hasTestTag("course-remove")).performClick()
        val confirm = hasText("确认移除")
        await(confirm)
        store.apply(PlannerCommand.TaskAdd(TaskInput("保留预约任务")))
        val before = store.state()
        ui.onNode(confirm).performClick()
        await(hasText("工作台已有变更，请重新预览。"))
        assertEquals(term, week(term.id).term)
        assertEquals(before.revision, store.state().revision)
        ui.onNodeWithText("重新预览").performClick()
        await(confirm)
        withWriterHeld {
            ui.onNode(confirm).performClick()
            ui.onNode(confirm).assertIsNotEnabled()
            ui.activityRule.scenario.recreate()
            attach()
            await(confirm).assertIsNotEnabled()
            ui.onNodeWithText("取消").assertIsNotEnabled()
        }
        await(hasText("还没有课表")).assertIsDisplayed()
        assertNull(week(term.id).term)
        val after = store.state()
        assertEquals(before.queue, after.queue)
        assertEquals(before.revision + 1, after.revision)
    }
}
