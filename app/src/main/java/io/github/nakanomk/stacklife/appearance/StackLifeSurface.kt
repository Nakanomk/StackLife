package io.github.nakanomk.stacklife.appearance

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.*
import dev.chrisbanes.haze.*
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

// Opt in per trusted route, never at the window or navigation-host level.
internal val LocalPlannerGlassAllowed = staticCompositionLocalOf { false }
internal val LocalNavigationGlass = compositionLocalOf<HazeState?> { null }

private class CapsuleInk(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
    var visible by mutableStateOf(Rect.Zero)
}
private class AssistantLayers(val view: View) { val foregrounds = mutableStateListOf<CapsuleInk>() }
private val LocalAssistantLayers = staticCompositionLocalOf<AssistantLayers?> { null }

@Composable
private fun glassModifier(source: HazeState): Modifier {
    val base = MaterialTheme.colorScheme.surface
    val style = remember(base) { GlassStyle.regular.then { backgroundColor(base); shape(CircleShape) } }
    val input = remember(source) { HazeInput.Sources(source, retention = HazeSourceRetention.ClearWhenUnavailable) }
    return Modifier.hazeGlass(input = input, style = style)
}

/** The original button retains its layout, semantics, hit testing, focus and ripple. Only its
 * drawing is lifted above the page source, so a capsule can never sample its own foreground. */
@Composable
internal fun AssistantGlassPage(content: @Composable () -> Unit) {
    val a = LocalAppearance.current
    val eligible = LocalPlannerGlassAllowed.current && a.glass && a.assistantGlass && glassUnavailableReason(a) == null
    val view = LocalView.current
    val layers = if (eligible) remember(view) { AssistantLayers(view) } else null
    val source = if (layers != null && layers.foregrounds.isNotEmpty()) rememberHazeState() else null
    // Keep this composition (and its page/list state) at the same slot when toggling glass.
    CompositionLocalProvider(LocalAssistantLayers provides layers) {
        Box(Modifier.fillMaxSize()) {
            Box(if (source == null) Modifier.fillMaxSize() else Modifier.fillMaxSize().hazeSource(source)) { content() }
            if (source != null && layers != null) {
                for (ink in layers.foregrounds) key(ink) { AssistantOptics(ink, source) }
            }
        }
    }
}

@Composable
private fun AssistantOptics(ink: CapsuleInk, source: HazeState) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val optics = glassModifier(source)
    Layout(modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }, content = {
        Canvas(Modifier.drawWithContent {
            val visible = ink.visible.translate(-ink.origin)
            if (!visible.isEmpty) clipRect(visible.left, visible.top, visible.right, visible.bottom) { this@drawWithContent.drawContent() }
        }.then(optics)) { drawLayer(ink.layer) }
    }) { children, constraints ->
        val size = ink.size
        val child = children.single().measure(Constraints.fixed(size.width, size.height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (size != IntSize.Zero && !ink.visible.isEmpty) {
                val offset = (ink.origin - origin).round()
                child.place(offset.x, offset.y)
            }
        }
    }
}

internal enum class CapsuleScene { NAVIGATION, ASSISTANT }

@Composable
internal fun StackLifeSurface(scene: CapsuleScene, modifier: Modifier, solid: Color, elevation: Dp = 0.dp,
    content: @Composable () -> Unit) {
    val navSource = if (scene == CapsuleScene.NAVIGATION) LocalNavigationGlass.current else null
    val view = LocalView.current
    val host = if (scene == CapsuleScene.ASSISTANT) LocalAssistantLayers.current?.takeIf { it.view === view } else null
    val glass = navSource != null || host != null
    val material = when {
        navSource != null -> glassModifier(navSource)
        host != null -> {
            val layer = rememberGraphicsLayer()
            val ink = remember(layer) { CapsuleInk(layer) }
            DisposableEffect(host, ink) {
                host.foregrounds.add(ink)
                onDispose { host.foregrounds.remove(ink) }
            }
            Modifier.onGloballyPositioned {
                ink.origin = it.positionInRoot(); ink.size = it.size; ink.visible = it.boundsInRoot()
            }.drawWithContent { layer.record { this@drawWithContent.drawContent() } }
        }
        else -> Modifier
    }
    Surface(modifier.semantics { stateDescription = if (glass) "折射胶囊" else "实色胶囊" }.then(material),
        shape = CircleShape, color = if (glass) Color.Transparent else solid,
        shadowElevation = if (glass) 0.dp else elevation, content = content)
}
