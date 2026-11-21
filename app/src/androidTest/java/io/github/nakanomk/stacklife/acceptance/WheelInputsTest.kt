package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.courses.TermFields
import io.github.nakanomk.stacklife.courses.TermForm
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.today.SlotEditor
import io.github.nakanomk.stacklife.today.SlotEditorKey
import java.time.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WheelInputsTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)

    @Test fun physicalWheelClampsDayInLeapFebruaryAndOptionalDateCanBeCancelledOrCleared() {
        val original = LocalDate.of(2024, 1, 31)
        ui.setContent { StackLifeTheme {
            var form by remember { mutableStateOf(TermForm(id = "Term", monday = LocalDate.of(2024, 1, 29), weeks = 6, switchDate = original)) }
            Column(Modifier.verticalScroll(rememberScrollState())) { TermFields(form, true) { form = it } }
        } }
        val field = ui.onNodeWithTag("termSwitchDate")
        field.performScrollTo().performClick()
        // Tap the next visible item of the actual Android NumberPicker rather than setting its value.
        ui.onNodeWithTag("wheel-month").performTouchInput { click(Offset(centerX, height * .84f)) }
        ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("wheel-day") and hasStateDescription("29")).fetchSemanticsNodes().isNotEmpty() }
        ui.adjustWheel("wheel-year", 2025)
        ui.onNodeWithTag("wheel-day").assert(hasStateDescription("28"))
        ui.onNodeWithTag("wheel-cancel").performClick()
        field.assertTextContains(original.toString())
        ui.chooseDate("termSwitchDate", LocalDate.of(2024, 2, 29))
        field.assertTextContains("2024-02-29").performClick()
        ui.onNodeWithTag("wheel-clear").performClick()
        field.assertTextContains(ui.activity.getString(R.string.wheel_unset))
        ui.onNodeWithTag("termMonday").performScrollTo().assertTextContains("2024-01-29").performClick()
        ui.onNodeWithTag("wheel-clear").assertDoesNotExist()
    }

    @Test fun skippedWallClockIsRejectedAndRepeatedHourCommitsChosenOffset() = runBlocking {
        store.apply(PlannerCommand.TaskAdd(TaskInput("DST")))
        val state = store.state()
        val task = state.queue.single()
        val zone = ZoneId.of("America/New_York")
        val spring = LocalDate.of(2026, 3, 8)
        val fall = LocalDate.of(2026, 11, 1)
        val entry = SlotEditorKey(task.id, task.title, spring.toEpochDay(), zone.id, null, 30, state.revision)
        val receipt = CompletableDeferred<Instant?>()
        ui.setContent { StackLifeTheme {
            val model: WorkspaceViewModel = viewModel { WorkspaceViewModel(store.api) }
            SlotEditor(entry, model, model::checkSlot, {}, { receipt.complete(it) })
        } }
        ui.chooseClock("slot:time", 2, 30)
        ui.onNodeWithText(ui.activity.getString(R.string.today_skipped_time)).performScrollTo().assertIsDisplayed()
        val save = hasText(ui.activity.getString(R.string.today_save_slot)) and hasClickAction()
        ui.onNode(save).assertIsNotEnabled()
        ui.chooseDate("slot:date", fall)
        ui.chooseClock("slot:time", 1, 30)
        ui.onNodeWithText("UTC-05:00").performScrollTo().performClick().assertIsSelected()
        assertEquals(state.revision, store.state().revision)
        ui.waitUntil(5_000) { ui.onAllNodes(save and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(save).performScrollTo().performClick()
        val expected = fall.atTime(1, 30).atOffset(ZoneOffset.ofHours(-5)).toInstant()
        ui.waitUntil(5_000) { receipt.isCompleted }
        assertEquals(expected, receipt.await())
        val day = store.api.observe(PlannerQuery.Day(fall, zone)).first() as DayPlan
        assertEquals(task.id, day.blocks.single().taskId)
        assertEquals(expected.toEpochMilli(), day.blocks.single().startMillis)
    }
}
