package io.github.nakanomk.stacklife.acceptance

import android.view.ViewGroup
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.descendants
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.courses.BellSchedule
import io.github.nakanomk.stacklife.courses.HUB_LOGIN_URL
import io.github.nakanomk.stacklife.courses.HUB_URL
import io.github.nakanomk.stacklife.courses.TermSpec
import io.github.nakanomk.stacklife.courses.meeting
import io.github.nakanomk.stacklife.courses.dateRangeLabel
import io.github.nakanomk.stacklife.planner.CourseWeek
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.planner.Terms
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HubMobileImportTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)

    private fun await(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(timeoutMillis = 10_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return ui.onNode(matcher)
    }

    @Test fun phoneExtractionOpensTermReviewAndSavesOnlyAfterConfirmation() = runBlocking {
        val term = TermSpec("手机提取测试学期", LocalDate.of(2026, 9, 7), 2, BellSchedule.SUMMER)
        val title = "合成课程：手机提取实验"
        val room = "合成教室 B"
        val before = store.state().revision
        suspend fun assertNotImported() {
            assertEquals(before, store.state().revision)
            assertTrue((store.api.observe(PlannerQuery.Terms).first() as Terms).terms.isEmpty())
        }

        ui.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        await(hasTestTag("nav:courses")).performClick()
        await(hasTestTag("course-import")).performClick()
        await(hasTestTag("importCourseHub")).performClick()
        await(hasTestTag("hubExtract"))

        val fixtureTitle = "StackLife synthetic timetable"
        val fixture = """<!doctype html><html><head><meta charset="utf-8"><title>$fixtureTitle</title></head><body>
            <table class="el-table"></table><script>
            document.querySelector('table').__vue__ = {weekTableData: [
                {ZC: 1, KS: '${term.firstMonday}', WEDNESDAY: [{KCMC: '$title', JC: '5-6', JSMC: '$room'}, {KCMC: '待核对', JC: '实践'}]}
            ]};
            </script></body></html>"""
        val scheduleButton = hasText("总课表") and isEnabled()
        await(scheduleButton)
        val webView = ui.runOnIdle {
            val web = ui.activity.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<WebView>().single()
            web.serveTestPages { request ->
                val html = when (request.url.toString()) {
                    HUB_LOGIN_URL -> "<title>Local landing</title>"
                    HUB_URL -> fixture
                    else -> ""
                }
                WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
            }
            web.loadUrl(HUB_LOGIN_URL)
            web
        }
        ui.waitUntil(timeoutMillis = 10_000) { ui.runOnUiThread { webView.title == "Local landing" && webView.progress == 100 } }
        ui.onNodeWithTag("hubImportHelp").assertIsDisplayed().performClick()
        ui.onNodeWithText("点击页面内的“总课表”按钮", substring = true).assertIsDisplayed()
        ui.onNodeWithTag("hubHelpClose").performClick()
        assertEquals("Local landing", ui.runOnUiThread { webView.title })
        ui.onNodeWithTag("hubExtract").assertIsNotEnabled()
        ui.onNode(scheduleButton).performClick()
        ui.waitUntil(timeoutMillis = 10_000) { ui.runOnUiThread { webView.title == fixtureTitle && webView.progress == 100 } }
        await(hasTestTag("hubExtract") and isEnabled()).performClick()

        await(hasTestTag("termMonday")).assertTextContains(term.firstMonday.toString())
        assertNotImported()
        ui.onNodeWithTag("importTerm:new").assertIsSelected()
        ui.chooseNumber("termWeeks", term.weeks)
        ui.onNodeWithTag("reviewCourseRows").performScrollTo().performClick()
        val list = await(hasTestTag("courseIssues"))
        val preview = hasTestTag("previewCourseImportTop")
        await(preview).assertIsDisplayed().assertIsNotEnabled()
        list.performScrollToNode(hasTestTag("previewCourseImport"))
        ui.onNodeWithTag("previewCourseImport").assertIsNotEnabled()
        list.performScrollToNode(hasTestTag("excludeIssue-0"))
        ui.onNodeWithTag("issueRaw-0").assertDoesNotExist()
        list.performScrollToNode(hasTestTag("issueRaw-0-toggle"))
        ui.onNodeWithTag("issueRaw-0-toggle").performClick()
        ui.onNodeWithTag("issueRaw-0").assertTextContains("实践", substring = true)
        ui.onNodeWithTag("issueRaw-0-toggle").performClick()
        list.performScrollToNode(hasTestTag("excludeIssue-0"))
        ui.onNodeWithTag("excludeIssue-0").performClick()
        list.performScrollToNode(preview)
        ui.onNode(preview).assertIsEnabled().performClick()
        val confirm = hasTestTag("confirmCourseImportTop")
        await(confirm).assertIsDisplayed()
        assertNotImported()
        ui.onNode(confirm).performClick()

        await(hasTestTag("course-week") and hasText(term.dateRangeLabel())).assertIsDisplayed()
        ui.onNodeWithTag("course-week").assertTextContains("第 1 周")
        val created = (store.api.observe(PlannerQuery.Terms).first() as Terms).terms.single()
        val week = store.api.observe(PlannerQuery.Week(created.id, 1)).first() as CourseWeek
        assertEquals(term.copy(id = created.id), week.term)
        assertEquals(listOf(term.meeting(1, 3, title, room, 5, 6)), week.classes.map { it.meeting })
        assertEquals(before + 1, store.state().revision)
    }
}
