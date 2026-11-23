package io.github.nakanomk.stacklife.appearance

import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R

import android.os.Build
import androidx.annotation.RequiresApi

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
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
internal val LocalParentShell = compositionLocalOf { false }
internal enum class CapsuleScene { NAVIGATION, ASSISTANT, CHROME, ACTION, COURSE, PANEL, TRAY, FROST, INSET }

private val dockEdge = EdgeStroke(width = 1.dp, bevel = 2.dp, color = Color.White.copy(alpha = .12f),
    primary = EdgeLightSource(.5f, -.3f, -.05f), secondary = EdgeLightSource(.5f, .8f, -.5f, intensity = .4f), peak = EdgePeak.Dual)

@Composable
internal fun rememberGlassEdge(rotation: Int): State<EdgeStroke> {
    val angle = LocalGlassLight.current
    return remember(angle, rotation) { derivedStateOf {
        val r = Math.toRadians((angle.intValue + rotation).toDouble())
        val x = cos(r).toFloat()
        val y = sin(r).toFloat()
        dockEdge.copy(primary = dockEdge.primary.copy(x = .5f + x, y = .7f + y))
    } }
}

@Stable
internal class PlannerBackdrop(val layer: GraphicsLayer, private val base: Color, private val below: Backdrop?,
    private val windowFrame: MutableIntState?) : Backdrop {
    override val isCoordinatesDependent = true
    var origin: Offset by mutableStateOf(Offset.Unspecified)

    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?, downscaleFactor: Float) {
        windowFrame?.intValue // Subscribe this draw, including nested filters, to the donor window's frame.
        if (below == null) drawRect(base) else with(below) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        if (coordinates == null || !coordinates.isAttached || !origin.isSpecified) return
        val target = coordinates.localToScreen(Offset.Zero)
        if (!target.isSpecified) return
        drawAt(origin - target, downscaleFactor)
    }

    private fun DrawScope.drawAt(offset: Offset, downscaleFactor: Float) {
        val factor = 1f / downscaleFactor
        withTransform({
            translate(offset.x * factor, offset.y * factor)
            if (factor != 1f) scale(factor, factor, Offset.Zero)
        }) { drawLayer(layer) }
    }

    fun relativeTo(receiver: State<Offset>): Backdrop? {
        if (windowFrame != null) return null
        val parent = when {
            below == null -> null
            !below.isCoordinatesDependent -> below
            below is PlannerBackdrop -> below.relativeTo(receiver) ?: return null
            else -> return null
        }
        val offset = derivedStateOf(structuralEqualityPolicy()) {
            val at = receiver.value
            if (origin.isSpecified && at.isSpecified) origin - at else Offset.Unspecified
        }
        return object : Backdrop {
            override val isCoordinatesDependent = false
            override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?,
                layerBlock: (GraphicsLayerScope.() -> Unit)?, downscaleFactor: Float) {
                if (parent == null) drawRect(base) else with(parent) { drawBackdrop(density, null, layerBlock, downscaleFactor) }
                val delta = offset.value
                if (delta.isSpecified) drawAt(delta, downscaleFactor)
            }
        }
    }
}

internal class GlassReader(val backdrop: Backdrop, val placement: Modifier)

/** Miuix normally invalidates on *every* global placement. When a pager moves its
 * toolbar and donor together their sampling rect has not moved. Observe only the
 * difference; the RenderNode continues to supply live scroll/content changes. */
@Composable
internal fun rememberGlassReader(source: Backdrop): GlassReader = remember(source) {
    if (source is PlannerBackdrop) {
        val position = mutableStateOf(Offset.Unspecified)
        val reader = source.relativeTo(position)
        if (reader != null) return@remember GlassReader(reader, Modifier.onGloballyPositioned {
            position.value = it.localToScreen(Offset.Zero)
        })
    }
    GlassReader(source, Modifier)
}

@Composable
internal fun rememberPlannerBackdrop(enabled: Boolean = true): PlannerBackdrop? {
    val a = LocalAppearance.current
    if (!enabled || !LocalPlannerGlassAllowed.current || !a.glass || glassUnavailableReason(a) != null) return null
    val base = MaterialTheme.colorScheme.surface
    val layer = rememberGraphicsLayer()
    val below = LocalCapsuleBackdrop.current
    return remember(layer, base, below) { PlannerBackdrop(layer, base, below, null) }
}

