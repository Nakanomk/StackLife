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
        ui.setContent { StackLifeTheme(Appearance()) { HelpAboutScreen(back = { error("Unexpected exit") }, onGuide = { error("Unexpected guide") }) } }
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

    @Test fun projectLicenseReturnsToItsActualLauncherAndEachAboutChildReturnsHome() {
        ui.setContent { StackLifeTheme(Appearance()) { HelpAboutScreen(back = { error("Unexpected exit") }, onGuide = { error("Unexpected guide") }) } }
        fun await(tag: String) {
            ui.waitUntil(8_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        }
        fun back() = ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        for (tag in listOf("about-help", "about-diagnostics", "about-build", "about-licenses")) {
            await(tag); ui.onNodeWithTag(tag).performScrollTo().performClick()
            if (tag == "about-build" || tag == "about-licenses") {
                val origin = if (tag == "about-build") "about-project-license" else "license:StackLife-GPL-3.0-or-later.txt"
                await(origin); ui.onNodeWithTag(origin).performScrollTo().performClick()
                ui.waitUntil(8_000) { ui.onAllNodes(hasText("GNU GENERAL PUBLIC LICENSE", substring = true)).fetchSemanticsNodes().isNotEmpty() }
                back(); await(origin); ui.onNodeWithTag(origin).assertIsDisplayed()
            } else {
                val title = ui.activity.getString(if (tag == "about-help") R.string.about_help_workspace else R.string.about_frames)
                ui.waitUntil(8_000) { ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            }
            back(); await(tag); ui.onNodeWithTag(tag).assertIsDisplayed()
        }
    }
}
