package io.github.nakanomk.stacklife.transitions

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics

internal val LocalFrontEntry = compositionLocalOf { true }

internal val LocalRouteOrigins = staticCompositionLocalOf<RouteOrigins?> { null }
internal val LocalRouteOriginsEnabled = compositionLocalOf { true }
internal val LocalRouteOwner = compositionLocalOf<String?> { null }
private val LocalRouteParent = compositionLocalOf<String?> { null }

internal class RouteLaunch(val modifier: Modifier, private val host: RouteOrigins?, private val site: OriginSite) {
    fun open(action: () -> Unit) { host?.chosen = site; action() }
}

internal class RouteOrigins : ContainerOrigins() {
    var chosen: OriginSite? = null
    private val removed = mutableSetOf<String>()
    private val presented = mutableSetOf<String>()
    private val landings = mutableStateMapOf<String, OriginSite?>()
    private val popGuards = mutableMapOf<String, () -> Boolean>()

    /** Invoked only for a committed pop, never for a held or cancelled seek. */
    fun interceptPop(entry: String) = popGuards[entry]?.invoke() == true

    @Composable
    fun GuardCommittedPop(guard: () -> Boolean) {
        val entry = LocalRouteOwner.current ?: return
        val current by rememberUpdatedState(guard)
        DisposableEffect(this, entry) {
            val callback = { current() }
            popGuards[entry] = callback
            onDispose { popGuards.remove(entry, callback) }
        }
    }

    fun selectLanding(entry: String, site: OriginSite?) {
        if (entry in landings && landings[entry] == site) return
        landings[entry] = site
        // The next card can already be placed; don't wait for a scroll/layout to acquire it.
        site?.let { source(it)?.locate(host) }
    }

    override fun tracks(site: OriginSite) = super.tracks(site) || site in landings.values

    /** A settings gear or a link may target an entry already below us. Reveal that live
     * entry (including its scroll or draft) rather than registering its identity twice. */
    fun <K> navigate(stack: MutableList<K>, next: K, identity: (K) -> String) {
        val target = identity(next)
        val origin = chosen
        chosen = null
        val existing = stack.indexOfLast { identity(it) == target }
        if (existing >= 0) {
            while (stack.lastIndex > existing) {
                if (interceptPop(identity(stack.last()))) break
                pop(identity(stack.removeAt(stack.lastIndex)))
            }
        } else {
            removed.remove(target)
            capture(target, identity(stack.last()), origin)
            stack.add(next)
        }
    }
    fun pop(key: String) {
        if (key in presented) removed.add(key) else { links.remove(key); landings.remove(key) }
    }

    @Composable
    internal fun Entry(key: String, logicalFront: Boolean, from: String?, animate: Boolean,
        ordinary: Boolean, content: @Composable () -> Unit) {
        DisposableEffect(key) {
            presented.add(key)
            onDispose {
                presented.remove(key)
                if (removed.remove(key)) { links.remove(key); landings.remove(key) }
            }
        }
        val progress = if (animate) rememberContainerProgress() else null
        val front = logicalFront && progress?.covered != true
        CompositionLocalProvider(LocalRouteOwner provides key, LocalRouteParent provides from, LocalFrontEntry provides front) {
            EntryWindow(front, progress?.covered == true) {
                if (ordinary) {
                    val link = links[key]?.takeIf { it.site.owner == from }
                    val site = if (key in landings) landings[key] else link?.site
                    val source = site?.takeIf { it.owner == from }?.let { source(it) }
                    RoutePage(this, link, source, progress, Modifier.testTag("route:$key"), content)
                } else content()
            }
        }
    }
}

/** An editor's keyboard belongs to that editor, not to the page materializing behind
 * it during Back. Keep real bars/cutouts; consume only the extra IME clearance. At commit
 * the IME can outlive the departing scene, so wait for this page to own focus again. */
@Composable
private fun EntryWindow(front: Boolean, sourceCovered: Boolean, content: @Composable () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    var covered by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    SideEffect {
        if (sourceCovered && hasFocus) focus.clearFocus(force = true)
        if (!front) covered = true else if (hasFocus) covered = false
    }
    val bars = WindowInsets.systemBars
    val cutout = WindowInsets.displayCutout
    val ime = WindowInsets.ime
    val extra = remember(ime, bars, cutout) { ime.exclude(bars.union(cutout)) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val hasKeyboardInset by remember(extra, density, direction) {
        derivedStateOf {
            extra.getBottom(density) > 0 || extra.getLeft(density, direction) > 0 ||
                extra.getRight(density, direction) > 0 || extra.getTop(density) > 0
        }
    }
    // An absent keyboard must not replace the consumed-insets provider for every
    // Scaffold and list when a page is covered. While present, consume live insets.
    val keyboard = if (hasKeyboardInset && (!front || covered && !hasFocus))
        Modifier.consumeWindowInsets(extra) else Modifier
    val inactive = if (!sourceCovered) Modifier else Modifier.clearAndSetSemantics {}
        .focusProperties { onEnter = { cancelFocusChange() } }
    Box(Modifier.fillMaxSize().then(inactive).onFocusChanged { hasFocus = it.hasFocus }.focusGroup().then(keyboard),
        propagateMinConstraints = true) { content() }
}

@Composable
internal fun rememberRouteLaunch(key: String, shape: Shape = RectangleShape, siteId: Long? = null,
    visible: (() -> Boolean)? = null): RouteLaunch {
    val host = LocalRouteOrigins.current.takeIf { LocalRouteOriginsEnabled.current }
    val owner = LocalRouteOwner.current
    val density = LocalDensity.current
    val site = OriginSite(owner, key, siteId ?: currentCompositeKeyHashCode)
    val anchor = remember(site, density, shape, visible) { ContainerAnchor(site, shape, density, visible) }
    val modifier = if (host == null) Modifier else Modifier.containerSource(host, anchor)
    return remember(modifier, host, site) { RouteLaunch(modifier, host, site) }
}

@Composable
internal fun Modifier.routeOrigin(key: String, shape: Shape = RectangleShape, siteId: Long? = null): Modifier =
    then(rememberRouteLaunch(key, shape, siteId).modifier)

/** A paged entry keeps its navigation key, but its return endpoint follows the settled item. */
@Composable
internal fun PagedRouteLanding(target: String?) {
    val origins = LocalRouteOrigins.current ?: return
    val entry = LocalRouteOwner.current ?: return
    val parent = LocalRouteParent.current ?: return
    val site = remember(parent, target) { target?.let { OriginSite(parent, it, 0L) } }
    SideEffect { origins.selectLanding(entry, site) }
}

/** Restore only a previously matched control. A separate-window or missing launcher
 * never acquires a different button merely because the restored route key matches. */
@Composable
internal fun RememberRouteLanding() {
    val origins = LocalRouteOrigins.current ?: return
    val entry = LocalRouteOwner.current ?: return
    val parent = LocalRouteParent.current ?: return
    val captured by rememberSaveable(entry) { mutableStateOf(origins.links[entry]?.site?.site) }
    val site = remember(entry, parent, captured) { captured?.let { OriginSite(parent, entry, it) } }
    SideEffect { origins.selectLanding(entry, site) }
}
