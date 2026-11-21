package io.github.nakanomk.stacklife.acceptance

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.MainActivity
import io.github.nakanomk.stacklife.i18n.AppLocales
import io.github.nakanomk.stacklife.i18n.LanguageChoice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageSettingsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun visible(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(12_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
        return ui.onNode(matcher).assertIsDisplayed()
    }

    private fun choose(option: String) {
        visible(hasTestTag("language-setting")).performClick()
        visible(hasText(option) and hasAnyAncestor(isDialog())).performClick()
    }

    @Test fun switchLanguageKeepsSettingsAndAppliesToNavigationAfterRecreation() {
        val previous = AppLocales.choice(ui.activity)
        try {
            visible(hasContentDescription("设置") and hasClickAction()).performClick()
            choose("English")
            visible(hasTestTag("language-setting") and hasText("Language") and hasText("English"))
            ui.activityRule.scenario.recreate()
            visible(hasText("Model connection"))
            visible(hasContentDescription("Back") and hasClickAction()).performClick()
            visible(hasContentDescription("Settings") and hasClickAction())
            visible(hasTestTag("nav:graphs") and hasText("Graphs")).performClick()
            visible(hasText("New graph"))
            visible(hasContentDescription("Settings") and hasClickAction()).performClick()
            choose("中文")
            visible(hasTestTag("language-setting") and hasText("语言") and hasText("中文"))
            choose("跟随系统")
            ui.waitUntil(8_000) { AppLocales.choice(ui.activity) == LanguageChoice.SYSTEM }
            assertEquals(LanguageChoice.SYSTEM, AppLocales.choice(ui.activity))
        } finally {
            ui.activityRule.scenario.onActivity { AppLocales.set(it, previous) }
        }
    }
}
