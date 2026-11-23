package io.github.nakanomk.stacklife.about

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.planner.Appearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HelpAboutScreenTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun theBadgeBlinksOnceWithoutShrinkingItsMouthAndStaysOpenWithReducedMotion() {
        val motion = mutableStateOf(false)
        ui.mainClock.autoAdvance = false
        ui.setContent {
            StackLifeTheme(Appearance()) {
                key(motion.value) {
                    CompositionLocalProvider(LocalMotionEnabled provides motion.value) { AboutIdentity {} }
                }
            }
        }
        fun outline(): Pair<Int, Int> {
            val pixels = ui.onNodeWithTag("about-fumo").captureToImage().toPixelMap()
            val paper = pixels[pixels.width / 2, pixels.height / 5]
            fun height(x: Int, region: IntRange): Int {
                val rows = region.filter { y ->
                    val c = pixels[x, y]
                    kotlin.math.abs(c.red - paper.red) + kotlin.math.abs(c.green - paper.green) +
                        kotlin.math.abs(c.blue - paper.blue) > .35f
                }
                return rows.last() - rows.first() + 1
            }
            return height(pixels.width * 22 / 100, pixels.height / 5..pixels.height * 64 / 100) to
                height(pixels.width / 2, pixels.height * 60 / 100..pixels.height * 78 / 100)
        }
        val open = outline()
        ui.mainClock.advanceTimeBy(575)
        assertEquals("Reduced motion keeps the original open face", open, outline())
        ui.runOnIdle { motion.value = true }
        ui.mainClock.advanceTimeByFrame()
        ui.mainClock.advanceTimeBy(480)
        val shut = (1..5).map {
            ui.mainClock.advanceTimeBy(32)
            outline()
        }.minBy { it.first }
        assertTrue("Both eyelids should meet", shut.first < open.first / 2)
        assertEquals("The mouth must not squash along with the eyes", open.second, shut.second)
        ui.mainClock.advanceTimeBy(450)
        assertEquals(open, outline())
        ui.mainClock.advanceTimeBy(1_400)
        assertEquals("No idle loop", open, outline())
    }

    @Test fun showsInstalledVersionAndLetsTheUserReadTheEndOfABundledNotice() {
        val context = ui.activity
        val version = installedVersion(context)
        val file = "Apfloat-MIT.txt"
        val original = context.assets.open("licenses/$file").bufferedReader().use { it.readText() }
        val last = original.replace("\r\n", "\n").trimEnd().substringAfterLast("\n\n")
        assertTrue(last.isNotBlank())
        ui.setContent { StackLifeTheme(Appearance()) { HelpAboutScreen(back = { error("Unexpected exit") }, onGuide = { error("Unexpected guide") }) } }
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("about-version").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("about-version").assertTextEquals(context.getString(R.string.about_version, version.name, version.code))
        ui.onNodeWithTag("about-licenses").performScrollTo().performClick()
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("about-license-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("about-license-list").performScrollToNode(hasTestTag("license:$file"))
        ui.onNodeWithTag("license:$file").assertTextContains(file).performClick()
        val reader = ui.onNodeWithTag("about-license-reader")
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("about-license-reader").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(context.getString(R.string.about_copy_notice)).performClick()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        ui.waitUntil(8_000) {
            clipboard.primaryClipDescription?.label == file && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == original
        }
        val disclaimer = hasText(last, substring = true)
        reader.performScrollToNode(disclaimer)
        ui.onNode(disclaimer).assertIsDisplayed()
        ui.runOnIdle { context.onBackPressedDispatcher.onBackPressed() }
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("license:$file").fetchSemanticsNodes().isNotEmpty() }
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
                val list = if (tag == "about-build") "about-build-list" else "about-license-list"
                await(list)
                if (tag == "about-build") {
                    await("about-running-version")
                    val installed = installedVersion(ui.activity)
                    ui.onNodeWithTag("about-running-version").assertTextEquals(
                        ui.activity.getString(R.string.about_version, installed.name, installed.code))
                }
                ui.onNodeWithTag(list).performScrollToNode(hasTestTag(origin))
                ui.onNodeWithTag(origin).performScrollTo().performClick()
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
