package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    private fun attach() = ui.activityRule.scenario.onActivity { activity ->
        activity.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }, firstRunGuide = true) }
    }
    private fun prefs() = runBlocking { store.api.observe(PlannerQuery.Settings).first() as PolicyView }
    private fun tag(id: String): SemanticsNodeInteraction {
        try { ui.waitUntil(10_000) { ui.onAllNodesWithTag(id).fetchSemanticsNodes().isNotEmpty() } }
        catch (failure: ComposeTimeoutException) { throw AssertionError("$id\n${ui.onRoot().printToString()}", failure) }
        return ui.onNodeWithTag(id)
    }
    private fun back() = ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    @Test fun finishingPersistsRealSelectionsWithoutCreatingAStarterTaskOrRequestingAService() {
        attach(); tag("guide-feature:today").performClick(); tag("guide-next").performClick()
        tag("routine-wake").performClick(); tag("slot:time").assertExists(); back(); tag("guide:1")
        tag("guide-next").performClick(); tag("guide-add").performClick(); tag("title:0").assertIsDisplayed(); back(); tag("guide:2")
        tag("guide-import").performClick(); tag("importMethodFile").assertExists(); back(); tag("guide:2")
        assertEquals(0, prefs().onboardingVersion); assertEquals(Destination.entries.toSet(), prefs().appearance.visible)
        tag("guide-next").performClick(); tag("nav:workspace").assertIsSelected()
        ui.onNodeWithTag("nav:today").assertDoesNotExist()
        val saved = prefs(); assertEquals(1, saved.onboardingVersion); assertEquals(Destination.WORKSPACE, saved.appearance.start)
        assertTrue(runBlocking { store.state().queue.isEmpty() })
        ui.activityRule.scenario.recreate(); attach(); tag("nav:workspace"); ui.onNodeWithTag("guide:0").assertDoesNotExist()
        runBlocking { store.apply(PlannerCommand.EditAppearance(AppearanceFields(AppearanceGroup.LAYOUT,null))) }
        ui.activityRule.scenario.close()
        store.reopen(); assertEquals(1,prefs().onboardingVersion)
    }

    @Test fun laterDoesNotSaveUnsubmittedSelectionAndHelpCanOpenTheGuideAgain() {
        attach(); tag("guide-feature:today").performClick(); tag("guide-later").performClick()
        tag("nav:today").assertIsSelected(); assertEquals(Destination.entries.toSet(), prefs().appearance.visible)
        assertEquals(1, prefs().onboardingVersion)
        ui.onNodeWithContentDescription("设置").performClick()
        ui.tapAboveDock(ui.onNode(hasScrollToIndexAction()), hasTestTag("settings-about"))
        tag("about-help").performClick()
        tag("open-guidance").performClick(); tag("guide:0").assertExists()
        tag("guide-later").performClick(); tag("open-guidance").assertIsDisplayed()
        assertEquals(1, prefs().onboardingVersion); assertTrue(runBlocking { store.state().queue.isEmpty() })
    }
}
