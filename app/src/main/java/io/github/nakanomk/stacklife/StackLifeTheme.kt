package io.github.nakanomk.stacklife

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Brand roles for devices without wallpaper-derived colors.
private val light = lightColorScheme(
    primary = Color(0xFF435D92), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF), onPrimaryContainer = Color(0xFF102F5E),
    secondary = Color(0xFF555F76), secondaryContainer = Color(0xFFD9E3FA),
    onSecondaryContainer = Color(0xFF151F32), tertiary = Color(0xFF735572),
)
private val dark = darkColorScheme(
    primary = Color(0xFFADC7FF), onPrimary = Color(0xFF092F61),
    primaryContainer = Color(0xFF2B4679), onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBDC7DE), secondaryContainer = Color(0xFF3D475C),
    onSecondaryContainer = Color(0xFFD9E3FA), tertiary = Color(0xFFDFBBDB),
)

@Composable
internal fun StackLifeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val colors = remember(context, isDark) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (isDark) dark else light
    }
    MaterialExpressiveTheme(colorScheme = colors, content = content)
}
