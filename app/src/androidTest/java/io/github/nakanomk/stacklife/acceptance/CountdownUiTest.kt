package io.github.nakanomk.stacklife.acceptance

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountdownUiTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    private fun list() = runBlocking { store.api.observe(PlannerQuery.Countdowns).first() as CountdownsView }
    private fun attach() = ui.activityRule.scenario.onActivity { activity ->
        activity.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
    }
    private fun await(m: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(10_000) { ui.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }; return ui.onNode(m)
    }
    private fun tag(s: String) = await(hasTestTag(s))
    private fun back() = await(hasContentDescription("返回")).performClick()
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureCountdown") != "yes") return
        ui.waitForIdle()
        val inst = InstrumentationRegistry.getInstrumentation(); inst.uiAutomation.waitForIdle(450, 5000)
        val image = checkNotNull(inst.uiAutomation.takeScreenshot())
        try { File(inst.targetContext.getExternalFilesDir(null), "u7-countdown-$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }

    @Test fun previewDoesNotWriteUntilSavedAndDetailSwipesEditTheVisibleIdentity() {
        val now = LocalDate.now()
        runBlocking { store.apply(PlannerCommand.TaskAdd(TaskInput("不受影响的任务")),
            PlannerCommand.CountdownAdd("阅读开始", now.minusDays(30).toEpochDay(), CountdownMode.ACCUMULATE),
            PlannerCommand.CountdownAdd("展览开幕", now.plusDays(10).toEpochDay())) }
        val queue = runBlocking { store.state().queue }
        val before = list(); val (reading, exhibit) = before.items
        attach(); tag("nav:countdown").performClick(); tag("countdown-add").performClick()
        tag("countdown-name").performTextReplacement("未保存的日期"); tag("countdown-preview").assertTextContains("未保存的日期")
        assertEquals(before, list()); back(); tag("countdown-add").performClick()
        tag("countdown-name").performTextReplacement("研讨会开幕")
        tag("countdown-date").performScrollTo().performClick()
        tag("wheel-year").performSemanticsAction(SemanticsActions.SetProgress) { it(2028f) }
        tag("wheel-month").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        tag("wheel-day").performSemanticsAction(SemanticsActions.SetProgress) { it(29f) }
        tag("wheel-confirm").performClick()
        await(hasText("置顶") and hasClickAction()).performScrollTo().performClick()
        tag("countdown-preview").performScrollTo().assertTextContains("研讨会开幕").assertTextContains("置顶"); capture("editor")
        ui.activityRule.scenario.recreate(); attach()
        tag("countdown-name").assertTextContains("研讨会开幕"); assertEquals(before, list())
        tag("countdown-save").performClick(); tag("countdown-list")
        val added = list().items.first(); assertTrue(added.pinned); assertEquals(LocalDate.of(2028, 2, 29).toEpochDay(), added.targetDay)
        assertEquals(before.revision + 1, list().revision); capture("list")
        await(hasContentDescription("排序")).performClick()
        ui.tapAboveDock(tag("countdown-list"), hasTestTag("countdown-move:${reading.id}:1"))
        ui.waitUntil(10_000) { list().items.map { it.id } == listOf(added.id, exhibit.id, reading.id) }
        await(hasContentDescription("完成")).performClick()
        tag("countdown-list").performScrollToNode(hasTestTag("open-countdown:${added.id}")); tag("open-countdown:${added.id}").performClick()
        tag("countdown-detail:${added.id}").assertIsDisplayed(); capture("detail")
        tag("countdown-detail-pager").performTouchInput { swipeUp() }
        tag("countdown-detail:${exhibit.id}").assertIsDisplayed(); tag("countdown-edit").performClick()
        tag("countdown-name").assertTextContains(exhibit.title).performTextReplacement("展览新日期")
        await(hasText("置顶") and hasClickAction()).performScrollTo().performClick()
        val rev = list().revision
        tag("countdown-save").performClick(); tag("countdown-detail:${exhibit.id}").assertTextContains("展览新日期")
        assertEquals(rev + 1, list().revision); assertTrue(list().items.single { it.id == exhibit.id }.pinned)
        tag("countdown-edit").performClick()
        val delete = hasTestTag("countdown-delete")
        val form = ui.onNode(hasScrollAction() and hasAnyDescendant(delete))
        ui.tapAboveDock(form, delete)
        await(hasText("取消") and hasAnyAncestor(isDialog())).performClick(); assertEquals(3, list().items.size)
        ui.tapAboveDock(form, delete); tag("countdown-confirm-delete").performClick()
        await(hasText("倒数日已不存在。")); assertEquals(listOf(added.id, reading.id), list().items.map { it.id })
        assertEquals(queue, runBlocking { store.state().queue })
    }
}
