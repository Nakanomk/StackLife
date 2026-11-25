package io.github.nakanomk.stacklife.today

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.planner.Appearance
import io.github.nakanomk.stacklife.planner.NowLineStyle
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.transitions.LocalFrontEntry
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class CurrentTimeMarkerTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val day = PlannerQuery.Day(LocalDate.of(2026, 11, 1), ZoneId.of("America/New_York"))
    private val fixed = day.date.atTime(1, 30).atZone(day.zone).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
    private val clock = mutableLongStateOf(fixed)
    private val settings = mutableStateOf(Appearance(nowLine = NowLineStyle.OFF, nowTrail = false))
    private val front = mutableStateOf(true)
    private val motion = mutableStateOf(true)
    private lateinit var entry: LifecycleRegistry
    private lateinit var scroll: ScrollState
    private var anchor = 0
    private var axisY = 0
    private fun px(dp: Int) = with(ui.density) { dp.dp.roundToPx() }

    private fun show(rtl: Boolean = false) {
        ui.mainClock.autoAdvance = false
        ui.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                val density = LocalDensity.current
                val owner = remember { object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).also { entry = it }
                }.also { it.lifecycle.currentState = Lifecycle.State.RESUMED } }
                val scale = remember(day, density) { DayAxisScale(day,
                    TimeGridPlacement(emptyList(), day.startMillis, day.endMillis), with(density) { 2.dp.toPx() }, 1, 1, 1) }
                axisY = scale.yAt(fixed)
                anchor = with(density) { 108.dp.roundToPx() }
                scroll = rememberScrollState(axisY - anchor)
                val drag = remember(scale, scroll) { AxisDrag(scale, scroll) }
                val background = if (rtl) Color.Black else Color.White
                val colors = if (rtl) darkColorScheme(primary = Color.Magenta, surface = background) else
                    lightColorScheme(primary = Color.Magenta, surface = background)
                CompositionLocalProvider(LocalLayoutDirection provides (if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr),
                    LocalAppearance provides settings.value, LocalMotionEnabled provides motion.value,
                    LocalFrontEntry provides front.value, LocalLifecycleOwner provides owner) {
                    MaterialTheme(colorScheme = colors) {
                        Box(Modifier.size(272.dp, 220.dp).clipToBounds().background(background).testTag("viewport")
                            .onGloballyPositioned(drag::placeViewport)) {
                            Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                                Spacer(Modifier.width(1.dp).height(with(density) { scale.height.toDp() }))
                            }
                            CurrentTimeMarker(drag, 96.dp, clock.longValue)
                        }
                    }
                }
            }
        }
        frames()
    }

    private fun frames() { repeat(3) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() } }
    private fun image() = ui.onNodeWithTag("viewport").captureToImage().toPixelMap()
    private fun ink(image: PixelMap, x: Int, y: Int): Boolean = image[x, y].let {
        it.red > .8f && it.blue > .8f && it.green < .25f
    }
    private fun slice(image: PixelMap, x: Int, y: Int, width: Int, height: Int) =
        IntArray(width * height) { image[x + it % width, y + it / width].toArgb() }
    private fun arrow(image: PixelMap, x: Int) = slice(image, x - px(10), anchor - px(18), px(20), px(19))
    private fun edges(image: PixelMap, x: Int): IntRange {
        val rows = (anchor - px(47) until anchor - px(20)).filter { ink(image, x, it) }
        assertTrue("Expected ink on vertical trail in actual draw", rows.isNotEmpty())
        return rows.first()..rows.last()
    }
    private fun assertDown(image: PixelMap, x: Int) {
        fun width(y: Int) = (x - px(10) .. x + px(10)).count { ink(image, it, y) }
        assertTrue(ink(image, x, anchor - px(2)))
        assertTrue("Arrow shoulders must be wider above its bottom tip", width(anchor - px(8)) > width(anchor - px(1)))
        assertFalse(ink(image, x, anchor + px(1)))
    }
    private fun movingTail(rtl: Boolean) {
        show(rtl)
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.today_now_at, "01:30 -05:00")).assertIsDisplayed()
        val base = image()
        val x = if (rtl) base.width - px(108) else px(108)
        assertDown(base, x)
        val before = arrow(base, x)
        val bounds = ui.onNodeWithTag("now-line").fetchSemanticsNode().boundsInRoot
        ui.runOnIdle { settings.value = settings.value.copy(nowTrail = true) }; frames()
        ui.mainClock.advanceTimeBy(624)
        val early = image()
        ui.mainClock.advanceTimeBy(304)
        val late = image()
        val a = edges(early, x); val b = edges(late, x)
        assertTrue("Both painted edges must travel downward: $a then $b", b.first > a.first && b.last > a.last)
        assertArrayEquals(before, arrow(early, x)); assertArrayEquals(before, arrow(late, x))
        assertEquals(bounds, ui.onNodeWithTag("now-line").fetchSemanticsNode().boundsInRoot)
        ui.runOnIdle { settings.value = settings.value.copy(nowTrail = false) }; frames()
        fun all(image: PixelMap) = slice(image, 0, 0, image.width, image.height)
        assertArrayEquals(all(base), all(image()))
        ui.mainClock.advanceTimeBy(432); assertArrayEquals(all(base), all(image()))
    }

    @Test fun bothTrailEdgesMoveButTimeAndArrowStayFixed() = movingTail(false)
    @Test fun darkRtlAtDoubleFontKeepsDownwardTipAndStationaryTime() = movingTail(true)

    @Test fun waveFreezesBehindAnotherEntryWhenPausedOrWithReducedMotionAndRestartsInFront() {
        show()
        ui.runOnIdle { settings.value = settings.value.copy(nowLine = NowLineStyle.WAVE, nowArrow = false) }; frames()
        fun wave(): IntArray = slice(image(), px(124), anchor - px(8), px(100), px(16))
        fun moves(expected: Boolean) {
            val a = wave(); ui.mainClock.advanceTimeBy(320)
            assertEquals("Wave movement", expected, !a.contentEquals(wave()))
        }
        moves(true)
        ui.runOnIdle { front.value = false }; frames(); moves(false)
        ui.runOnIdle { front.value = true; entry.currentState = Lifecycle.State.STARTED }; frames(); moves(false)
        ui.runOnIdle { entry.currentState = Lifecycle.State.RESUMED; motion.value = false }; frames(); moves(false)
        ui.runOnIdle { motion.value = true }; frames(); moves(true)
        ui.runOnIdle { settings.value = settings.value.copy(nowLine = NowLineStyle.STRAIGHT) }; frames(); moves(false)
    }

    @Test fun timeSurvivesAllDecorationsOffAndClipsAtBothEdgesOfDstDayViewport() {
        show()
        val description = ui.activity.getString(R.string.today_now_at, "01:30 -05:00")
        ui.runOnIdle { settings.value = settings.value.copy(nowArrow = false) }; frames()
        val label = ui.onNodeWithContentDescription(description)
        label.assertIsDisplayed()
        val empty = image()
        assertFalse((anchor - px(17) .. anchor).any { ink(empty, px(108), it) })
        ui.runOnIdle { runBlocking { scroll.scrollTo(0) } }; frames(); label.assertDoesNotExist()
        ui.runOnIdle { runBlocking { scroll.scrollTo(axisY + px(2)) } }; frames(); label.assertDoesNotExist()
        ui.runOnIdle { runBlocking { scroll.scrollTo(axisY - anchor) } }; frames(); label.assertIsDisplayed()
        ui.runOnIdle { clock.longValue = day.endMillis }; frames(); ui.onNodeWithTag("now-line").assertDoesNotExist()
        ui.runOnIdle { clock.longValue = fixed }; frames(); label.assertIsDisplayed()
        ui.runOnIdle { settings.value = settings.value.copy(nowTrail = true); motion.value = false }; frames()
        assertTrue(edges(image(), px(108)).last > anchor - px(24)) // trail without the arrow
    }
}
