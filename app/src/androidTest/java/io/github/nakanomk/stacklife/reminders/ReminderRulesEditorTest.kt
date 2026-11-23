package io.github.nakanomk.stacklife.reminders

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.acceptance.adjustWheel
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.acceptance.WorkspaceFixture
import io.github.nakanomk.stacklife.appearance.LocalPlatformAppearance
import io.github.nakanomk.stacklife.appearance.PlatformAppearance
import io.github.nakanomk.stacklife.planner.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderRulesEditorTest {
    private val fixture = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(ui)
    private fun click(id: Int) = ui.onNode(hasText(ui.activity.getString(id)) and hasClickAction()).performClick()

    @Test fun courseDefaultAnimatesTheMeasuredHeightInBothDirectionsUnlessMotionIsReduced() {
        var reduced by mutableStateOf(false)
        var systemAnimators by mutableStateOf(true)
        val original = runBlocking { fixture.state().revision }
        ui.setContent {
            StackLifeTheme {
                CompositionLocalProvider(LocalPlatformAppearance provides PlatformAppearance(animators = systemAnimators)) {
                    StackLifeTheme(Appearance(reduceMotion = reduced)) {
                        Column {
                            Box(Modifier.testTag("measured-editor")) {
                                ReminderRulesEditor(viewModel { WorkspaceViewModel(fixture.api) }, ReminderSubject.CourseDefault)
                            }
                        }
                    }
                }
            }
        }
        val title = hasText(ui.activity.getString(R.string.reminder_course_defaults)) and hasClickAction()
        ui.waitUntil(5_000) { ui.onAllNodes(title).fetchSemanticsNodes().size == 1 }
        fun bounds() = ui.onNodeWithTag("measured-editor").fetchSemanticsNode().boundsInRoot
        val closed = bounds()
        ui.mainClock.autoAdvance = false
        try {
            ui.onNode(title).performClick()
            ui.mainClock.advanceTimeBy(80)
            val opening = bounds()
            ui.mainClock.advanceTimeBy(2_000)
            val open = bounds()
            assertTrue("Opening jumped from $closed to $open", opening.height > closed.height && opening.height < open.height)
            assertEquals(closed.top, opening.top, 0f)
            ui.onNode(title).assertDoesNotExist()
            click(R.string.ui_cancel)
            ui.mainClock.advanceTimeBy(80)
            val closing = bounds()
            assertTrue("Closing jumped from $open to $closed", closing.height < open.height && closing.height > closed.height)
            assertEquals(closed.top, closing.top, 0f)
            ui.mainClock.advanceTimeBy(2_000)
            assertEquals(closed, bounds())

            // Either app or system reduction must settle on the first layout, without a residual spring.
            for (appReduction in listOf(true, false)) {
                ui.runOnIdle { reduced = appReduction; systemAnimators = appReduction }
                ui.mainClock.advanceTimeByFrame()
                ui.onNode(title).performClick()
                ui.mainClock.advanceTimeByFrame()
                assertEquals(open, bounds())
                click(R.string.ui_cancel)
                ui.mainClock.advanceTimeByFrame()
                assertEquals(closed, bounds())
            }
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(original, runBlocking { fixture.state().revision })
    }

    @Test fun savingADefaultKeepsOneDisabledEditorUntilTheRealCommitCompletes() {
        val reachedCommit = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val api = object : PlannerApi by fixture.api {
            override suspend fun commit(proposalId: String): CommitResult {
                reachedCommit.complete(Unit)
                releaseCommit.await()
                return fixture.api.commit(proposalId)
            }
        }
        fun view() = runBlocking { fixture.api.observe(PlannerQuery.Reminders(ReminderSubject.CourseDefault)).first() as ReminderView }
        val before = view()
        ui.setContent {
            StackLifeTheme {
                Box(Modifier.testTag("measured-editor")) {
                    ReminderRulesEditor(viewModel { WorkspaceViewModel(api) }, ReminderSubject.CourseDefault)
                }
            }
        }
        val title = hasText(ui.activity.getString(R.string.reminder_course_defaults)) and hasClickAction()
        ui.waitUntil(5_000) { ui.onAllNodes(title).fetchSemanticsNodes().size == 1 }
        ui.onNode(title).performClick()
        click(R.string.reminder_add)
        click(R.string.ui_ok)
        val save = hasText(ui.activity.getString(R.string.ui_save_changes)) and hasClickAction()
        ui.waitUntil(5_000) { ui.onAllNodes(save and isEnabled()).fetchSemanticsNodes().size == 1 }
        val expanded = ui.onNodeWithTag("measured-editor").fetchSemanticsNode().boundsInRoot
        assertEquals(before, view())
        try {
            ui.onNode(save).performClick()
            ui.waitUntil(5_000) { reachedCommit.isCompleted }
            ui.onNode(save).assertIsNotEnabled()
            ui.onNodeWithText(ui.activity.getString(R.string.reminder_add)).assertIsNotEnabled()
            assertEquals(expanded, ui.onNodeWithTag("measured-editor").fetchSemanticsNode().boundsInRoot)
            assertEquals(before, view())
        } finally { releaseCommit.complete(Unit) }
        ui.waitUntil(5_000) { ui.onAllNodes(title).fetchSemanticsNodes().size == 1 }
        val after = view()
        assertEquals(before.revision + 1, after.revision)
        assertEquals(before.policy.rules.size + 1, after.policy.rules.size)
    }

    @Test fun editCustomLeadWithWheelsThenRejectDuplicateAndDisableOnlyThisOccurrence() {
        var policy by mutableStateOf(ReminderPolicy(true, emptyList()))
        val target = ReminderSubject.Occurrence(OccurrenceKey.Schedule("one"))
        ui.setContent { StackLifeTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            ReminderRuleFields(target, policy) { policy = it }
        } } }
        click(R.string.reminder_inherit)
        click(R.string.reminder_add)
        click(R.string.reminder_when)
        click(R.string.reminder_custom)
        click(R.string.reminder_amount)
        ui.adjustWheel("wheel-number", 90)
        ui.onNodeWithTag("wheel-confirm").performClick()
        click(R.string.ui_ok)
        val rule = ui.runOnIdle { policy.rules.single() }
        assertEquals(ReminderWhen.BeforeStart(90 * 60_000L), rule.time)
        ui.onNodeWithText(ui.activity.getString(R.string.reminder_minutes_before, 90)).performClick()
        click(R.string.reminder_unit)
        ui.adjustWheel("wheel-number", 1)
        ui.onNodeWithTag("wheel-confirm").performClick()
        click(R.string.reminder_amount)
        ui.adjustWheel("wheel-number", 1)
        ui.onNodeWithTag("wheel-confirm").performClick()
        click(R.string.ui_ok)
        ui.runOnIdle { assertEquals(rule.copy(time = ReminderWhen.BeforeStart(3_600_000L)), policy.rules.single()) }
        click(R.string.reminder_add)
        click(R.string.reminder_when)
        ui.onNode(hasText(ui.activity.getString(R.string.reminder_hours_before, 1)) and isSelectable()).performClick()
        ui.onNodeWithText(ui.activity.getString(R.string.reminder_duplicate)).assertIsDisplayed()
        ui.onNodeWithText(ui.activity.getString(R.string.ui_ok)).assertIsNotEnabled()
        click(R.string.ui_cancel)
        ui.onNodeWithText(ui.activity.getString(R.string.reminder_hours_before, 1)).performClick()
        click(R.string.reminder_remove)
        ui.runOnIdle { assertEquals(ReminderPolicy(false, emptyList()), policy) }
        click(R.string.reminder_inherit)
        ui.runOnIdle { assertEquals(ReminderPolicy(true, emptyList()), policy) }
    }
}
