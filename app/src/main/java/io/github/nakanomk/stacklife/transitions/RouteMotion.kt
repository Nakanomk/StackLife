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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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

// Only the compact landing region yields its paper to the live launcher. Use spatial
// progress, not an independent timer: a held/reversed Back produces the exact same pixels.
internal fun landingAlpha(open: Float, compactFraction: Float = .045f): Float {
    val t = (open / compactFraction).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal val LocalRouteOrigins = staticCompositionLocalOf<RouteOrigins?> { null }
internal val LocalRouteOriginsEnabled = compositionLocalOf { true }
private val LocalRouteOwner = compositionLocalOf<String?> { null }

private data class ControlBounds(val bounds: Rect, val radius: Float, val owner: String?)

internal class RouteLaunch internal constructor(private val key: String, private val host: RouteOrigins?,
    internal val owner: String?, private val shape: Shape, private val density: Density) {
    private var coordinates: LayoutCoordinates? = null
    private var screenBounds by mutableStateOf<Rect?>(null)
    val modifier: Modifier = if (host == null) Modifier else Modifier.onGloballyPositioned {
        coordinates = it
        screenBounds = screenRect(it)
    }

    internal fun bounds() = screenBounds.takeIf { coordinates?.isAttached == true }

    private fun screenRect(c: LayoutCoordinates): Rect? {
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
    private val controls = mutableStateMapOf<String, List<RouteLaunch>>()
    private var selected: Pair<String, ControlBounds>? = null
    private val launches = mutableMapOf<String, ControlBounds>()
    private val levels = mutableStateMapOf<String, Int>()
    private var opening by mutableStateOf<String?>(null)
    internal var window by mutableStateOf(Offset.Zero)

    internal fun attach(key: String, control: RouteLaunch) {
        controls[key] = controls[key].orEmpty() + control
    }
    internal fun detach(key: String, control: RouteLaunch) {
        val remaining = controls[key]?.minus(control) ?: return
        if (remaining.isEmpty()) controls.remove(key) else controls[key] = remaining
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
        opening = key
        val control = selected?.takeIf { it.first == key }?.second ?: live(key, from)
        selected = null
        if (control == null) launches.remove(key) else launches[key] = control
    }
    fun pop(key: String) { opening = null; launches.remove(key) }

    @Composable
    internal fun Entry(key: String, depth: Int, stackTop: Int, from: String?, animate: Boolean,
        ordinary: Boolean, content: @Composable () -> Unit) {
        DisposableEffect(key) { levels[key] = depth; onDispose { levels.remove(key) } }
        val front = depth >= stackTop && levels.values.none { it > depth }
        CompositionLocalProvider(LocalRouteOwner provides key) {
            EntryWindow(front) {
                if (ordinary) {
                    val start = remember(key) { launches[key] }
                    val transition = LocalNavAnimatedContentScope.current.transition
                    val returning = transition.currentState != EnterExitState.PreEnter
                    val moves = animate && front && (returning || opening == key)
                    val track = if (!moves) null else if (returning) {
                        // The obscured page is composed and placed again on pop. Observe that
                        // placement, not last push's rectangle (or a control on another page).
                        live(key, if (start != null) start.owner else from)
                    } else start
                    val progress = transition.animateFloat(transitionSpec = { if (animate && front) tween(320) else snap() }, label = "route:$key") {
                        if (it == EnterExitState.Visible) 1f else 0f
                    }
                    RouteSurface(key, track, progress, returning, content)
                } else content()
            }
        }
    }
}

/** An editor's keyboard belongs to that editor, not to the page materializing behind
 * it during Back. Keep real bars/cutouts; consume only the extra IME clearance. At commit
 * the IME can outlive the departing scene, so wait for this page to own focus again. */
@Composable
private fun EntryWindow(front: Boolean, content: @Composable () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    var covered by remember { mutableStateOf(false) }
    SideEffect {
        if (!front) covered = true else if (hasFocus) covered = false
    }
    val keyboard = if (front && (!covered || hasFocus)) Modifier else {
        val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        Modifier.consumeWindowInsets(WindowInsets.ime.exclude(bars))
    }
    Box(Modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }.focusGroup().then(keyboard),
        propagateMinConstraints = true) { content() }
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
private fun RouteSurface(key: String, start: ControlBounds?, progress: State<Float>, returning: Boolean, content: @Composable () -> Unit) {
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
            val open = if (start == null) 1f else progress.value
            val radius = (start?.radius ?: 0f) * (1f - open)
            shape = if (radius > 0f) RoundedCornerShape(radius.toDp()) else RectangleShape
            clip = open < 1f
            alpha = if (returning) landingAlpha(open) else 1f
        }.background(color)) {
            Box(Modifier.layout { child, pane ->
                val page = child.measure(full)
                layout(pane.maxWidth, pane.maxHeight) { page.place(0, 0) }
            }.graphicsLayer { alpha = if (start == null) 1f else ((progress.value - .12f) / .55f).coerceIn(0f, 1f) }) { content() }
        }
    }
}
