package io.github.nakanomk.stacklife.acceptance

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
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
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.RootPages
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.appearance.glassUnavailableReason
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.appearance.rememberPlannerBackdrop
import io.github.nakanomk.stacklife.planner.Appearance
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.WorkspaceViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
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
        val actions = listOf("assistant-history", "assistant-attach", "assistant-send").map { name ->
            tag(name).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp); box(name)
        }
        assertTrue(actions.all { kotlin.math.abs(it.bottom - five.bottom) <= 1f })
        val (history, attach, send) = actions
        val text = field.fetchSemanticsNode().boundsInRoot
        assertEquals(five.left, history.left, 1f)
        assertTrue(history.right <= text.left + 1f && text.right <= attach.left + 1f)
        assertEquals(attach.right, send.left, 1f)
        assertEquals(five.right, send.right, 1f)
        tag("assistant-history").performTouchInput { click() }
        tag("assistant-history-list").assertIsDisplayed()
    }

    @Test fun composerGrowsOnlyThroughFiveLinesAndKeepsTheHistoryHitTarget() = checkComposer(false)
    @Test fun narrowLargeFontComposerKeepsItsDraftAndIndependentActions() = checkComposer(true)

    @Test fun glassDockTracksLiveBottomPixelsWhenOnlyPageDrawingChanges() {
        val appearance = Appearance(glass = true)
        var bottom by mutableStateOf(Color.Red)
        var available = false
        ui.setContent {
            StackLifeTheme(appearance) {
                available = glassUnavailableReason(appearance) == null
                RootPages(appearance.visibleOrder, Destination.WORKSPACE, 0L, {}, AssistantPage.Terms) { _, _ ->
                    Spacer(Modifier.fillMaxSize().drawBehind {
                        drawRect(Color.Blue)
                        drawRect(bottom, Offset(0f, size.height * .7f), Size(size.width, size.height * .3f))
                    })
                }
            }
        }
        ui.runOnIdle { assumeTrue("Needs real glass", available) }
        val dock = tag("navigation-capsule")
        dock.assert(hasStateDescription(ui.activity.getString(R.string.ui_glass_capsule)))
        fun expectBottom(red: Boolean) {
            ui.waitUntil(5_000) {
                val pixels = dock.captureToImage().toPixelMap()
                var samples = 0
                var hits = 0
                for (y in pixels.height / 4 until pixels.height * 3 / 4 step 4)
                    for (x in pixels.width / 5 until pixels.width * 4 / 5 step 4) {
                        val c = pixels[x, y]
                        val channel = if (red) c.red - maxOf(c.green, c.blue) else c.green - maxOf(c.red, c.blue)
                        if (channel > .15f) hits++
                        samples++
                    }
                hits > samples / 2
            }
        }
        expectBottom(true)
        ui.runOnIdle { bottom = Color.Green }
        expectBottom(false)
    }

    @Test fun distantTabTapPaintsOnlyItsTwoPagesThenRestoresTheRealSwipeOrder() {
        val fills = mapOf(Destination.TODAY to Color.Red, Destination.WORKSPACE to Color.Green,
            Destination.GRAPHS to Color.Blue, Destination.COURSES to Color.Magenta, Destination.COUNTDOWN to Color.Yellow)
        val appearance = Appearance(glass = false)
        ui.setContent {
            var active by remember { mutableStateOf(Destination.TODAY) }
            StackLifeTheme(appearance) {
                RootPages(appearance.visibleOrder, active, 0L, { active = it }, AssistantPage.Terms) { page, _ ->
                    Spacer(Modifier.fillMaxSize().background(fills.getValue(page)))
                }
            }
        }
        tag("nav:today").assertIsSelected()
        val root = ui.onRoot()
        val scan = IntArray(root.captureToImage().width)
        fun trip(from: Destination, to: Destination) {
            val action = tag("nav:${to.key}").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            ui.runOnUiThread { action() }
            val src = fills.getValue(from).toArgb()
            val dst = fills.getValue(to).toArgb()
            var sharedFrame = false
            var finalTarget = 0
            repeat(25) { frame ->
                ui.mainClock.advanceTimeByFrame()
                val image = root.captureToImage()
                image.readPixels(scan, startY = image.height / 2, width = scan.size, height = 1)
                val old = scan.count { it == src }
                finalTarget = scan.count { it == dst }
                assertTrue("$from → $to frame $frame painted ${scan.size - old - finalTarget} foreign pixels",
                    old + finalTarget >= scan.size - 2)
                if (old > 5 && finalTarget > 5) sharedFrame = true
            }
            assertTrue("A direct tap should still slide from its real source, not cut", sharedFrame)
            assertTrue(finalTarget >= scan.size - 2)
            tag("nav:${to.key}").assertIsSelected()
        }
        ui.mainClock.autoAdvance = false
        try {
            trip(Destination.TODAY, Destination.COURSES)
            trip(Destination.COURSES, Destination.TODAY)
        } finally { ui.mainClock.autoAdvance = true }
        root.performTouchInput { swipeLeft() }
        tag("nav:workspace").assertIsSelected()
    }

    @Test fun rootTabsRetainOnlyTwoVisitedNeighborsAndNeverPlaceTheirHiddenContent() {
        val today = Destination.TODAY
        val workspace = Destination.WORKSPACE
        val courses = Destination.COURSES
        val countdown = Destination.COUNTDOWN
        val starts = mutableListOf<Destination>()
        val live = mutableSetOf<Destination>()
        val nodes = mutableMapOf<Destination, SemanticsNode>()
        var appearance by mutableStateOf(Appearance(glass = true))
        var capable = false
        ui.setContent {
            var active by remember { mutableStateOf(today) }
            StackLifeTheme(appearance) {
                capable = LocalMotionEnabled.current && glassUnavailableReason(appearance) == null
                RootPages(appearance.visibleOrder, active, 0L, { active = it }, AssistantPage.Terms) { page, _ ->
                    val material = rememberPlannerBackdrop()
                    DisposableEffect(page, material) {
                        starts.add(page)
                        live.add(page)
                        onDispose { live.remove(page) }
                    }
                    Spacer(Modifier.fillMaxSize().testTag("page:${page.key}"))
                }
            }
        }
        ui.runOnIdle { assumeTrue("Requires animated, hardware-accelerated roots", capable) }
        fun at(current: Destination, vararg hidden: Destination) {
            tag("nav:${current.key}").assertIsSelected()
            nodes[current] = tag("page:${current.key}").assertIsDisplayed().fetchSemanticsNode()
            ui.runOnIdle {
                assertEquals(setOf(current, *hidden), live)
                for (page in hidden) assertFalse("$page should stay composed but unplaced", nodes.getValue(page).layoutInfo.isPlaced)
            }
            for (page in hidden) ui.onNodeWithTag("page:${page.key}").assertDoesNotExist()
        }
        fun go(page: Destination, vararg hidden: Destination) {
            val (old, expected) = ui.runOnIdle { starts.size to if (page in live) emptyList() else listOf(page) }
            tag("nav:${page.key}").performClick()
            at(page, *hidden)
            ui.runOnIdle { assertEquals("Only a non-resident $page may start on this trip", expected, starts.drop(old)) }
        }
        at(today)
        go(courses, today) // Unvisited slots between these keys must never start, even transiently.
        go(workspace, courses, today)
        go(today, workspace, courses)
        go(countdown, today, workspace)
        go(courses, countdown, today)
        ui.runOnIdle {
            assertEquals(listOf(today, courses, workspace, countdown, courses), starts)
            appearance = appearance.copy(reduceMotion = true)
        }
        at(courses)
        go(workspace)
        ui.runOnIdle { appearance = appearance.copy(reduceMotion = false) }
        at(workspace)
        ui.runOnIdle { assertEquals(listOf(today, courses, workspace, countdown, courses, workspace), starts) }
    }

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
