package io.github.nakanomk.stacklife.appearance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.*
import dev.chrisbanes.haze.*
import dev.chrisbanes.haze.glass.*

internal val LocalPlannerGlassAllowed = staticCompositionLocalOf { false }
internal val LocalCapsuleBackdrop = compositionLocalOf<HazeState?> { null }
internal enum class CapsuleScene { NAVIGATION, ASSISTANT, CHROME, COURSE }

@Composable
internal fun rememberPlannerBackdrop(enabled: Boolean = true): HazeState? {
    val appearance = LocalAppearance.current
    return if (enabled && LocalPlannerGlassAllowed.current && appearance.glass && glassUnavailableReason(appearance) == null)
        rememberHazeState() else null
}

@Composable
internal fun GlassBacking(modifier: Modifier = Modifier, background: @Composable BoxScope.() -> Unit,
    foreground: @Composable BoxScope.() -> Unit) {
    val source = rememberPlannerBackdrop()
    Box(modifier) {
        Box(if (source != null) Modifier.matchParentSize().hazeSource(source) else Modifier.matchParentSize(), content = background)
        CompositionLocalProvider(LocalCapsuleBackdrop provides source) { foreground() }
    }
}

@Composable
internal fun StackLifeSurface(scene: CapsuleScene, modifier: Modifier, solid: Color, elevation: Dp = 0.dp,
    shape: RoundedCornerShape = CircleShape, tint: Color = Color.Unspecified, content: @Composable () -> Unit) {
    val appearance = LocalAppearance.current
    val source = LocalCapsuleBackdrop.current?.takeIf {
        when (scene) {
            CapsuleScene.NAVIGATION -> appearance.navGlass
            CapsuleScene.ASSISTANT -> appearance.assistantGlass
            CapsuleScene.CHROME, CapsuleScene.COURSE -> true
        }
    }
    val base = MaterialTheme.colorScheme.surface
    val tinted = tint != Color.Unspecified
    val course = scene == CapsuleScene.COURSE
    val material = if (source == null) Modifier else {
        val style = remember(base, shape, tint, course) {
            GlassStyle.regular.then {
                shape(shape)
                backgroundColor(base)
                tint(if (tinted) tint.copy(alpha = if (course) .92f else .68f) else base.copy(alpha = .54f))
                optics { copy(depth = OpticalSizeValue.Fixed(1f), blurRadius = OpticalSizeValue.Fixed(10.dp),
                    refractionDisplacement = 8.dp, refractionProfile = RefractionProfile.Edge(8.dp)) }
                chromaticAberrationStrength(0f)
                specularIntensity(if (course) .52f else .34f)
                edgeShadow(Color.Black.copy(alpha = .14f))
                whitePoint(0f)
            }
        }
        val input = remember(source) { HazeInput.Sources(source, retention = HazeSourceRetention.ClearWhenUnavailable) }
        Modifier.hazeGlass(input = input, style = style)
    }
    val glow = if (source != null && course && tinted) Modifier.shadow(5.dp, shape, clip = false,
        ambientColor = tint, spotColor = tint) else Modifier
    val edge = when {
        source == null -> MaterialTheme.colorScheme.outlineVariant
        tinted -> lerp(tint, Color.White, .36f).copy(alpha = .75f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = .26f)
    }
    val semantics = if (scene == CapsuleScene.NAVIGATION || scene == CapsuleScene.ASSISTANT) Modifier.semantics {
        stateDescription = if (source == null) "实色胶囊" else "折射胶囊"
    } else Modifier
    Surface(modifier.then(semantics).then(glow).then(material),
        shape = shape, color = if (source == null) solid else Color.Transparent,
        border = BorderStroke(if (course && source != null) 1.dp else Dp.Hairline, edge),
        shadowElevation = if (source == null) elevation else 0.dp, content = content)
}
