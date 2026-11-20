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
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
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

    @Test fun recreatingDuringConfirmedImportOpensTheReviewedTermExactlyOnce() = runBlocking {
        // Without the receipt's target, CourseHome would default to this other term.
        val other = term.copy(id = "A-other")
        seed(other, other.meeting(1, 2, "另一门", "B", 1, 1))
        val before = store.state().revision
        val file = File.createTempFile("course-confirmation", ".json", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeText("""[{"ZC":1,"KS":"2026-09-07","MONDAY":[{"KCMC":"回执课","JSMC":"A","JC":"5-6"}]}]""")
            attach(file)
            await(hasTestTag("nav:courses")).performClick()
            await(hasTestTag("course-term")).assertTextContains(other.id)
            ui.onNodeWithTag("course-import").performClick()
            await(hasTestTag("importCourseFile")).performClick()
            await(hasTestTag("termId")).performScrollTo().performTextInput(term.id)
            ui.onNodeWithTag("termWeeks").performScrollTo().performTextInput(term.weeks.toString())
            ui.onNodeWithTag("reviewCourseRows").performScrollTo().performClick()
            await(hasTestTag("courseIssues")).performScrollToNode(hasTestTag("previewCourseImport"))
            ui.onNodeWithTag("previewCourseImport").performClick()
            val confirm = hasTestTag("confirmCourseImport")
            await(hasTestTag("courseImportDiff")).performScrollToNode(confirm)
            withWriterHeld {
                ui.onNode(confirm).performClick()
                ui.onNodeWithTag("cancelCourseImport").assertIsNotEnabled()
                ui.activityRule.scenario.recreate()
                attach(file)
                await(hasTestTag("cancelCourseImport")).assertIsNotEnabled()
                ui.onNodeWithTag("courseImportDiff").assertDoesNotExist()
                ui.onNodeWithTag("importCourseFile").assertDoesNotExist()
            }
            await(hasTestTag("course-term") and hasText(term.id)).assertIsDisplayed()
            ui.onNodeWithTag("course-week").assertTextContains("第 1 周")
            assertEquals(listOf(meeting), week(term.id).classes.map { it.meeting })
            assertEquals(other, week(other.id).term)
            assertEquals(before + 1, store.state().revision)
        } finally { file.delete() }
    }

    @Test fun staleRemovalRequiresFreshPreviewThenSurvivesRecreationExactlyOnce() = runBlocking {
        seed(term, meeting)
        attach()
        await(hasTestTag("nav:courses")).performClick()
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
