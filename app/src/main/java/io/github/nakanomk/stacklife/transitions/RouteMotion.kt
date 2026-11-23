package io.github.nakanomk.stacklife.transitions

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.navigation3.ui.LocalNavAnimatedContentScope

internal fun AnimatedContentTransitionScope<*>.sceneMotion(enabled: Boolean) = EnterTransition.None togetherWith
    if (enabled) ExitTransition.KeepUntilTransitionsFinished else ExitTransition.None

internal val LocalFrontEntry = compositionLocalOf { true }

internal val LocalRouteOrigins = staticCompositionLocalOf<RouteOrigins?> { null }
internal val LocalRouteOriginsEnabled = compositionLocalOf { true }
internal val LocalRouteOwner = compositionLocalOf<String?> { null }

@Composable
internal fun AnimatedVisibilityScope.routeOpen(enabled: Boolean): State<Float> {
    val owner = LocalRouteOwner.current
    val origins = LocalRouteOrigins.current
    val exiting = enabled && transition.currentState == EnterExitState.Visible && transition.targetState != EnterExitState.Visible
    // NavDisplay 1.2.2 advances normal animateTo linearly, but seeks gesture progress
    // directly and eases its own cancellation. A toolbar pop removes the entry first;
    // a predictive pop starts while it is still on the stack. Latch that distinction
    // for the whole exit, including release, so committing cannot warp a held frame.
    val scrubbing = remember(transition, owner, exiting) { exiting && owner != null && origins?.isPopped(owner) == false }
    return containerOpen(enabled, if (scrubbing) LinearEasing else FastOutSlowInEasing)
}

internal class RouteLaunch(val modifier: Modifier, private val host: RouteOrigins?, private val site: OriginSite) {
    fun open(action: () -> Unit) { host?.chosen = site; action() }
}

internal class RouteOrigins : ContainerOrigins() {
    var chosen: OriginSite? = null
    private val removed = mutableSetOf<String>()
    private val levels = mutableStateMapOf<String, Int>()

    fun push(key: String, from: String) {
        removed.remove(key)
        capture(key, from, chosen)
        chosen = null
    }
    fun pop(key: String) { removed.add(key) }
    fun isPopped(key: String) = key in removed

    @Composable
    internal fun Entry(key: String, depth: Int, stackTop: Int, from: String?, animate: Boolean,
        ordinary: Boolean, content: @Composable () -> Unit) {
        DisposableEffect(key) {
            levels[key] = depth
            onDispose { levels.remove(key); if (removed.remove(key)) links.remove(key) }
        }
        val front = depth >= stackTop && levels.values.none { it > depth }
        CompositionLocalProvider(LocalRouteOwner provides key, LocalEntryDepth provides depth, LocalFrontEntry provides front) {
            EntryWindow(front) {
                if (ordinary) {
                    val visibility = LocalNavAnimatedContentScope.current
                    val moving = animate && front && visibility.transition.currentState != visibility.transition.targetState
                    val open = visibility.routeOpen(animate && front)
                    val link = links[key]?.takeIf { it.site.owner == from }
                    val returning = visibility.transition.currentState == EnterExitState.Visible
                    val driver = pairVisibility(this, link, open, moving, returning)
                    ContainerPage(this, link, driver?.page ?: visibility, LocalContainerScope.current,
                        open, driver?.open ?: open, returning, front, moving,
                        Modifier.fillMaxSize().testTag("route:$key"), content)
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
    val site = OriginSite(owner, key, currentCompositeKeyHashCode)
    val anchor = remember(site, density, shape) { ContainerAnchor(site, shape, density) }
    val modifier = if (host == null) Modifier else Modifier.containerSource(host, anchor)
    return remember(modifier, host, site) { RouteLaunch(modifier, host, site) }
}

@Composable
internal fun Modifier.routeOrigin(key: String, shape: Shape = RectangleShape): Modifier = then(rememberRouteLaunch(key, shape).modifier)
