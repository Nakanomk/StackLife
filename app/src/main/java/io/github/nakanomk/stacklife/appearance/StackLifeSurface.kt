package io.github.nakanomk.stacklife.appearance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.*
import dev.chrisbanes.haze.*
import dev.chrisbanes.haze.glass.*

internal val LocalPlannerGlassAllowed = staticCompositionLocalOf { false }
internal val LocalCapsuleBackdrop = compositionLocalOf<HazeState?> { null }
internal enum class CapsuleScene { NAVIGATION, ASSISTANT }

@Composable
internal fun StackLifeSurface(scene: CapsuleScene, modifier: Modifier, solid: Color, elevation: Dp = 0.dp,
    content: @Composable () -> Unit) {
    val appearance = LocalAppearance.current
    val source = LocalCapsuleBackdrop.current?.takeIf {
        if (scene == CapsuleScene.NAVIGATION) appearance.navGlass else appearance.assistantGlass
    }
    val base = MaterialTheme.colorScheme.surface
    val material = if (source == null) Modifier else {
        val style = remember(base) {
            GlassStyle.regular.then {
                shape(CircleShape)
                backgroundColor(base)
                tint(base.copy(alpha = .54f))
                optics { copy(depth = OpticalSizeValue.Fixed(1f), blurRadius = OpticalSizeValue.Fixed(10.dp),
                    refractionDisplacement = 8.dp, refractionProfile = RefractionProfile.Edge(8.dp)) }
                chromaticAberrationStrength(0f)
                specularIntensity(.24f)
                edgeShadow(Color.Black.copy(alpha = .09f))
                whitePoint(0f)
            }
        }
        val input = remember(source) { HazeInput.Sources(source, retention = HazeSourceRetention.ClearWhenUnavailable) }
        Modifier.hazeGlass(input = input, style = style)
    }
    Surface(modifier.semantics { stateDescription = if (source == null) "实色胶囊" else "折射胶囊" }.then(material),
        shape = CircleShape, color = if (source == null) solid else Color.Transparent,
        border = if (source == null) BorderStroke(Dp.Hairline, MaterialTheme.colorScheme.outlineVariant) else null,
        shadowElevation = if (source == null) elevation else 0.dp, content = content)
}
