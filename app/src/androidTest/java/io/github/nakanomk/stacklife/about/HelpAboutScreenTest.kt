package io.github.nakanomk.stacklife.about

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.planner.Appearance
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HelpAboutScreenTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun showsInstalledVersionAndLetsTheUserReadTheEndOfABundledNotice() {
        val context = ui.activity
        val version = installedVersion(context)
        val file = "Apfloat-MIT.txt"
        val last = context.assets.open("licenses/$file").bufferedReader().use { it.readLines().joinToString("\n") }
            .trimEnd().substringAfterLast("\n\n")
        assertTrue(last.isNotBlank())
        ui.setContent { StackLifeTheme(Appearance()) { HelpAboutScreen {} } }
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("about-version").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("about-version").assertTextEquals(context.getString(R.string.about_version, version.name, version.code))
        ui.onNodeWithTag("about-licenses").performScrollTo().performClick()
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("license:$file").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("license:$file").performClick()
        ui.waitUntil(8_000) { ui.onAllNodesWithText("MIT License").fetchSemanticsNodes().isNotEmpty() }
        val disclaimer = hasText(last, substring = true)
        ui.onNode(hasScrollAction()).performScrollToNode(disclaimer)
        ui.onNode(disclaimer).assertIsDisplayed()
        ui.runOnIdle { context.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithTag("license:$file").assertIsDisplayed()
    }
}
