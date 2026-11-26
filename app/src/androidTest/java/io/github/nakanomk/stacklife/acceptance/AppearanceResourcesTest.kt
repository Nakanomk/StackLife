package io.github.nakanomk.stacklife.acceptance

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.RootPane
import io.github.nakanomk.stacklife.appearance.LocalPlannerGlassAllowed
import io.github.nakanomk.stacklife.appearance.LocalPlatformAppearance
import io.github.nakanomk.stacklife.appearance.PlatformAppearance
import io.github.nakanomk.stacklife.workspace.Page
import io.github.nakanomk.stacklife.workspace.PageTitle
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.BackgroundPhoto
import io.github.nakanomk.stacklife.appearance.BackgroundStore
import io.github.nakanomk.stacklife.appearance.LocalBackgroundStore
import io.github.nakanomk.stacklife.planner.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
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

    @OptIn(ExperimentalTestApi::class)
    @Test fun previewSystemBarsFollowDraftThenReturnToTheLatestSavedMode() {
        val saved = mutableStateOf(ThemeMode.LIGHT)
        val draft = mutableStateOf<ThemeMode?>(null)
        val glass = mutableStateOf(true)
        val fontScale = mutableStateOf(1f)
        val surface = IntArray(1)
        val context = ui.activity.applicationContext
        val folder = Files.createTempDirectory(context.cacheDir.toPath(), "bars-photo-").toFile()
        val id = UUID.randomUUID().toString()
        val photos = BackgroundStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }) { setOf(id) }
        try {
            val directory = File(folder, "background-images").apply { mkdir() }
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.RED)
                Canvas(bitmap).drawRect(16f, 0f, 32f, 32f, android.graphics.Paint().apply { color = Color.BLUE })
                File(directory, id).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            @Composable fun content() {
                val fill = MaterialTheme.colorScheme.surface.toArgb()
                SideEffect { surface[0] = fill }
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale.value)) {
                    Page {
                        RootPane(header = {
                            Box(Modifier.testTag("header-controls")) {
                                PageTitle(context.getString(R.string.ui_settings), back = { ui.activity.onBackPressedDispatcher.onBackPressed() })
                            }
                        }) { _, _ -> BackgroundPhoto(id, Modifier.fillMaxSize()) }
                    }
                }
            }
            fun attach() = ui.activityRule.scenario.onActivity { activity ->
                activity.enableEdgeToEdge()
                activity.setContent { CompositionLocalProvider(LocalBackgroundStore provides photos) {
                    StackLifeTheme(Appearance(theme = saved.value, glass = glass.value)) {
                        CompositionLocalProvider(LocalPlannerGlassAllowed provides true,
                            LocalPlatformAppearance provides PlatformAppearance(accelerated = true)) {
                            val mode = draft.value
                            if (mode == null) content()
                            else StackLifeTheme(Appearance(theme = mode, glass = glass.value)) { content() }
                        }
                    }
                } }
            }
            fun bars(light: Boolean) {
                val decor = android.graphics.Rect()
                val top = ui.runOnIdle {
                    val window = ui.activity.window
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    assertEquals(light, controller.isAppearanceLightStatusBars)
                    assertEquals(light, controller.isAppearanceLightNavigationBars)
                    val origin = IntArray(2)
                    window.decorView.getLocationOnScreen(origin)
                    decor.set(origin[0], origin[1], origin[0] + window.decorView.width, origin[1] + window.decorView.height)
                    requireNotNull(ViewCompat.getRootWindowInsets(window.decorView))
                        .getInsets(WindowInsetsCompat.Type.statusBars()).top
                }
                assertTrue("No status bar on the test window", top > 0)
                assertTrue(ui.onNodeWithTag("header-controls").fetchSemanticsNode().boundsInRoot.top >= top - 1)
                val transmitting = glass.value && Build.VERSION.SDK_INT >= 33
                // System screenshot includes the actual translucent bar. Sample its icon-free
                // interior and both sides of the inset; a header starting at the inset leaves a seam.
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                var failure = ""
                try {
                    ui.waitUntil(10_000) {
                        val shot = requireNotNull(automation.takeScreenshot())
                        val image = if (shot.config == Bitmap.Config.HARDWARE)
                            requireNotNull(shot.copy(Bitmap.Config.ARGB_8888, false)).also { shot.recycle() } else shot
                        try {
                            val left = decor.left + decor.width() * 3 / 8
                            val right = decor.left + decor.width() * 5 / 8
                            fun at(x: Int, y: Int) = image.getPixel(x, decor.top + y)
                            val probes = listOf(left, right)
                            fun chroma(y: Int) = Color.red(at(left, y)) - Color.blue(at(left, y)) > 32 &&
                                Color.blue(at(right, y)) - Color.red(at(right, y)) > 32
                            fun distance(a: Int, b: Int) = maxOf(kotlin.math.abs(Color.red(a) - Color.red(b)),
                                kotlin.math.abs(Color.green(a) - Color.green(b)), kotlin.math.abs(Color.blue(a) - Color.blue(b)))
                            val matches = if (transmitting) {
                                chroma(0) && chroma(top / 2) && chroma(top) &&
                                    probes.all { distance(at(it, top - 1), at(it, top)) < 8 } &&
                                    // The continuous wash fades as it approaches the clear edge.
                                    probes.any { distance(at(it, top / 2), at(it, top * 2)) > 4 }
                            } else probes.all { x -> listOf(0, top / 2, top - 1, top, top + 1).all { y ->
                                distance(at(x, y), surface[0]) <= 2
                            } }
                            failure = "light=$light, glass=$transmitting, inset=$top, " +
                                "top=${at(left, top / 2).toUInt().toString(16)}, seam=${at(left, top).toUInt().toString(16)}"
                            matches
                        } finally { image.recycle() }
                    }
                } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                    throw AssertionError(failure, e)
                }
            }
            attach(); bars(true)
            ui.runOnIdle { fontScale.value = 2f }; bars(true)
            ui.runOnIdle { draft.value = ThemeMode.DARK }; bars(false)
            ui.runOnIdle { draft.value = null }; bars(true)
            ui.runOnIdle { draft.value = ThemeMode.LIGHT; saved.value = ThemeMode.DARK }; bars(true)
            ui.runOnIdle { draft.value = null }; bars(false)
            ui.runOnIdle { glass.value = false }; bars(false)
            ui.activityRule.scenario.recreate(); attach(); bars(false)
        } finally { folder.deleteRecursively() }
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
