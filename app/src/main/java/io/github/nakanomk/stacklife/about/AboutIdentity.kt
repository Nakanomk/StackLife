package io.github.nakanomk.stacklife.about

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.appearance.FrostedCard

// Hero dimensions and container roles adapted from BiliPai's MaterialAboutOverview (GPL-3.0):
// github.com/jay3-yy/BiliPai/blob/5893c2c80739e3759ac64ae5f232cf03a94da7f8/app/src/main/java/com/android/purebilibili/feature/settings/ui/MaterialAboutContent.kt
// The face, its framing, eyelid rig and responsive text placement are StackLife's own.
@Composable
internal fun AboutIdentity(version: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalContentColor provides colors.onPrimaryContainer) {
        FrostedCard(Modifier.fillMaxWidth(), color = colors.primaryContainer) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp)) {
                val faceSize = if (maxWidth < 300.dp) 72.dp else 88.dp
                // Reserve text room at its actual font scale, not just at the default phone size.
                val titleSize = MaterialTheme.typography.headlineMedium.fontSize
                val textRoom = with(LocalDensity.current) { titleSize.toDp() * 5f }
                if (maxWidth >= faceSize + 24.dp + 16.dp + textRoom) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        FumoBadge(faceSize)
                        IdentityText(version)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FumoBadge(faceSize)
                        IdentityText(version)
                    }
                }
            }
        }
    }
}

@Composable
private fun IdentityText(version: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.about_developer), style = MaterialTheme.typography.bodyMedium)
        version()
    }
}

// Blender 5.3.0 Bézier shape-key F-curve, 60 fps. Normalized handle coordinates are
// (handle - segmentStart) / (segmentEnd - segmentStart), independently for time and value.
// Hold f0–27; close through f30 to f33; hold to f36; reopen through f42 to f48.
private val closeLids = keyframes<Float> {
    delayMillis = 450
    durationMillis = 100
    0f at 0 using CubicBezierEasing(1f/3f, 0f, 2f/3f, .647887f)
    .71f at 50 using CubicBezierEasing(1f/3f, .862069f, 2f/3f, 1f)
}
private val openLids = keyframes<Float> {
    delayMillis = 50
    durationMillis = 200
    1f at 0 using CubicBezierEasing(1f/3f, 0f, 2f/3f, .717949f)
    .22f at 100 using CubicBezierEasing(1f/3f, 1f, 2f/3f, 1f)
}

@Composable
private fun FumoBadge(faceSize: Dp) {
    val motion = LocalMotionEnabled.current
    val closure = remember { Animatable(0f) }
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(motion) {
        if (!entered && motion) {
            entered = true
            closure.animateTo(1f, closeLids)
            closure.animateTo(0f, openLids)
        } else {
            entered = true
            closure.snapTo(0f)
        }
    }
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    val eyes = remember { Path() }
    val eyeStroke = remember { Stroke(width = 4.5f, join = StrokeJoin.Round) }
    val mouth = remember { Path().apply {
        moveTo(47f, 62f)
        quadraticTo(50f, 68f, 54f, 63f)
        quadraticTo(58f, 68f, 61f, 62f)
    } }
    val mouthStroke = remember { Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Canvas(Modifier.padding(12.dp).size(faceSize).testTag("about-fumo")) {
            val blink = if (motion) closure.value else 0f
            // Exact original 108-unit contour, recentered in a 60-unit artboard. Close
            // by moving the eye control points to y=51, not by thinning the stroke.
            val top = 42f + 9f * blink
            val middle = 50f + blink
            val bottom = 58f - 7f * blink
            eyes.rewind()
            eyes.eye(28f, top, middle, bottom)
            eyes.eye(62f, top, middle, bottom)
            val unit = size.minDimension / 60f
            withTransform({
                translate(center.x, center.y)
                scale(unit, unit, Offset.Zero)
                translate(-54f, -53f)
            }) {
                drawPath(eyes, ink, style = eyeStroke)
                drawPath(mouth, ink, style = mouthStroke)
            }
        }
    }
}

private fun Path.eye(x: Float, top: Float, mid: Float, bottom: Float) {
    moveTo(x, top)
    lineTo(x + 18f, top)
    lineTo(x + 18f, mid)
    quadraticTo(x + 18f, bottom, x + 9f, bottom)
    quadraticTo(x, bottom, x, mid)
    close()
}
