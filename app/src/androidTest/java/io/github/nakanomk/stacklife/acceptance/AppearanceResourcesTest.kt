package io.github.nakanomk.stacklife.acceptance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.planner.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceResourcesTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun foregroundAndMonochromeShareTheUnclippedAdaptiveMaskSafeZone() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val app = ui.activity.applicationInfo
        val icon = ui.activity.getDrawable(app.icon) as AdaptiveIconDrawable
        fun alpha(drawable: Drawable): List<Int> {
            val b = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 108, 108); drawable.draw(Canvas(b))
            return (0 until 108 * 108).map { Color.alpha(b.getPixel(it % 108, it / 108)) }
        }
        val ink = alpha(icon.foreground); assertEquals(ink, alpha(requireNotNull(icon.monochrome)))
        val marks = ink.indices.filter { ink[it] > 0 }; assertTrue(marks.isNotEmpty())
        for (i in marks) {
            val dx = i % 108 + .5 - 54; val dy = i / 108 + .5 - 54
            assertTrue("Foreground mark at $dx,$dy outside the inscribed 66dp safe circle", dx*dx + dy*dy <= 33*33)
        }
    }

    @Test fun previewSystemBarsFollowDraftThenReturnToTheLatestSavedMode() {
        val saved = mutableStateOf(ThemeMode.LIGHT)
        val draft = mutableStateOf<ThemeMode?>(null)
        ui.setContent { StackLifeTheme(Appearance(theme = saved.value)) {
            draft.value?.let { StackLifeTheme(Appearance(theme = it)) {} }
        } }
        fun bars(light: Boolean) = ui.runOnIdle {
            val window = ui.activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertEquals(light, controller.isAppearanceLightStatusBars)
            assertEquals(light, controller.isAppearanceLightNavigationBars)
        }
        bars(true)
        ui.runOnIdle { draft.value = ThemeMode.DARK }; bars(false)
        ui.runOnIdle { draft.value = null }; bars(true)
        ui.runOnIdle { draft.value = ThemeMode.LIGHT; saved.value = ThemeMode.DARK }; bars(true)
        ui.runOnIdle { draft.value = null }; bars(false)
    }

    @Test fun userSeedAndSystemPalettesKeepReadableTextInBothThemes() {
        val state = mutableStateOf(Appearance()); var roles = emptyList<Pair<Int, Int>>()
        ui.setContent { StackLifeTheme(state.value) {
            val c = MaterialTheme.colorScheme
            SideEffect { roles = listOf(c.onSurface to c.surface, c.onSurfaceVariant to c.surfaceContainerHigh,
                c.onPrimaryContainer to c.primaryContainer, c.onSecondaryContainer to c.secondaryContainer, c.onPrimary to c.primary).map { it.first.toArgb() to it.second.toArgb() } }
        } }
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) for (palette in PaletteMode.entries) for (contrast in listOf(false, true)) {
            val a = Appearance(theme = theme, palette = palette, seed = "#FFFF00", highContrast = contrast)
            ui.runOnIdle { state.value = a }; ui.waitForIdle()
            assertEquals(5, roles.size)
            for ((fg, bg) in roles) assertTrue("$theme/$palette/highContrast=$contrast: ${ColorUtils.calculateContrast(fg, bg)}", ColorUtils.calculateContrast(fg, bg) >= 4.5)
        }
    }
}
