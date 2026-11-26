package io.github.nakanomk.stacklife.reminders

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
                        // Keep the tiny calendar off the transparent window corner and the system bars.
                        Column(Modifier.safeDrawingPadding().padding(16.dp).background(MaterialTheme.colorScheme.surface)) {
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
        val ink = ui.onNodeWithText(ui.activity.getString(R.string.reminder_course_defaults), useUnmergedTree = true)
        val identity = ink.fetchSemanticsNode().id
        val left = ink.fetchSemanticsNode().boundsInRoot.left
        val lead = ui.onNodeWithTag("expandable-preference-leading", useUnmergedTree = true)
        val iconBounds = lead.fetchSemanticsNode().boundsInRoot
        val closed = bounds()
        ui.mainClock.autoAdvance = false
        try {
            ui.onNode(title).performClick()
            ui.mainClock.advanceTimeBy(80)
            val opening = bounds()
            val moving = ink.fetchSemanticsNode().boundsInRoot.left
            ui.mainClock.advanceTimeBy(2_000)
            val open = bounds()
            assertTrue("Opening jumped from $closed to $open", opening.height > closed.height && opening.height < open.height)
            assertEquals(closed.top, opening.top, 0f)
            val start = ink.fetchSemanticsNode().boundsInRoot.left
            assertEquals(iconBounds, lead.fetchSemanticsNode().boundsInRoot)
            val rule = ui.onNodeWithText(ui.activity.getString(R.string.reminder_minutes_before, 15), useUnmergedTree = true)
            assertEquals(iconBounds.left, start, 1f)
            assertEquals(start, rule.fetchSemanticsNode().boundsInRoot.left, 1f)
            assertTrue("Title did not move to the start padding: $left, $moving, $start", moving < left && moving > start)
            assertEquals(identity, ink.fetchSemanticsNode().id)
            click(R.string.ui_cancel)
            ui.mainClock.advanceTimeBy(80)
            val closing = bounds()
            val returning = ink.fetchSemanticsNode().boundsInRoot.left
            assertTrue(returning < left && returning > start)
            // Retarget before settling. The same title and same edit session UI remain usable.
            ui.onNode(title).performClick()
            ui.mainClock.advanceTimeBy(2_000)
            assertEquals(start, ink.fetchSemanticsNode().boundsInRoot.left, 1f)
            assertEquals(identity, ink.fetchSemanticsNode().id)
            ui.onNode(title).performClick()
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
                assertEquals(start, ink.fetchSemanticsNode().boundsInRoot.left, 1f)
                click(R.string.ui_cancel)
                ui.mainClock.advanceTimeByFrame()
                assertEquals(closed, bounds())
                assertEquals(left, ink.fetchSemanticsNode().boundsInRoot.left, 1f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        assertEquals(original, runBlocking { fixture.state().revision })
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun doubleSizeRtlTitleWrapsWithoutEllipsisAndAlignsWithExpandedRules() {
        ui.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    StackLifeTheme(Appearance(reduceMotion = true)) {
                        Column(Modifier.width(184.dp)) {
                            ReminderRulesEditor(viewModel { WorkspaceViewModel(fixture.api) }, ReminderSubject.CourseDefault)
                        }
                    }
                }
            }
        }
        val text = ui.activity.getString(R.string.reminder_course_defaults)
        val header = hasText(text) and hasClickAction()
        ui.waitUntil(5_000) { ui.onAllNodes(header).fetchSemanticsNodes().size == 1 }
        val title = ui.onNodeWithText(text, useUnmergedTree = true)
        fun titleBounds() = title.fetchSemanticsNode().boundsInRoot
        fun assertFullTitle(): Int {
            val results = mutableListOf<TextLayoutResult>()
            title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
            assertFalse(layout.isLineEllipsized(layout.lineCount - 1))
            val bounds = titleBounds()
            val slot = ui.onNode(header).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= slot.left && bounds.right <= slot.right && bounds.bottom <= slot.bottom)
            return layout.lineCount
        }
        val closedLines = assertFullTitle()
        assertTrue(closedLines > 1)
        val closed = titleBounds()
        ui.onNode(header).performClick()
        assertTrue(assertFullTitle() <= closedLines)
        val rule = ui.onNodeWithText(ui.activity.getString(R.string.reminder_minutes_before, 15), useUnmergedTree = true)
        assertEquals(rule.fetchSemanticsNode().boundsInRoot.right, titleBounds().right, 1f)
        assertTrue(titleBounds().right > closed.right)
        click(R.string.ui_cancel)
        assertEquals(closed, titleBounds())
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
        ui.waitUntil(5_000) { ui.onAllNodes(save).fetchSemanticsNodes().isEmpty() }
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
