package io.github.nakanomk.stacklife.appearance

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.*
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

internal val LocalPlannerGlassAllowed = staticCompositionLocalOf { false }
internal val LocalCapsuleBackdrop = compositionLocalOf<Backdrop?> { null }
internal enum class CapsuleScene { NAVIGATION, ASSISTANT, CHROME, ACTION, COURSE, INDICATOR, PANEL }

@Stable
internal class PlannerBackdrop(val layer: GraphicsLayer, private val base: Color, private val below: Backdrop?) : Backdrop {
    override val isCoordinatesDependent = true
    var origin: Offset by mutableStateOf(Offset.Unspecified)

    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        if (below == null) drawRect(base) else with(below) { drawBackdrop(density, coordinates, layerBlock) }
        if (coordinates == null || !coordinates.isAttached || !origin.isSpecified) return
        val target = coordinates.localToScreen(Offset.Zero)
        if (target.isSpecified) translate(origin.x - target.x, origin.y - target.y) { drawLayer(layer) }
    }
}

@Composable
internal fun rememberPlannerBackdrop(enabled: Boolean = true): PlannerBackdrop? {
    val a = LocalAppearance.current
    if (!enabled || !LocalPlannerGlassAllowed.current || !a.glass || glassUnavailableReason(a) != null) return null
    val base = MaterialTheme.colorScheme.surface
    val layer = rememberGraphicsLayer()
    val below = LocalCapsuleBackdrop.current
    return remember(layer, base, below) { PlannerBackdrop(layer, base, below) }
}

internal fun Modifier.plannerBackdrop(source: PlannerBackdrop?): Modifier {
    if (source == null) return this
    return onGloballyPositioned { source.origin = it.localToScreen(Offset.Zero) }.drawWithContent {
        source.layer.record {
            this@drawWithContent.drawContent()
        }
        drawLayer(source.layer)
    }
}

@Composable
internal fun GlassBacking(modifier: Modifier = Modifier, background: @Composable BoxScope.() -> Unit,
    foreground: @Composable BoxScope.() -> Unit) {
    val source = rememberPlannerBackdrop()
    Box(modifier) {
        Box(Modifier.matchParentSize().plannerBackdrop(source), content = background)
        CompositionLocalProvider(LocalCapsuleBackdrop provides source) { foreground() }
    }
}

@Composable
internal fun StackLifeSurface(scene: CapsuleScene, modifier: Modifier, solid: Color, elevation: Dp = 0.dp,
    shape: RoundedCornerShape = CircleShape, tint: Color = Color.Unspecified,
    interactionSource: InteractionSource? = null, content: @Composable () -> Unit) {
    val a = LocalAppearance.current
    val source = LocalCapsuleBackdrop.current?.takeIf { LocalPlannerGlassAllowed.current && a.glass && glassUnavailableReason(a) == null && when (scene) {
        CapsuleScene.NAVIGATION -> a.navGlass
        CapsuleScene.ASSISTANT -> a.assistantGlass
        else -> true
    } }
    val nestedShell = scene == CapsuleScene.PANEL || scene == CapsuleScene.CHROME || scene == CapsuleScene.NAVIGATION
    val export = if (source != null && nestedShell) rememberLayerBackdrop() else null
    val semantics = if (scene == CapsuleScene.NAVIGATION || scene == CapsuleScene.ASSISTANT) Modifier.semantics {
        stateDescription = if (source == null) "实色胶囊" else "折射胶囊"
    } else Modifier
    val material = if (source == null) Modifier else {
        val base = MaterialTheme.colorScheme.surface
        val dark = base.luminance() < .5f
        val course = scene == CapsuleScene.COURSE
        val indicator = scene == CapsuleScene.INDICATOR
        val panel = scene == CapsuleScene.PANEL
        val wash = when {
            course -> tint.copy(alpha = if (dark) .66f else .48f)
            indicator -> tint.copy(alpha = .28f)
            tint.isSpecified -> tint.copy(alpha = tint.alpha * .56f)
            panel -> base.copy(alpha = if (dark) .63f else .70f)
            scene == CapsuleScene.ASSISTANT -> base.copy(alpha = .54f)
            else -> base.copy(alpha = .40f)
        }
        val blend = if (!course) BlendMode.SrcOver else if (dark) BlendMode.Screen else BlendMode.Multiply
        val blurDp = when (scene) {
            CapsuleScene.COURSE, CapsuleScene.INDICATOR -> 1.dp
            CapsuleScene.ASSISTANT -> 10.dp
            CapsuleScene.PANEL -> 24.dp
            else -> 4.dp
        }
        val depth = when { course || indicator -> 10.dp; panel -> 12.dp; else -> 24.dp }
        val displacement = if (indicator || course) 14.dp else depth
        val angle = LocalGlassLight.current
        val highlight = remember(angle, course, indicator) { derivedStateOf {
            Highlight(width = if (indicator) .6.dp else .8.dp,
                style = HighlightStyle.Default(color = Color.White.copy(alpha = if (indicator) .48f else .75f), angle = angle.intValue.toFloat()))
        } }
        val shade = remember(course, tint, dark, indicator) {
            val ink = if (course) lerp(tint, Color.Black, .35f) else Color.Black
            Shadow(radius = if (indicator) 3.dp else if (course) 5.dp else 10.dp, color = ink.copy(alpha = if (indicator) .08f else if (dark || course) .24f else .10f))
        }
        val rim = remember(course, dark) {
            InnerShadow(radius = if (course) 4.dp else 5.dp, offset = DpOffset(0.dp, 1.dp),
                color = Color.White.copy(alpha = if (course) .18f else if (dark) .16f else .08f))
        }
        val press = if (interactionSource != null && LocalMotionEnabled.current) {
            val down by interactionSource.collectIsPressedAsState()
            val scale = animateFloatAsState(if (down) 1.055f else 1f, spring(dampingRatio = .8f, stiffness = 550f), label = "glass press")
            Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        } else Modifier
        press.drawBackdrop(source, shape = { shape }, effects = {
            vibrancy()
            blur(blurDp.toPx())
            lens(depth.toPx(), displacement.toPx(), depthEffect = course, chromaticAberration = indicator)
        }, highlight = { highlight.value }, shadow = { shade }, innerShadow = { rim }, exportedBackdrop = export,
            onDrawSurface = { drawRect(wash, blendMode = blend) })
    }
    if (source == null && scene == CapsuleScene.INDICATOR) {
        Box(modifier.then(Modifier.background(solid, shape))) { content() }
        return
    }
    Surface(modifier.then(semantics).then(material), shape = shape, color = if (source == null) solid else Color.Transparent,
        contentColor = LocalContentColor.current, border = if (source == null) BorderStroke(Dp.Hairline, MaterialTheme.colorScheme.outlineVariant) else null,
        shadowElevation = if (source == null) elevation else 0.dp) {
        if (nestedShell) CompositionLocalProvider(LocalCapsuleBackdrop provides export, content = content) else content()
    }
}
