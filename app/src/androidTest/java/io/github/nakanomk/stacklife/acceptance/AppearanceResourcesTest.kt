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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
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

    @Test fun previewSystemBarsFollowDraftThenReturnToTheLatestSavedMode() {
        val saved = mutableStateOf(ThemeMode.LIGHT)
        val draft = mutableStateOf<ThemeMode?>(null)
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
            fun attach() = ui.activityRule.scenario.onActivity { activity ->
                activity.enableEdgeToEdge()
                activity.setContent { CompositionLocalProvider(LocalBackgroundStore provides photos) {
                    StackLifeTheme(Appearance(theme = saved.value)) {
                        Box(Modifier.fillMaxSize()) {
                            BackgroundPhoto(id, Modifier.fillMaxSize())
                            draft.value?.let { mode ->
                                Box(Modifier.align(Alignment.Center).fillMaxWidth().fillMaxHeight(.4f)) {
                                    StackLifeTheme(Appearance(theme = mode)) { BackgroundPhoto(id, Modifier.fillMaxSize()) }
                                }
                            }
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
                val expected = if (light) Color.WHITE else Color.BLACK
                // The API37 display capture can interpolate a single channel step at the seam.
                // Keep alpha exact there too; the software raster below gets no tolerance at all.
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
                            fun photo(y: Int): Boolean {
                                val a = at(left, y); val b = at(right, y)
                                return Color.red(a) - Color.blue(a) > 102 && Color.blue(b) - Color.red(b) > 102
                            }
                            val nestedRow = (decor.height() * .3f).toInt() + top / 2
                            fun matchesBar(x: Int, y: Int): Boolean {
                                val pixel = at(x, y)
                                if (y != top - 1) return pixel == expected
                                val range = if (light) 254..255 else 0..1
                                return Color.alpha(pixel) == 255 && Color.red(pixel) in range &&
                                    Color.green(pixel) in range && Color.blue(pixel) in range
                            }
                            val mismatch = (0 until top).firstOrNull { y -> !matchesBar(left, y) || !matchesBar(right, y) }
                            fun colors(y: Int) = "${at(left, y).toUInt().toString(16)}/${at(right, y).toUInt().toString(16)}"
                            failure = "light=$light, decor=$decor, inset=$top, first mismatched bar row=$mismatch " +
                                "(${colors(mismatch ?: 0)}), photo at inset=${colors(top)}, nested=${colors(nestedRow)}"
                            mismatch == null && photo(top) && photo(nestedRow)
                        } finally { image.recycle() }
                    }
                } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
                    throw AssertionError(failure, e)
                }
                // GraphicsLayer.toImageBitmap() still renders into a GPU texture, then copies
                // that texture into ARGB for readback. Draw the actual app view into a native-size
                // *software* raster instead, with no PixelCopy, GPU texture sampling or rescaling.
                ui.runOnIdle {
                    val view = ui.activity.findViewById<android.view.View>(android.R.id.content)
                    assertEquals(decor.width(), view.width); assertEquals(decor.height(), view.height)
                    val app = Bitmap.createBitmap(view.width, top + 1, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(app))
                        val pixels = IntArray(app.width * (top + 1))
                        app.getPixels(pixels, 0, app.width, 0, 0, app.width, top + 1)
                        for (i in 0 until app.width * top) {
                            if (pixels[i] != expected) fail("Software app bar ($light) pixel ${i % app.width},${i / app.width}: ${pixels[i].toUInt().toString(16)}")
                        }
                        val firstContent = app.width * top
                        val left = pixels[firstContent + app.width * 3 / 8]
                        val right = pixels[firstContent + app.width * 5 / 8]
                        assertTrue("App photo must begin at inset row $top",
                            Color.red(left) - Color.blue(left) > 102 && Color.blue(right) - Color.red(right) > 102)
                    } finally { app.recycle() }
                }
            }
            attach(); bars(true)
            ui.runOnIdle { draft.value = ThemeMode.DARK }; bars(false)
            ui.runOnIdle { draft.value = null }; bars(true)
            ui.runOnIdle { draft.value = ThemeMode.LIGHT; saved.value = ThemeMode.DARK }; bars(true)
            ui.runOnIdle { draft.value = null }; bars(false)
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
