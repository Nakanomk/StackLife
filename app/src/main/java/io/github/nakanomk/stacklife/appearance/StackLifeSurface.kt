package io.github.nakanomk.stacklife.appearance

import android.os.Build
import androidx.annotation.RequiresApi

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
internal enum class CapsuleScene { NAVIGATION, ASSISTANT, CHROME, ACTION, COURSE, PANEL }

private val dockEdge = EdgeStroke(width = 1.dp, bevel = 2.dp, color = Color.White.copy(alpha = .12f),
    primary = EdgeLightSource(.5f, -.3f, -.05f), secondary = EdgeLightSource(.5f, .8f, -.5f, intensity = .4f), peak = EdgePeak.Dual)

@Composable
internal fun rememberGlassEdge(rotation: Int): State<EdgeStroke> {
    val angle = LocalGlassLight.current
    return remember(angle, rotation) { derivedStateOf {
        val r = Math.toRadians((angle.intValue + rotation).toDouble())
        val x = cos(r).toFloat()
        val y = sin(r).toFloat()
        dockEdge.copy(primary = dockEdge.primary.copy(x = .5f + x, y = .7f + y),
            secondary = dockEdge.secondary.copy(x = .5f - x, y = .7f - y))
    } }
}

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
        Surface(modifier.then(semantics), shape = shape, color = solid, contentColor = LocalContentColor.current, shadowElevation = elevation) {
            if (nestedShell) CompositionLocalProvider(LocalCapsuleBackdrop provides null, LocalParentShell provides false, content = content) else content()
        }
        return
    }
    val floating = scene == CapsuleScene.CHROME || (scene == CapsuleScene.NAVIGATION && shape == CircleShape)
    val elastic = LocalMotionEnabled.current && !LocalParentShell.current &&
        (floating || (scene == CapsuleScene.ACTION && interactionSource != null))
    OpticalSurface(scene, modifier.then(semantics), source, nestedShell, shape, tint, floating, elastic, content)
}

@RequiresApi(33)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpticalSurface(scene: CapsuleScene, modifier: Modifier, source: Backdrop, nestedShell: Boolean,
    shape: RoundedCornerShape, tint: Color, floating: Boolean, elastic: Boolean, content: @Composable () -> Unit) {
    val touch = if (elastic) remember { GlassTouch() } else null
    val transform = touch?.let { rememberGlassTouchLayer(it) }
    val export = if (nestedShell) rememberLayerBackdrop() else null
    val base = MaterialTheme.colorScheme.surface
    val dark = base.luminance() < .5f
    val course = scene == CapsuleScene.COURSE
    val panel = scene == CapsuleScene.PANEL
    val input = scene == CapsuleScene.ASSISTANT
    val inline = LocalParentShell.current && !course
    val hasLens = !inline && !input
    val wash = when {
        course -> tint.copy(alpha = .88f)
        panel -> base.copy(alpha = if (dark) .63f else .70f)
        input -> base.copy(alpha = .54f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .40f)
    }
    val blend = if (!course) BlendMode.SrcOver else if (dark) BlendMode.Screen else BlendMode.Multiply
    val blurDp = when (scene) {
        CapsuleScene.COURSE -> 1.dp
        CapsuleScene.ASSISTANT -> 10.dp
        CapsuleScene.PANEL -> 24.dp
        else -> 4.dp
    }
    val chromeDepth = if (panel) 12.dp else 24.dp
    val specular = if (hasLens && !panel && !course) rememberGlassEdge(-45) else null
    val shadow = if (inline || course) Modifier else Modifier.dropShadow(shape,
        remember(dark) { Shadow(radius = 10.dp, color = Color.Black, alpha = if (dark) .20f else .10f) })
    val material = Modifier.drawBackdrop(source, shape = { shape }, layerBlock = transform, effects = {
        if (!inline) {
            // A timetable cell is a flat sheet over registered period rules, not a dock droplet.
            // Keep a clear centre even in a skinny collision lane. A radial depth normal
            // would pull those rules vertically along the entire length of its side walls.
            val band = if (course) minOf(2.dp.toPx(), size.minDimension / 8f) else chromeDepth.toPx()
            val reach = if (course) band * .375f else band
            if (hasLens) padding = maxOf(padding, reach)
            colorControls(saturation = 1.5f)
            blur(blurDp.toPx())
            if (hasLens && band > 0f) dockLens(band, reach, depth = false, dispersion = 0f)
        }
    }, onDrawSurface = {
        if (tint.isSpecified && !course) {
            drawRect(tint, blendMode = BlendMode.Hue)
            drawRect(tint.copy(alpha = .75f))
        }
        drawRect(wash, blendMode = blend)
    }, onDrawFront = if (specular == null) null else {
        { drawEdgeStroke(shape, specular.value, .75f) }
    })
    val receiver = if (touch == null) modifier else modifier.observeGlassTouch(touch, ownsScrub = floating)
    Box(receiver, propagateMinConstraints = true) {
        Spacer(Modifier.matchParentSize().then(if (export == null) Modifier else Modifier.layerBackdrop(export))
            .then(shadow).then(material))
        val body = if (transform == null) Modifier else Modifier.graphicsLayer(block = transform)
        Box(if (floating) body else body.clip(shape), propagateMinConstraints = true) {
            if (export == null && touch == null) content()
            else CompositionLocalProvider(
                LocalCapsuleBackdrop provides if (floating && export != null) rememberCombinedBackdrop(source, export) else export ?: source,
                LocalParentShell provides (export != null || LocalParentShell.current),
                LocalRippleConfiguration provides if (touch != null && LocalInputModeManager.current.inputMode == InputMode.Touch) null else LocalRippleConfiguration.current,
                content = content)
        }
    }
}
