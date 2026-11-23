package io.github.nakanomk.stacklife

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.snap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.*
import androidx.core.view.WindowCompat
import com.materialkolor.MaterialKolors
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.rememberDynamicScheme
import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.planner.*

private val SilentHaptics = object : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

private val StillMotion = object : MotionScheme {
    override fun <T> defaultSpatialSpec() = snap<T>()
    override fun <T> fastSpatialSpec() = snap<T>()
    override fun <T> slowSpatialSpec() = snap<T>()
    override fun <T> defaultEffectsSpec() = snap<T>()
    override fun <T> fastEffectsSpec() = snap<T>()
    override fun <T> slowEffectsSpec() = snap<T>()
}

private val LocalSystemDark = staticCompositionLocalOf<Boolean?> { null }
private val LocalSystemHaptics = staticCompositionLocalOf<HapticFeedback?> { null }
private val LocalBarPreviews = staticCompositionLocalOf<SnapshotStateList<State<Boolean>>?> { null }
private val defaultSeed = Appearance().seed
private val plannerTypography = Typography()

@Composable
internal fun StackLifeTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val upstreamDark = LocalSystemDark.current
    val systemDark = upstreamDark ?: isSystemInDarkTheme()
    val dark = when (appearance.theme) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val nested = upstreamDark != null
    val platform = if (nested) LocalPlatformAppearance.current else rememberPlatformAppearance()
    val contrast = if (appearance.highContrast) 1.0 else platform.contrast.toDouble()
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val dynamic = Build.VERSION.SDK_INT >= 31 && appearance.palette == PaletteMode.DYNAMIC
    val colors = if (Build.VERSION.SDK_INT >= 31 && dynamic && contrast == 0.0) {
        remember(context, config, dark) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
    } else {
        val seedHex = if (appearance.palette == PaletteMode.DYNAMIC) defaultSeed else appearance.seed
        val seed = remember(seedHex, dynamic, config) {
            if (Build.VERSION.SDK_INT >= 31 && dynamic) Color(context.getColor(android.R.color.system_accent1_500))
            else Color(android.graphics.Color.parseColor(seedHex))
        }
        val scheme = rememberDynamicScheme(seed, dark, contrastLevel = contrast,
            specVersion = ColorSpec.SpecVersion.SPEC_2025)
        remember(scheme) { MaterialKolors(scheme).toAndroidX() }
    }
    // One window writer. A preview contributes its live value; leaving removes only that
    // contribution, revealing the latest saved theme rather than a captured pre-editor value.
    val parentPreviews = LocalBarPreviews.current
    val barPreviews = parentPreviews ?: remember { mutableStateListOf<State<Boolean>>() }
    val barsDark = if (parentPreviews == null) barPreviews.lastOrNull()?.value ?: dark else dark
    if (parentPreviews != null) {
        val preview = rememberUpdatedState(dark)
        DisposableEffect(barPreviews, preview) {
            barPreviews.add(preview)
            onDispose { barPreviews.remove(preview) }
        }
    } else {
        val window = LocalActivity.current?.window
        val view = LocalView.current
        SideEffect {
            if (window != null) {
                // The root draws a fully opaque protection. Do not let DecorView add a second
                // contrast scrim over it when an in-app preview differs from the system theme.
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 29) window.isStatusBarContrastEnforced = false
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !barsDark
                    isAppearanceLightNavigationBars = !barsDark
                }
            }
        }
    }
    val motion = platform.animators && !appearance.reduceMotion
    val systemHaptics = LocalSystemHaptics.current ?: LocalHapticFeedback.current
    val haptics = if (appearance.haptics && platform.haptics) systemHaptics else SilentHaptics
    CompositionLocalProvider(LocalSystemDark provides systemDark, LocalSystemHaptics provides systemHaptics, LocalBarPreviews provides barPreviews,
        LocalAppearance provides appearance,
        LocalPlatformAppearance provides platform, LocalMotionEnabled provides motion, LocalHapticFeedback provides haptics) {
        MaterialTheme(colorScheme = colors, typography = plannerTypography,
            motionScheme = if (motion) MotionScheme.standard() else StillMotion) {
            if (nested) content() else Box(Modifier.fillMaxSize()) {
                val statusBars = WindowInsets.statusBars
                // Preserve the full layout/coordinate plane (including transition anchors) but
                // never paint wallpaper or private transition content behind the protection.
                Box(Modifier.fillMaxSize().drawWithContent {
                    clipRect(top = statusBars.getTop(this).toFloat()) { this@drawWithContent.drawContent() }
                }) { content() }
                // Android's edge-to-edge system-bar protection pattern: last in the root window,
                // outside every navigation overlay and recording layer. Does not consume insets.
                Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(statusBars)
                    .drawBehind {
                        // An integer-pixel scissor and a solid fill, not an antialiased edge
                        // laid over a contrasting photo at the final protected pixel.
                        clipRect {
                            drawContext.canvas.nativeCanvas.drawColor(if (barsDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                        }
                    })
            }
        }
    }
}

// Core's scheme owns all tones (including fixed roles); do not mix in AndroidX baseline roles.
private fun MaterialKolors.toAndroidX() = ColorScheme(
    primary = primary(), onPrimary = onPrimary(), primaryContainer = primaryContainer(), onPrimaryContainer = onPrimaryContainer(),
    inversePrimary = inversePrimary(),
    secondary = secondary(), onSecondary = onSecondary(), secondaryContainer = secondaryContainer(), onSecondaryContainer = onSecondaryContainer(),
    tertiary = tertiary(), onTertiary = onTertiary(), tertiaryContainer = tertiaryContainer(), onTertiaryContainer = onTertiaryContainer(),
    background = background(), onBackground = onBackground(), surface = surface(), onSurface = onSurface(),
    surfaceVariant = surfaceVariant(), onSurfaceVariant = onSurfaceVariant(), surfaceTint = surfaceTint(),
    inverseSurface = inverseSurface(), inverseOnSurface = inverseOnSurface(), error = error(), onError = onError(),
    errorContainer = errorContainer(), onErrorContainer = onErrorContainer(), outline = outline(), outlineVariant = outlineVariant(),
    scrim = scrim(), surfaceBright = surfaceBright(), surfaceDim = surfaceDim(), surfaceContainer = surfaceContainer(),
    surfaceContainerHigh = surfaceContainerHigh(), surfaceContainerHighest = surfaceContainerHighest(),
    surfaceContainerLow = surfaceContainerLow(), surfaceContainerLowest = surfaceContainerLowest(),
    primaryFixed = primaryFixed(), primaryFixedDim = primaryFixedDim(), onPrimaryFixed = onPrimaryFixed(), onPrimaryFixedVariant = onPrimaryFixedVariant(),
    secondaryFixed = secondaryFixed(), secondaryFixedDim = secondaryFixedDim(), onSecondaryFixed = onSecondaryFixed(), onSecondaryFixedVariant = onSecondaryFixedVariant(),
    tertiaryFixed = tertiaryFixed(), tertiaryFixedDim = tertiaryFixedDim(), onTertiaryFixed = onTertiaryFixed(), onTertiaryFixedVariant = onTertiaryFixedVariant(),
)
