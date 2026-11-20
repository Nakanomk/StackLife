package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.assistant.Disclosure
import io.github.nakanomk.stacklife.assistant.PlannerTools
import io.github.nakanomk.stacklife.assistant.ToolPlan
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualCourseEntryTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    private fun await(tag: String): SemanticsNodeInteraction {
        val matcher = hasTestTag(tag) and isEnabled()
        try {
            ui.waitUntil(timeoutMillis = 10_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: ComposeTimeoutException) {
            val roots = ui.onAllNodes(isRoot(), useUnmergedTree = true)
            val tree = roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() }
            throw AssertionError("未找到可用的 $tag：\n$tree", failure)
        }
        return ui.onNode(matcher)
    }
    private fun awaitClosed() = ui.waitUntil(timeoutMillis = 10_000) {
        ui.onAllNodesWithTag("manual-course-sheet").fetchSemanticsNodes().isEmpty()
    }
    private fun field(tag: String, value: String) {
        await(tag).performScrollTo().assertIsDisplayed().performTextReplacement(value)
        await(tag).assertTextContains(value)
    }
    private fun period(tag: String, n: Int) {
        await(tag).performScrollTo().performClick()
        await("$tag:$n").performScrollTo().performClick()
    }
    private suspend fun week(n: Int) = store.api.observe(PlannerQuery.Week("手动学期", n)).first() as CourseWeek

    @Test fun authorizedAssistantSeriesAppearsOnTodaysAxisAfterLocalConfirmationAndReopen(): Unit = runBlocking {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // Campus period five, on whichever Shanghai date maps to this device's current civil day.
        val campusDate = (-1L..1L).map(today::plusDays).first {
            it.atTime(14, 30).atZone(campusZone).withZoneSameInstant(zone).toLocalDate() == today
        }
        val monday = campusDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val args = Json.parseToJsonElement("""{"commands":[{"kind":"manual_course_add","termId":"测试学期","title":"现场课程","room":"独立地点","day":${campusDate.dayOfWeek.value},"weeks":[1],"firstPeriod":5,"lastPeriod":6,"newTerm":{"firstMonday":"$monday","totalWeeks":2,"initial":"SUMMER"}}]}""").jsonObject
        val plan = PlannerTools(store.api, AssistantPage.Terms, Disclosure(courseDetails = true)).proposal(args) as ToolPlan.Commands
        val ready = store.api.preview(plan.commands) as PreviewResult.Ready
        val day = PlannerQuery.Day(today, zone)
        assertTrue((store.api.observe(day).first() as DayPlan).courses.isEmpty())
        assertTrue(store.api.commit(ready.proposalId) is CommitResult.Applied)
        store.reopen()
        val privateRead = PlannerTools(store.api, AssistantPage.Day(today, zone), Disclosure())
            .query(Json.parseToJsonElement("""{"kind":"current"}""").jsonObject).toString()
        assertTrue("busy" in privateRead); assertFalse("现场课程" in privateRead || "独立地点" in privateRead)
        ui.activityRule.scenario.onActivity { activity -> activity.setContent {
            StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
        } }
        await("day-course").performScrollTo().performClick()
        ui.onNode(hasText("现场课程") and hasAnyAncestor(hasTestTag("course-detail"))).assertIsDisplayed()
    }

    @Test fun emptyTimetableToRealSeriesThenMenuCancelEditAndRemove() = runBlocking {
        ui.activityRule.scenario.onActivity { activity -> activity.setContent {
            StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
        } }
        await("nav:courses").performClick()
        await("course-add").assertIsDisplayed().performClick()
        field("termId", "手动学期"); field("termMonday", "2026-09-07"); field("termWeeks", "3")
        field("manual-title", "线性代数"); field("manual-room", "东101")
        await("manual-weeks").performScrollTo().assertIsDisplayed().performClick()
        await("manual-week:3").performScrollTo().assertIsDisplayed().performClick()
        await("manual-weeks-done").assertIsDisplayed().performClick()
        await("manual-weeks").assertTextContains("第 1、3 周")
        await("manual-day").performScrollTo().performClick()
        await("manual-day:2").performClick()
        period("manual-first", 5); period("manual-last", 6)
        await("manual-preview").performClick(); await("manual-review").assertExists()
        assertNull(week(1).term)
        await("manual-save").performClick(); awaitClosed(); await("grid-course:0").performClick()
        await("course-detail").assertExists()
        ui.onNode(hasText("线性代数") and hasAnyAncestor(hasTestTag("course-detail"))).assertExists()
        val class1 = week(1).classes.single()
        assertEquals(MANUAL_SOURCE, class1.source); assertEquals(2, class1.meeting.day)
        assertEquals(5, class1.meeting.firstPeriod); assertEquals(6, class1.meeting.lastPeriod)
        assertEquals("东101", class1.meeting.room); assertTrue(week(2).classes.isEmpty()); assertEquals("线性代数", week(3).classes.single().meeting.title)
        val day = store.api.observe(PlannerQuery.Day(LocalDate.parse("2026-09-08"), campusZone)).first() as DayPlan
        assertEquals(class1, day.courses.single())
        ui.onNodeWithText("关闭").performClick(); await("course-options").performClick(); await("course-add").performClick()
        field("manual-title", "不保存"); await("manual-cancel").performClick(); awaitClosed()
        await("course-options").performClick(); await("course-add").performClick(); field("manual-title", "本周新课")
        await("manual-preview").performClick(); await("manual-save").performClick(); awaitClosed()
        assertEquals(listOf("本周新课", "线性代数"), week(1).classes.map { it.meeting.title })
        await("grid-course:1").performClick(); await("manual-edit").performClick(); field("manual-title", "数值分析")
        await("manual-preview").performClick(); await("manual-save").performClick()
        awaitClosed()
        assertEquals("数值分析", week(3).classes.single().meeting.title)
        await("grid-course:1").performClick(); await("manual-edit").performClick()
        await("manual-remove").performScrollTo().assertIsDisplayed().performClick()
        await("manual-save").performClick(); awaitClosed()
        assertEquals("本周新课", week(1).classes.single().meeting.title)
        assertTrue(week(3).classes.isEmpty())
    }
}
