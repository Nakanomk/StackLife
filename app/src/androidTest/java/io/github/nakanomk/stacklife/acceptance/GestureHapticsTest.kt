package io.github.nakanomk.stacklife.acceptance

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.LocalPlatformAppearance
import io.github.nakanomk.stacklife.appearance.PlatformAppearance
import io.github.nakanomk.stacklife.appearance.scrollBoundaryHaptics
import io.github.nakanomk.stacklife.courses.BellSchedule
import io.github.nakanomk.stacklife.courses.CourseSelection
import io.github.nakanomk.stacklife.courses.CoursesRoute
import io.github.nakanomk.stacklife.courses.TermSpec
import io.github.nakanomk.stacklife.courses.meeting
import io.github.nakanomk.stacklife.planner.Appearance
import io.github.nakanomk.stacklife.planner.HapticLevel
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.planner.PolicyView
import io.github.nakanomk.stacklife.rememberDestinationCapsule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GestureHapticsTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    private val effects = mutableListOf<HapticFeedbackType>()
    private val delegate = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { effects.add(hapticFeedbackType) }
    }
    private val light = HapticFeedbackType.SegmentFrequentTick
    private val navigation = HapticFeedbackType.SegmentTick
    private val boundary = HapticFeedbackType.GestureThresholdActivate
    private val systemEnabled = mutableStateOf(true)

    // Exercise the real nested StackLifeTheme gate without changing the device's system setting.
    private fun record(content: @Composable () -> Unit) = ui.setContent {
        CompositionLocalProvider(LocalHapticFeedback provides delegate) {
            StackLifeTheme {
                CompositionLocalProvider(LocalPlatformAppearance provides PlatformAppearance(haptics = systemEnabled.value), content = content)
            }
        }
    }

    private fun pulses(first: HapticFeedbackType? = null, second: HapticFeedbackType? = null) = ui.runOnIdle {
        assertEquals(listOfNotNull(first, second), effects.toList()); effects.clear()
    }

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(10_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
        return ui.onNode(matcher)
    }
    private fun tag(id: String) = node(hasTestTag(id))

    @Test fun rootDockWorkspaceAndDayOnlyPulseForNewAcceptedSelections() {
        record { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        tag("nav:today").performClick()
        pulses()
        node(hasContentDescription(ui.activity.getString(R.string.today_next_day))).performClick()
        pulses(light)
        val dock = tag("navigation-capsule")
        val left = dock.fetchSemanticsNode().boundsInRoot.topLeft
        val today = tag("nav:today").fetchSemanticsNode().boundsInRoot.center - left
        val workspace = tag("nav:workspace").fetchSemanticsNode().boundsInRoot.center - left
        dock.performTouchInput { down(today); moveTo(workspace, 160); cancel() }
        tag("nav:today").assertIsSelected(); pulses()
        dock.performTouchInput { down(today); moveTo(workspace, 160); up() }
        tag("nav:workspace").assertIsSelected(); pulses(light)
        val queue = node(hasText(ui.activity.getString(R.string.ui_queue)) and hasClickAction())
        val stack = node(hasText(ui.activity.getString(R.string.ui_stack)) and hasClickAction())
        queue.performClick(); pulses()
        val delta = stack.fetchSemanticsNode().boundsInRoot.center - queue.fetchSemanticsNode().boundsInRoot.center
        queue.performTouchInput { down(center); moveBy(delta, 160); up() }
        stack.assertIsSelected(); pulses(light)
        stack.performClick(); pulses()
        tag("frames").performTouchInput { swipeLeft() }
        tag("nav:graphs").assertIsSelected(); pulses(light)
        // A direct dock tap invokes the root pager's programmatic trip, not a second swipe.
        tag("nav:today").performClick(); tag("nav:today").assertIsSelected(); pulses(light)
        val click = androidx.compose.ui.semantics.SemanticsActions.OnClick
        val selectWorkspace = tag("nav:workspace").fetchSemanticsNode().config[click].action!!
        val selectToday = tag("nav:today").fetchSemanticsNode().config[click].action!!
        ui.runOnUiThread { selectWorkspace(); selectWorkspace(); selectToday() }
        tag("nav:today").assertIsSelected(); pulses(light, light)
    }

    @Test fun weekArrowAndSwipePulseOnceButExternalAlignmentAndCancelledDragAreSilent() {
        val term = TermSpec("Haptic week", LocalDate.of(2028, 2, 28), 5, BellSchedule.WINTER)
        runBlocking {
            val id = store.api.stageCourseImport(term, (1..5).map { term.meeting(it, 1, "Class $it", "", 1, 2) })
            try { store.apply(PlannerCommand.CourseImport(id)) } finally { store.api.discardCourseImport(id) }
        }
        val owner = mutableStateOf(CourseSelection(term.id, 1))
        record { StackLifeTheme(Appearance(navigationHaptics = HapticLevel.STANDARD, glass = false)) {
            CoursesRoute(viewModel { WorkspaceViewModel(store.api) }, owner.value, { owner.value = it }, {}, { _, _ -> }, {}, {})
        } }
        tag("course-next").performClick(); tag("week-grid"); pulses(navigation)
        ui.runOnIdle { owner.value = CourseSelection(term.id, 3) }
        tag("course-week").assertTextContains(ui.activity.getString(R.string.courses_week, 3)); pulses()
        val grid = tag("week-grid")
        grid.performTouchInput { down(center); moveBy(Offset(-width * .22f, 0f), 160); cancel() }
        ui.runOnIdle { assertEquals(3, owner.value.week) }; pulses()
        grid.performTouchInput { swipeLeft() }
        ui.waitUntil(5_000) { owner.value.week == 4 }; pulses(navigation)
    }

    @Test fun navigationUsesTheSelectedSystemEffectAndHonorsBothSwitches() {
        var appearance by mutableStateOf(Appearance(glass = false))
        var active by mutableStateOf("today")
        record { StackLifeTheme(appearance) { rememberDestinationCapsule(active) { active = it }.invoke() } }
        tag("nav:workspace").performClick(); pulses(light)
        ui.runOnIdle { appearance = appearance.copy(navigationHaptics = HapticLevel.STANDARD) }
        tag("nav:today").performClick(); pulses(navigation)
        ui.runOnIdle { appearance = appearance.copy(haptics = false) }
        tag("nav:workspace").performClick(); pulses()
        ui.runOnIdle { appearance = appearance.copy(haptics = true, navigationHaptics = HapticLevel.OFF) }
        tag("nav:today").performClick(); pulses()
        ui.runOnIdle { appearance = appearance.copy(navigationHaptics = HapticLevel.STANDARD); systemEnabled.value = false }
        tag("nav:workspace").performClick(); tag("nav:workspace").assertIsSelected(); pulses()
    }

    @Test fun materialEditorKeepsSceneChoicesWhenMasterIsDisabled() {
        record { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
        fun text(resource: Int) = hasText(ui.activity.getString(resource))
        val material = text(R.string.ui_material_motion) and hasClickAction()
        node(hasContentDescription(ui.activity.getString(R.string.ui_settings)) and hasClickAction()).performClick()
        node(material).performClick()
        fun field(id: Int): SemanticsNodeInteraction {
            val match = text(id) and hasClickAction()
            tag("appearance-scroll").performScrollToNode(match)
            return node(match).performScrollTo()
        }
        fun choose(scene: Int, option: Int) {
            field(scene).performClick()
            node(text(option) and SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton)).performClick()
        }
        choose(R.string.haptic_navigation, R.string.ui_standard)
        choose(R.string.haptic_edges, R.string.haptic_off)
        field(R.string.ui_system_haptics).performClick()
        node(text(R.string.ui_save_appearance) and hasClickAction()).performClick()
        node(material)
        val saved = runBlocking { (store.api.observe(PlannerQuery.Settings).first() as PolicyView).appearance }
        assertEquals(false, saved.haptics)
        assertEquals(HapticLevel.STANDARD, saved.navigationHaptics)
        assertEquals(HapticLevel.OFF, saved.edgeHaptics)
    }

    @Test fun scrollBoundariesLatchAcrossDragsAndIgnoreProgrammaticMovementAndIdleAncestors() {
        lateinit var inner: ScrollState
        var level by mutableStateOf(HapticLevel.STANDARD)
        record { StackLifeTheme(Appearance(edgeHaptics = level)) {
            Column {
                val outer = rememberScrollState()
                Column(Modifier.size(200.dp, 180.dp).scrollBoundaryHaptics(outer).verticalScroll(outer)) {
                    inner = rememberScrollState()
                    Column(Modifier.fillMaxWidth().height(160.dp).testTag("inner").scrollBoundaryHaptics(inner).verticalScroll(inner)) {
                        Spacer(Modifier.height(480.dp))
                    }
                    Spacer(Modifier.height(30.dp))
                }
                val short = rememberScrollState()
                Column(Modifier.size(200.dp, 80.dp).testTag("short").scrollBoundaryHaptics(short).verticalScroll(short)) {
                    Spacer(Modifier.height(12.dp))
                }
            }
        } }
        fun drag(target: String, fraction: Float) = tag(target).performTouchInput {
            down(center); moveBy(Offset(0f, height * fraction), 160); advanceEventTime(250); up()
        }
        drag("short", -.45f); pulses()
        ui.runOnIdle { runBlocking { inner.scrollTo(inner.maxValue - 4) } }; pulses()
        drag("inner", -.45f); pulses(boundary)
        drag("inner", -.45f); pulses()
        drag("inner", .4f); pulses()
        drag("inner", -.7f); pulses(boundary)
        ui.runOnIdle { level = HapticLevel.LIGHT; runBlocking { inner.scrollTo(4) } }; pulses()
        drag("inner", .45f); pulses(light)
        ui.runOnIdle { level = HapticLevel.OFF; runBlocking { inner.scrollTo(inner.maxValue - 4) } }
        drag("inner", -.45f); pulses()
    }
}
