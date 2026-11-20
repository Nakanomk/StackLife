package io.github.nakanomk.stacklife.appearance

import android.os.Build
import androidx.annotation.RequiresApi

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.*
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import androidx.compose.ui.graphics.shadow.Shadow
import kotlin.math.cos
import kotlin.math.sin
import top.yukonga.miuix.kmp.material.EdgeLightSource
import top.yukonga.miuix.kmp.material.EdgePeak
import top.yukonga.miuix.kmp.material.EdgeStroke
import top.yukonga.miuix.kmp.material.drawEdgeStroke

internal val LocalPlannerGlassAllowed = staticCompositionLocalOf { false }
internal val LocalCapsuleBackdrop = compositionLocalOf<Backdrop?> { null }
private val LocalParentShell = compositionLocalOf { false }
internal enum class CapsuleScene { NAVIGATION, ASSISTANT, CHROME, ACTION, COURSE, INDICATOR, PANEL }

private val dockEdge = EdgeStroke(width = 1.dp, bevel = 2.dp, color = Color.White.copy(alpha = .12f),
    primary = EdgeLightSource(.5f, -.3f, -.05f), secondary = EdgeLightSource(.5f, .8f, -.5f, intensity = .4f), peak = EdgePeak.Dual)

@Stable
internal class PlannerBackdrop(val layer: GraphicsLayer, private val base: Color, private val below: Backdrop?) : Backdrop {
    override val isCoordinatesDependent = true
    var origin: Offset by mutableStateOf(Offset.Unspecified)

    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?, downscaleFactor: Float) {
        if (below == null) drawRect(base) else with(below) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        if (coordinates == null || !coordinates.isAttached || !origin.isSpecified) return
        val target = coordinates.localToScreen(Offset.Zero)
        if (!target.isSpecified) return
        val factor = 1f / downscaleFactor
        withTransform({
            translate((origin.x - target.x) * factor, (origin.y - target.y) * factor)
            if (factor != 1f) scale(factor, factor, Offset.Zero)
        }) { drawLayer(layer) }
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
    val semantics = if (scene == CapsuleScene.NAVIGATION || scene == CapsuleScene.ASSISTANT) Modifier.semantics {
        stateDescription = if (source == null) "实色胶囊" else "折射胶囊"
    } else Modifier
    if (Build.VERSION.SDK_INT < 33 || source == null) {
        if (scene == CapsuleScene.INDICATOR) Box(modifier.background(solid, shape)) { content() }
        else Surface(modifier.then(semantics), shape = shape, color = solid, contentColor = LocalContentColor.current, shadowElevation = elevation) {
            if (nestedShell) CompositionLocalProvider(LocalCapsuleBackdrop provides null, LocalParentShell provides false, content = content) else content()
        }
        return
    }
    OpticalSurface(scene, modifier.then(semantics), source, nestedShell, shape, tint, interactionSource, content)
}

@RequiresApi(33)
@Composable
private fun OpticalSurface(scene: CapsuleScene, modifier: Modifier, source: Backdrop, nestedShell: Boolean,
    shape: RoundedCornerShape, tint: Color, interactionSource: InteractionSource?, content: @Composable () -> Unit) {
    val export = if (nestedShell) rememberLayerBackdrop() else null
    val base = MaterialTheme.colorScheme.surface
    val dark = base.luminance() < .5f
    val course = scene == CapsuleScene.COURSE
    val indicator = scene == CapsuleScene.INDICATOR
    val panel = scene == CapsuleScene.PANEL
    val input = scene == CapsuleScene.ASSISTANT
    val inline = LocalParentShell.current && !indicator
    val hasLens = !inline && !input
    val wash = when {
        course -> tint.copy(alpha = .88f)
        indicator -> (if (dark) Color.White else Color.Black).copy(alpha = .10f)
        panel -> base.copy(alpha = if (dark) .63f else .70f)
        input -> base.copy(alpha = .54f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .40f)
    }
    val blend = if (!course) BlendMode.SrcOver else if (dark) BlendMode.Screen else BlendMode.Multiply
    val blurDp = when (scene) {
        CapsuleScene.COURSE, CapsuleScene.INDICATOR -> 1.dp
        CapsuleScene.ASSISTANT -> 10.dp
        CapsuleScene.PANEL -> 24.dp
        else -> 4.dp
    }
    val depth = when { indicator -> 10.dp; panel -> 12.dp; else -> 24.dp }
    val displacement = if (indicator) 14.dp else depth
    val angle = LocalGlassLight.current
    val specular = remember(angle) { derivedStateOf {
        val r = Math.toRadians((angle.intValue - 45).toDouble())
        dockEdge.copy(primary = dockEdge.primary.copy(x = .5f + cos(r).toFloat(), y = .7f + sin(r).toFloat()))
    } }
    val shadow = if (inline || indicator) Modifier else Modifier.dropShadow(shape,
        remember(dark, course) { Shadow(radius = if (course) 4.dp else 10.dp, color = Color.Black, alpha = if (dark) .20f else .10f) })
    val press = if (!inline && interactionSource != null && LocalMotionEnabled.current) {
        val down by interactionSource.collectIsPressedAsState()
        val progress = animateFloatAsState(if (down) 1f else 0f, spring(dampingRatio = .8f, stiffness = 550f), label = "glass press")
        Modifier.graphicsLayer { val s = 1f + progress.value * 16.dp.toPx() / size.maxDimension.coerceAtLeast(1f); scaleX = s; scaleY = s }
    } else Modifier
    val material = Modifier.drawBackdrop(source, shape = { shape }, effects = {
        if (!inline) {
            if (hasLens) padding = maxOf(padding, displacement.toPx())
            colorControls(saturation = 1.5f)
            blur(blurDp.toPx())
            if (hasLens) dockLens(depth.toPx(), displacement.toPx(), depth = course, dispersion = if (indicator) .5f else 0f)
        }
    }, onDrawSurface = {
        if (tint.isSpecified && !course && !indicator) {
            drawRect(tint, blendMode = BlendMode.Hue)
            drawRect(tint.copy(alpha = .75f))
        }
        drawRect(wash, blendMode = blend)
    }, onDrawFront = if (!hasLens || indicator || panel) null else {
        { drawEdgeStroke(shape, specular.value, .75f) }
    })
    Surface(modifier.then(press).then(shadow), shape = shape, color = Color.Transparent, contentColor = LocalContentColor.current) {
        Box {
            Spacer(Modifier.matchParentSize().then(if (export != null) Modifier.layerBackdrop(export) else Modifier).then(material))
            if (export != null) CompositionLocalProvider(LocalCapsuleBackdrop provides export, LocalParentShell provides true, content = content) else content()
        }
    }
}
