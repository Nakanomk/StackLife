package io.github.nakanomk.stacklife.transitions

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import kotlin.math.roundToInt

internal fun AnimatedContentTransitionScope<*>.sceneMotion(enabled: Boolean) = EnterTransition.None togetherWith
    if (enabled) ExitTransition.KeepUntilTransitionsFinished else ExitTransition.None

internal val LocalRouteOrigins = staticCompositionLocalOf<RouteOrigins?> { null }
internal val LocalRouteOriginsEnabled = compositionLocalOf { true }
private val LocalRouteOwner = compositionLocalOf<String?> { null }

private data class ControlBounds(val bounds: Rect, val radius: Float, val owner: String?)

internal class RouteLaunch internal constructor(private val key: String, private val host: RouteOrigins?,
    internal val owner: String?, private val shape: Shape, private val density: Density) {
    private var coordinates: LayoutCoordinates? = null
    val modifier: Modifier = if (host == null) Modifier else Modifier.onGloballyPositioned { coordinates = it }

    internal fun bounds(): Rect? {
        val c = coordinates?.takeIf { it.isAttached } ?: return null
        val clipped = c.boundsInWindow().takeUnless { it.isEmpty } ?: return null
        val screen = c.localToScreen(Offset.Zero)
        return if (screen.x.isFinite() && screen.y.isFinite()) clipped.translate(screen - c.localToWindow(Offset.Zero)) else null
    }

    internal fun radius(bounds: Rect) = (shape as? CornerBasedShape)?.topStart?.toPx(bounds.size, density)
        ?.coerceAtMost(bounds.minDimension / 2f) ?: 0f

    fun open(action: () -> Unit) {
        val bounds = bounds()
        if (bounds != null) host?.select(key, bounds, radius(bounds), owner)
        action()
    }
}

internal class RouteOrigins {
    private val controls = mutableMapOf<String, MutableSet<RouteLaunch>>()
    private var selected: Pair<String, ControlBounds>? = null
    private val launches = mutableMapOf<String, ControlBounds>()
    private val levels = mutableStateMapOf<String, Int>()
    internal var window by mutableStateOf(Offset.Zero)

    internal fun attach(key: String, control: RouteLaunch) {
        controls.getOrPut(key) { mutableSetOf() }.add(control)
    }
    internal fun detach(key: String, control: RouteLaunch) {
        controls[key]?.let { it.remove(control); if (it.isEmpty()) controls.remove(key) }
    }
    internal fun select(key: String, bounds: Rect, radius: Float, owner: String?) {
        selected = key to ControlBounds(bounds, radius, owner)
    }
    private fun live(key: String, owner: String?): ControlBounds? {
        for (control in controls[key].orEmpty()) if (control.owner == owner) {
            val rect = control.bounds() ?: continue
            return ControlBounds(rect, control.radius(rect), owner)
        }
        return null
    }
    fun push(key: String, from: String) {
        val control = selected?.takeIf { it.first == key }?.second ?: live(key, from)
        selected = null
        if (control == null) launches.remove(key) else launches[key] = control
    }
    fun pop(key: String) { launches.remove(key) }

    @Composable
    internal fun Entry(key: String, depth: Int, stackTop: Int, from: String?, animate: Boolean,
        ordinary: Boolean, content: @Composable () -> Unit) {
        DisposableEffect(key) { levels[key] = depth; onDispose { levels.remove(key) } }
        CompositionLocalProvider(LocalRouteOwner provides key) {
            if (ordinary) {
                val start = remember(key) { launches[key] }
                val front = depth >= stackTop && levels.values.none { it > depth }
                val transition = LocalNavAnimatedContentScope.current.transition
                // A restored entry has no cached coordinates. On return use only a live control
                // on the actual parent; never substitute a control on a different page.
                val leaving = transition.currentState == EnterExitState.Visible
                val track = if (leaving && front) live(key, if (start != null) start.owner else from) ?: start else start
                val progress = transition.animateFloat(transitionSpec = { if (animate && front && track != null) tween(320) else snap() }, label = "route:$key") {
                    if (animate && front && track != null && it != EnterExitState.Visible) 0f else 1f
                }
                RouteSurface(key, track, progress, content)
            } else content()
        }
    }
}

@Composable
internal fun rememberRouteLaunch(key: String, shape: Shape = RectangleShape): RouteLaunch {
    val host = LocalRouteOrigins.current.takeIf { LocalRouteOriginsEnabled.current }
    val owner = LocalRouteOwner.current
    val density = LocalDensity.current
    val control = remember(host, key, owner, shape, density) { RouteLaunch(key, host, owner, shape, density) }
    DisposableEffect(host, control) {
        host?.attach(key, control)
        onDispose { host?.detach(key, control) }
    }
    return control
}

@Composable
internal fun Modifier.routeOrigin(key: String, shape: Shape = RectangleShape): Modifier = then(rememberRouteLaunch(key, shape).modifier)

@Composable
private fun RouteSurface(key: String, start: ControlBounds?, progress: State<Float>, content: @Composable () -> Unit) {
    val registry = LocalRouteOrigins.current
    val color = MaterialTheme.colorScheme.surface
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val full = constraints
        Box(Modifier.layout { child, c ->
            val end = Rect(0f, 0f, c.maxWidth.toFloat(), c.maxHeight.toFloat())
            val source = start?.bounds?.translate(-(registry?.window ?: Offset.Zero))?.intersect(end)
            val rect = if (source == null || source.isEmpty || progress.value == 1f) end else lerp(source, end, progress.value)
            val left = rect.left.roundToInt(); val top = rect.top.roundToInt()
            val frame = child.measure(Constraints.fixed(rect.right.roundToInt() - left, rect.bottom.roundToInt() - top))
            layout(c.maxWidth, c.maxHeight) { frame.place(left, top) }
        }.testTag("route:$key").graphicsLayer {
            val radius = (start?.radius ?: 0f) * (1f - progress.value)
            shape = if (radius > 0f) RoundedCornerShape(radius.toDp()) else RectangleShape
            clip = progress.value < 1f
        }.background(color)) {
            Box(Modifier.layout { child, pane ->
                val page = child.measure(full)
                layout(pane.maxWidth, pane.maxHeight) { page.place(0, 0) }
            }.graphicsLayer { alpha = ((progress.value - .12f) / .55f).coerceIn(0f, 1f) }) { content() }
        }
    }
}
