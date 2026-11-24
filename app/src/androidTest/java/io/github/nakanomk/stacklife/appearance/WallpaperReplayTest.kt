package io.github.nakanomk.stacklife.appearance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.StackLifeApplication
import io.github.nakanomk.stacklife.planner.Appearance
import io.github.nakanomk.stacklife.planner.WallpaperSpec
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WallpaperReplayTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun aMovingReceiverReplaysThePhotoInScreenCoordinatesButNeverPageForeground() {
        val app = ui.activity.application as StackLifeApplication
        runBlocking { app.backgrounds.recover() }
        val id = UUID.randomUUID().toString()
        val file = File(File(app.noBackupFilesDir, "background-images"), id)
        val b = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            b.eraseColor(android.graphics.Color.RED)
            Canvas(b).drawRect(32f, 0f, 64f, 32f, Paint().apply { color = android.graphics.Color.BLUE })
            file.outputStream().use { assertTrue(b.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            var right by mutableStateOf(false)
            var solid by mutableStateOf(false)
            ui.setContent {
                MaterialTheme(colorScheme = lightColorScheme(surface = Color.White, onSurface = Color.Black)) {
                    CompositionLocalProvider(LocalAppearance provides Appearance(reduceTransparency = solid),
                        LocalPlannerGlassAllowed provides true, LocalPlatformAppearance provides rememberPlatformAppearance()) {
                        Box(Modifier.size(200.dp, 100.dp)) {
                            WallpaperHost(WallpaperSpec(id, tint = 0f), drawBehindContent = false) {
                                Box(Modifier.fillMaxSize().background(Color.Green)) {
                                    Box(Modifier.align(if (right) Alignment.CenterEnd else Alignment.CenterStart)
                                        .size(32.dp).wallpaperBackground(Color.Yellow).testTag("receiver"))
                                }
                            }
                        }
                    }
                }
            }
            fun center(): Color = ui.onNodeWithTag("receiver").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
            ui.waitUntil(10_000) { center().let { it.red > it.blue + .2f && it.red > it.green + .2f } }
            ui.runOnIdle { right = true }
            ui.waitUntil(10_000) { center().let { it.blue > it.red + .2f && it.blue > it.green + .2f } }
            ui.runOnIdle { solid = true }
            assertEquals(Color.Yellow, center())
        } finally { b.recycle(); file.delete() }
    }

    @Test fun privateWallpaperBlursProgressivelyWithoutFilteringForegroundOrChangingTheCrop() {
        val app = ui.activity.application as StackLifeApplication
        runBlocking { app.backgrounds.recover() }
        val id = UUID.randomUUID().toString()
        val file = File(File(app.noBackupFilesDir, "background-images"), id)
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(android.graphics.Color.WHITE)
            val canvas = Canvas(source)
            val gray = Paint().apply { color = android.graphics.Color.rgb(128, 128, 128) }
            for (x in 0 until 256 step 16) canvas.drawRect(x.toFloat(), 0f, x + 8f, 256f, gray)
            file.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            var strength by mutableFloatStateOf(0f)
            var protect by mutableStateOf(false)
            ui.setContent {
                MaterialTheme(colorScheme = lightColorScheme(surface = Color.White, onSurface = Color.Black)) {
                    CompositionLocalProvider(LocalAppearance provides Appearance(reduceTransparency = protect)) {
                        Box(Modifier.size(160.dp).testTag("blur-preview")) {
                            WallpaperHost(WallpaperSpec(imageId = id, tint = 0f, blur = strength)) {
                                Box(Modifier.fillMaxSize()) { Spacer(Modifier.align(Alignment.TopCenter).size(12.dp).background(Color.Red)) }
                            }
                        }
                    }
                }
            }
            fun contrast(name: String, ready: (Float) -> Boolean): Float {
                var contrast = 0f
                ui.waitUntil(12_000) {
                    val image = ui.onNodeWithTag("blur-preview").captureToImage()
                    val px = image.toPixelMap()
                    val ink = px[px.width / 2, px.height / 32]
                    assertTrue("Foreground changed: $ink", ink.red > .98f && ink.blue < .02f)
                    var min = 1f; var max = 0f
                    for (x in px.width / 4 until px.width * 3 / 4) {
                        val value = px[x, px.height / 2].red
                        min = minOf(min, value); max = maxOf(max, value)
                    }
                    contrast = max - min
                    val ok = ready(contrast) && (if (name == "solid") min > .99f else min < .95f)
                    val inst = InstrumentationRegistry.getInstrumentation()
                    if (ok && InstrumentationRegistry.getArguments().getString("captureBlur") == "yes") {
                        File(inst.targetContext.getExternalFilesDir(null), "blur-$name.png").outputStream().use {
                            check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    }
                    ok
                }
                return contrast
            }
            val sharp = contrast("0") { it > .35f }
            ui.runOnIdle { strength = .5f }
            val half = contrast("50") { it in .05f..(sharp - .04f) }
            ui.runOnIdle { strength = 1f }
            val full = contrast("100") { it < half - .03f }
            ui.runOnIdle { protect = true }
            contrast("solid") { it == 0f }
            ui.runOnIdle { protect = false }
            contrast("restored") { it in 0f..(full + .01f) && it > 0f }
            ui.runOnIdle { strength = 0f }
            contrast("unfiltered") { it >= sharp - .01f }
        } finally { source.recycle(); file.delete() }
    }

    @Test fun protectionUsesOnlyTheVisibleCropAndFiveLocalBands() {
        val bitmap = Bitmap.createBitmap(120, 40, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            Canvas(bitmap).drawRect(0f, 0f, 40f, 20f, Paint().apply { color = android.graphics.Color.BLACK })
            val size = IntSize(40, 40)
            fun sample(focus: Float) = wallpaperBands(bitmap, size,
                wallpaperCrop(size, IntSize(120, 40), WallpaperSpec(offsetX = focus)), Color.Black, Color.White)
            val left = sample(-1f); val right = sample(1f)
            assertEquals(.16f, left.first(), .001f); assertTrue(left.last() > .53f)
            assertTrue(right.all { it > .53f }); assertEquals(right.first(), right.last(), .001f)
        } finally { bitmap.recycle() }
    }
}
