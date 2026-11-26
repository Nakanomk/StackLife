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
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.planner.Appearance
import io.github.nakanomk.stacklife.planner.PlannerQuery
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
    private val settings = mutableStateOf(Appearance(showNowLine = false, nowArrow = true))
    private lateinit var scroll: ScrollState
    private var anchor = 0
    private var axisY = 0
    private fun px(dp: Int) = with(ui.density) { dp.dp.roundToPx() }

    private fun show(rtl: Boolean = false, rail: Boolean = false) {
        ui.mainClock.autoAdvance = false
        ui.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                val density = LocalDensity.current
                val scale = remember(day, density) { DayAxisScale(day,
                    TimeGridPlacement(emptyList(), day.startMillis, day.endMillis), with(density) { 2.dp.toPx() }, 1, 1, 1) }
                axisY = scale.yAt(fixed)
                anchor = with(density) { 108.dp.roundToPx() }
                scroll = rememberScrollState(axisY - anchor)
                val drag = remember(scale, scroll) { AxisDrag(scale, scroll) }
                val background = if (rtl) Color.Black else Color.White
                val surface = if (rtl) Color.DarkGray else Color.LightGray // Distinguish a painted badge from untouched paper.
                val colors = if (rtl) darkColorScheme(primary = Color.Magenta, surface = surface) else
                    lightColorScheme(primary = Color.Magenta, surface = surface)
                CompositionLocalProvider(LocalLayoutDirection provides (if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr),
                    LocalAppearance provides settings.value) {
                    MaterialTheme(colorScheme = colors) {
                        Box(Modifier.size(272.dp, 220.dp).clipToBounds().background(background).testTag("viewport")
                            .onGloballyPositioned(drag::placeViewport)) {
                            val y = scale.cursorY(clock.longValue)
                            if (rail) TimeAxisRail(drag, 96.dp, y, clock.longValue >= day.endMillis)
                            Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                                Spacer(Modifier.width(1.dp).height(with(density) { scale.height.toDp() }))
                            }
                            CurrentTimeMarker(drag, 96.dp, clock.longValue, y)
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
    private fun assertCentredTriangle(image: PixelMap, x: Int, timeY: Int = anchor) {
        val radius = px(12)
        var count = 0
        var xSum = 0.0
        var ySum = 0.0
        for (y in timeY - radius until timeY + radius) for (col in x - radius until x + radius) {
            if (ink(image, col, y)) { count++; xSum += col + 0.5; ySum += y + 0.5 }
        }
        assertTrue("Expected a broad, filled triangle", count > px(10) * px(10))
        assertEquals("Centred on the spine", x.toDouble(), xSum / count, 1.5)
        assertEquals("Area centroid marks the instant", timeY.toDouble(), ySum / count, 1.5)
        fun width(y: Int): Int {
            val pixels = (x - radius until x + radius).filter { ink(image, it, y) }
            assertFalse("No holes or split tip", pixels.isEmpty())
            assertEquals(pixels.last() - pixels.first() + 1, pixels.size)
            return pixels.size
        }
        assertTrue(width(timeY - px(4)) > width(timeY + px(6)))
        assertFalse(ink(image, x, timeY - px(9)))
        assertFalse(ink(image, x, timeY + px(12)))
    }

    @Test fun theCentroidFollowsTimeAndScrollWithoutLeavingTheRail() {
        show()
        val x = px(96)
        assertCentredTriangle(image(), x)
        ui.runOnIdle { clock.longValue = fixed + 10 * 60_000L }; frames()
        ui.onNodeWithContentDescription(ui.activity.getString(R.string.today_now_at, "01:40 -05:00")).assertIsDisplayed()
        val moved = image()
        assertCentredTriangle(moved, x, anchor + px(20)) // Ten minutes at 2dp/minute.
        assertFalse(ink(moved, x, anchor))
        ui.runOnIdle { runBlocking { scroll.scrollTo(scroll.value + px(20)) } }; frames()
        assertCentredTriangle(image(), x)
    }

    @Test fun elapsedRailTracksTheArrowInsideAndOutsideTheScrolledViewport() {
        show(rail = true)
        val x = px(96)
        fun samples() = image().let { ink(it, x, px(24)) to ink(it, x, it.height - px(24)) }
        assertEquals(true to false, samples())
        ui.runOnIdle { runBlocking { scroll.scrollTo(0) } }; frames()
        assertEquals(true to true, samples())
        ui.runOnIdle { runBlocking { scroll.scrollTo(axisY + px(40)) } }; frames()
        assertEquals(false to false, samples())
        ui.runOnIdle { runBlocking { scroll.scrollTo(axisY - anchor) }; settings.value = settings.value.copy(nowArrow = false) }; frames()
        assertEquals(false to false, samples())
        ui.runOnIdle { clock.longValue = day.endMillis }; frames()
        ui.onNodeWithTag("now-line").assertDoesNotExist()
        assertEquals(true to true, samples())
        ui.runOnIdle { settings.value = settings.value.copy(nowArrow = true); clock.longValue = day.startMillis - 1 }; frames()
        assertEquals(false to false, samples())
    }

    @Test fun darkRtlStraightLineAndArrowShareAnInstantAtDoubleFontSize() {
        show(rtl = true)
        val base = image()
        val x = base.width - px(96)
        assertCentredTriangle(base, x)
        ui.runOnIdle { settings.value = settings.value.copy(showNowLine = true) }; frames()
        val both = image()
        for (col in px(4)..x - px(14)) {
            assertTrue(ink(both, col, anchor))
            assertFalse(ink(both, col, anchor + px(4)))
        }
        assertTrue(ink(both, x, anchor + px(6)))
        ui.runOnIdle { settings.value = settings.value.copy(nowArrow = false) }; frames()
        val lineOnly = image()
        assertFalse(ink(lineOnly, x - px(2), anchor + px(6)))
        assertTrue(ink(lineOnly, px(8), anchor))
        // A frame clock alone must not change any of the line's pixels.
        ui.mainClock.advanceTimeBy(432)
        assertArrayEquals(slice(lineOnly, 0, 0, lineOnly.width, lineOnly.height),
            slice(image(), 0, 0, lineOnly.width, lineOnly.height))
    }

    @Test fun timeSurvivesAllDecorationsOffAndClipsAtBothEdgesOfDstDayViewport() {
        show()
        val description = ui.activity.getString(R.string.today_now_at, "01:30 -05:00")
        ui.runOnIdle { settings.value = settings.value.copy(nowArrow = false) }; frames()
        val label = ui.onNodeWithContentDescription(description)
        label.assertIsDisplayed()
        val empty = image()
        assertFalse((anchor - px(17) .. anchor).any { ink(empty, px(96), it) })
        ui.runOnIdle { runBlocking { scroll.scrollTo(0) } }; frames(); label.assertDoesNotExist()
        ui.runOnIdle { clock.longValue += 60_000 }; frames()
        ui.onNodeWithTag("now-line").assertDoesNotExist()
        ui.runOnIdle { assertEquals("A minute tick must not move a manually scrolled viewport", 0, scroll.value); clock.longValue = fixed }; frames()
        ui.runOnIdle { runBlocking { scroll.scrollTo(axisY + px(2)) } }; frames(); label.assertDoesNotExist()
        ui.runOnIdle { runBlocking { scroll.scrollTo(axisY - anchor) } }; frames(); label.assertIsDisplayed()
        ui.runOnIdle { clock.longValue = day.endMillis }; frames(); ui.onNodeWithTag("now-line").assertDoesNotExist()
        ui.runOnIdle { clock.longValue = fixed }; frames(); label.assertIsDisplayed()
    }
}
