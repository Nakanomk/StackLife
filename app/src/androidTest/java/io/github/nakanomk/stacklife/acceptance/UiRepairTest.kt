package io.github.nakanomk.stacklife.acceptance

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class UiRepairTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)

    private fun show(narrow: Boolean = false) = ui.setContent {
        if (narrow) DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 680.dp)) then
            DeviceConfigurationOverride.FontScale(2f)) { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        else StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
    }

    private fun tag(tag: String): SemanticsNodeInteraction {
        ui.waitUntil(10_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
        return ui.onNodeWithTag(tag)
    }

    private fun checkComposer(narrow: Boolean) {
        show(narrow)
        val field = tag("assistant-input")
        fun box(name: String) = tag(name).fetchSemanticsNode().boundsInRoot
        fun shell() = box("assistant-composer")
        field.performClick()
        val empty = shell(); val id = field.fetchSemanticsNode().id
        field.performTextReplacement("文")
        assertEquals(empty.size, shell().size)
        assertEquals(id, field.fetchSemanticsNode().id)
        field.performTextClearance()
        assertEquals(empty.size, shell().size)
        field.performTextReplacement("一\n二")
        val two = shell()
        field.performTextReplacement("一\n二\n三\n四\n五")
        val five = shell()
        assertTrue(two.height > empty.height && five.height > two.height)
        val twenty = List(20) { "文" }.joinToString("\n")
        field.performTextReplacement(twenty)
        assertEquals(five.size, shell().size)
        assertEquals(twenty, field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        val actions = listOf("assistant-attach", "assistant-history", "assistant-send").map { name ->
            tag(name).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp); box(name)
        }
        assertTrue(actions.all { kotlin.math.abs(it.bottom - five.bottom) <= 1f })
        assertTrue(actions.zipWithNext().all { (a, b) -> a.right <= b.left })
        tag("assistant-history").performTouchInput { click() }
        tag("assistant-history-list").assertIsDisplayed()
    }

    @Test fun composerGrowsOnlyThroughFiveLinesAndKeepsTheHistoryHitTarget() = checkComposer(false)
    @Test fun narrowLargeFontComposerKeepsItsDraftAndIndependentActions() = checkComposer(true)

    @Test fun helpDoesNotOpenThePreferenceAndBackAndScrollDismissIt() {
        show()
        tag("assistant-input")
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.ui_settings)).performClick()
        val title = ui.activity.getString(R.string.reminder_channel)
        val explanation = ui.activity.getString(R.string.reminder_explanation)
        val icon = ui.onNodeWithContentDescription(ui.activity.getString(R.string.setting_info, title))
        icon.performTouchInput { click() }
        ui.onNodeWithText(explanation).assertIsDisplayed()
        ui.onNode(isDialog()).assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.onNodeWithText(explanation).assertDoesNotExist()
        icon.assertIsDisplayed().performTouchInput { click() }
        ui.onNodeWithText(explanation).assertIsDisplayed()
        ui.onNodeWithTag("routine-sleep").performTouchInput { swipeUp() }
        ui.onNodeWithText(explanation).assertDoesNotExist()
        icon.performScrollTo().performTouchInput { click() }
        ui.onNodeWithText(explanation).assertIsDisplayed()
        ui.onNodeWithTag("routine-wake").performTouchInput { click() }
        ui.onNodeWithText(explanation).assertDoesNotExist()
        tag("slot:time").assertIsDisplayed()
    }
}