internal fun Modifier.plannerBackdrop(source: PlannerBackdrop?, drawInPlace: Boolean = true): Modifier {
    if (source == null) return this
    return onGloballyPositioned { source.origin = it.localToScreen(Offset.Zero) }.drawWithContent {
        drawRecordedBackdrop(source, drawInPlace)
    }
}

internal fun ContentDrawScope.drawRecordedBackdrop(source: PlannerBackdrop, drawInPlace: Boolean = true) {
    source.layer.record { this@drawRecordedBackdrop.drawContent() }
    if (drawInPlace) drawLayer(source.layer)
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
    interactionSource: InteractionSource? = null, solidBorder: BorderStroke? = null, materialBounds: Modifier = Modifier,
    content: @Composable () -> Unit) {
    val a = LocalAppearance.current
    val source = LocalCapsuleBackdrop.current?.takeIf { LocalPlannerGlassAllowed.current && a.glass && glassUnavailableReason(a) == null && when (scene) {
        CapsuleScene.NAVIGATION -> a.navGlass
        CapsuleScene.ASSISTANT -> a.assistantGlass
        else -> true
    } }
    val nestedShell = scene == CapsuleScene.PANEL || scene == CapsuleScene.TRAY || scene == CapsuleScene.CHROME || scene == CapsuleScene.NAVIGATION
    val semantics = if (scene == CapsuleScene.NAVIGATION || scene == CapsuleScene.ASSISTANT) {
        val description = stringResource(if (source == null) R.string.ui_solid_capsule else R.string.ui_glass_capsule)
        Modifier.semantics { stateDescription = description }
    } else Modifier
    if (Build.VERSION.SDK_INT < 33 || source == null) {
        Surface(modifier.then(semantics), shape = shape, color = solid, contentColor = LocalContentColor.current,
            shadowElevation = elevation, border = solidBorder) {
            if (nestedShell) CompositionLocalProvider(LocalCapsuleBackdrop provides null, LocalParentShell provides false, content = content) else content()
        }
        return
    }
    val floating = scene == CapsuleScene.CHROME || (scene == CapsuleScene.NAVIGATION && shape == CircleShape)
    val elastic = LocalMotionEnabled.current && !LocalParentShell.current &&
        (floating || (scene == CapsuleScene.ACTION && interactionSource != null))
    val courseContact = if (scene == CapsuleScene.COURSE && interactionSource != null && LocalMotionEnabled.current) {
        val pressed by interactionSource.collectIsPressedAsState()
        val contact = animateFloatAsState(if (pressed) 1f else 0f, spring(.8f, 550f), label = "course contact")
        Modifier.graphicsLayer {
            val scale = 1f + contact.value * 16.dp.toPx() / size.maxDimension.coerceAtLeast(1f)
            scaleX = scale
            scaleY = scale
        }
    } else Modifier
    OpticalSurface(scene, modifier.then(semantics).then(courseContact), source, nestedShell, shape, tint, floating, elastic, materialBounds, content)
}

