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
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
                    CompositionLocalProvider(LocalAppearance provides Appearance(reduceTransparency = solid)) {
                        Box(Modifier.size(200.dp, 100.dp)) {
                            WallpaperHost(WallpaperSpec(id, tint = 0f)) {
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
