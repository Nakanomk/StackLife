package io.github.nakanomk.stacklife.gallery

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.planner.Workspace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryTaskReturnTest {
    private val ui = createAndroidComposeRule<GalleryActivity>()
    private val environment = object : ExternalResource() {
        private val prefs get() = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("catalog", Context.MODE_PRIVATE)
        private var previous: String? = null
        override fun before() { previous = prefs.getString("preview", null); GalleryOptions().save(prefs) }
        override fun after() { prefs.edit().putString("preview", previous).commit() }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(environment).around(ui)

    @Test fun openingACourseShowsTheActualMeetingWithoutAModelConnection() {
        val app = ui.activity.application as GalleryApplication
        val first = runBlocking {
            app.ready.await()
            (app.planner.observe(PlannerQuery.Week("gallery-week", 1)).first() as io.github.nakanomk.stacklife.planner.CourseWeek).classes.first()
        }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("category:COMPONENT").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("category:COMPONENT").performClick()
        ui.onNodeWithTag("example:component-course-grid").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("grid-course:0").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("grid-course:0").performClick()
        ui.onNode(hasText(first.meeting.title) and hasAnyAncestor(hasTestTag("course-detail"))).assertIsDisplayed()
        ui.onNodeWithText("Asia/Shanghai").assertIsDisplayed()
    }

    @Test fun cancelledBackExpandsTheSameTaskAndLeavesTheGalleryWorkspaceUntouched() {
        val app = ui.activity.application as GalleryApplication
        val before = runBlocking { app.ready.await(); app.planner.observe(PlannerQuery.Workspace).first() as Workspace }
        val tag = "task:${before.queue.first().id}"
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("category:MOTION").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("category:MOTION").performClick()
        ui.onNodeWithTag("example:motion-task").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        val row = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag(tag).performClick()
        val detail = ui.onNodeWithTag("detail:$tag")
        val open = detail.fetchSemanticsNode().boundsInRoot
        val input = DirectNavigationEventInput()
        ui.runOnIdle { ui.activity.navigationEventDispatcher.addInput(input) }
        ui.mainClock.autoAdvance = false
        fun event(fraction: Float) = NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = fraction)
        try {
            ui.runOnUiThread { input.backStarted(event(0f)) }
            ui.mainClock.advanceTimeBy(64)
            ui.runOnUiThread { input.backProgressed(event(.55f)) }
            ui.mainClock.advanceTimeBy(64)
            val half = detail.fetchSemanticsNode().boundsInRoot
            assertTrue("Must seek toward the selected row inside the catalog viewport", half.height > row.height && half.height < open.height)
            ui.runOnUiThread { input.backProgressed(event(.2f)) }
            ui.mainClock.advanceTimeBy(64)
            assertTrue(detail.fetchSemanticsNode().boundsInRoot.height > half.height)
            ui.runOnUiThread { input.backCancelled() }
            ui.mainClock.advanceTimeBy(800)
            assertEquals(open, detail.fetchSemanticsNode().boundsInRoot)
        } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnUiThread { ui.activity.navigationEventDispatcher.removeInput(input) }
        }
        assertEquals(before, runBlocking { app.planner.observe(PlannerQuery.Workspace).first() })
    }
}