@RequiresApi(33)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpticalSurface(scene: CapsuleScene, modifier: Modifier, source: Backdrop, nestedShell: Boolean,
    shape: RoundedCornerShape, tint: Color, floating: Boolean, elastic: Boolean, materialBounds: Modifier,
    content: @Composable () -> Unit) {
    val touch = if (elastic) remember { GlassTouch() } else null
    val transform = touch?.let { rememberGlassTouchLayer(it) }
    val panel = scene == CapsuleScene.PANEL || scene == CapsuleScene.TRAY
    val panelPlane = if (panel) rememberPlannerBackdrop() else null
    val export = if (nestedShell && !panel) rememberLayerBackdrop() else null
    val base = MaterialTheme.colorScheme.surface
    val dark = base.luminance() < .5f
    val course = scene == CapsuleScene.COURSE
    val input = scene == CapsuleScene.ASSISTANT
    val frost = scene == CapsuleScene.FROST
    // An inset control reads the parent shell's already-rendered material, never its own labels.
    val inset = scene == CapsuleScene.INSET
    val inline = LocalParentShell.current && !course && !inset
    val hasLens = !inline && !frost
    val wash = when {
        inset && tint.isSpecified -> tint
        course -> tint.copy(alpha = .88f)
        panel -> base.copy(alpha = if (dark) .63f else .70f)
        input -> (if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else base).copy(alpha = .54f)
        frost -> if (tint.isSpecified) tint.copy(alpha = .36f)
            else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .42f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .40f)
    }
    val blend = if (!course) BlendMode.SrcOver else if (dark) BlendMode.Screen else BlendMode.Multiply
    val blurDp = when (scene) {
        CapsuleScene.INSET -> 0.dp
        CapsuleScene.COURSE -> 4.dp
        CapsuleScene.ASSISTANT, CapsuleScene.FROST -> 10.dp
        CapsuleScene.PANEL, CapsuleScene.TRAY -> 24.dp
        else -> 4.dp
    }
    val specular = if (input || (hasLens && scene != CapsuleScene.PANEL)) rememberGlassEdge(-45) else null
    val shadow = if (inline || frost || inset) Modifier else Modifier.dropShadow(shape,
        remember(dark, course) { Shadow(radius = if (course) 4.dp else 10.dp, color = Color.Black, alpha = if (dark) .20f else .10f) })
    val reader = rememberGlassReader(source)
    val material = Modifier.drawBackdrop(reader.backdrop, shape = { shape }, layerBlock = transform, effects = {
        if (!inline || frost) {
            if (!inline) colorControls(saturation = 1.5f)
            // Both the blur and the undispersed lens only resample their input.
            // A constant paper is invariant; photos, recorded rows and exported
            // shells are not. Keep the normal saturation, tint, shadow and rim.
            if (source is UniformPagePaper) return@drawBackdrop
            if (hasLens) {
                val depth = when {
                    inset -> minOf(8.dp.toPx(), size.minDimension * .35f)
                    course -> 24.dp.toPx()
                    panel -> 12.dp.toPx()
                    else -> 24.dp.toPx() * dockGeometryScale(size.height)
                }
                padding = maxOf(padding, depth)
                if (blurDp > 0.dp) blur(blurDp.toPx())
                dockLens(depth, depth, depth = course, dispersion = 0f)
            } else blur(blurDp.toPx())
        }
    }, onDrawSurface = {
        if (tint.isSpecified && !course && !frost && !inset) {
            drawRect(tint, blendMode = BlendMode.Hue)
            drawRect(tint.copy(alpha = .75f))
        }
        drawRect(wash, blendMode = blend)
    })
    // A front callback forces Miuix to composite the entire lens into another
    // offscreen buffer. The highlight needs only a display list inside the same clip.
    val rim = if (specular == null) Modifier else Modifier.graphicsLayer().drawBehind {
        val scale = if (input) 1f else dockGeometryScale(size.minDimension)
        drawEdgeStroke(shape, specular.value, .75f * scale)
    }
    val receiver = if (touch == null) modifier else modifier.observeGlassTouch(touch, ownsScrub = floating)
    Box(receiver, propagateMinConstraints = true) {
        Spacer(Modifier.matchParentSize().then(materialBounds)
            .then(if (export == null) Modifier.plannerBackdrop(panelPlane) else Modifier.layerBackdrop(export).graphicsLayer())
            .then(shadow).then(material).then(reader.placement).then(rim))
        val body = if (transform == null) Modifier else Modifier.graphicsLayer(block = transform)
        Box(if (floating) body else body.clip(shape), propagateMinConstraints = true) {
            if (export == null && panelPlane == null && touch == null) content()
            else CompositionLocalProvider(
                LocalCapsuleBackdrop provides if (floating && export != null) rememberCombinedBackdrop(source, export) else export ?: panelPlane ?: source,
                LocalParentShell provides (nestedShell || LocalParentShell.current),
                LocalRippleConfiguration provides if (touch != null && LocalInputModeManager.current.inputMode == InputMode.Touch) null else LocalRippleConfiguration.current,
                content = content)
        }
    }
}

private fun Density.dockGeometryScale(pixels: Float): Float = (pixels / 64.dp.toPx()).coerceIn(0f, 1f)
