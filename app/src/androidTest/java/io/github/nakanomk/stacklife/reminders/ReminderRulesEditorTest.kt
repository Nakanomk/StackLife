package io.github.nakanomk.stacklife.reminders

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.acceptance.adjustWheel
import io.github.nakanomk.stacklife.planner.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderRulesEditorTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private fun click(id: Int) = ui.onNode(hasText(ui.activity.getString(id)) and hasClickAction()).performClick()

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
