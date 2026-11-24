package io.github.nakanomk.stacklife.transitions

// Placement gates, linear bounds, overlay ordering and ink handoff adapted from BiliPai
// SettingsSharedContainerTransition.kt, SettingsContainerSource.kt and SettingsContainerMotionPolicy.kt
// (5893c2c80739e3759ac64ae5f232cf03a94da7f8, GPL-3.0). The host is the actual Nav3 or sheet layout.
import androidx.compose.animation.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import io.github.nakanomk.stacklife.appearance.LocalCapsuleBackdrop
import io.github.nakanomk.stacklife.appearance.LocalPanelBack
import io.github.nakanomk.stacklife.appearance.LocalWallpaperRecording
import io.github.nakanomk.stacklife.appearance.wallpaperBackground
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.round
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.floor

internal const val ContainerDuration = 300
internal val containerBounds = BoundsTransform { _, _ -> tween(ContainerDuration, easing = LinearEasing) }
internal fun containerInk(open: Float, returning: Boolean) = ((open - if (returning) .6f else .2f) / .2f).coerceIn(0f, 1f)

/** Only the last *painted window* belongs to this transition. If its launcher disappears,
 * carry that window to the logical end edge; never extrapolate toward the former launcher.
 * Seeking back above the handoff reopens the window to the page using the same Nav fraction. */
internal class EdgeDeparture {
    private var held: Rect? = null
    private var corner = 0f
    private var heldOpen = 1f
    var detached = false
        private set

    fun placed(rect: Rect, radius: Float, open: Float) {
        held = rect
        corner = radius
        heldOpen = open
    }

    fun loseMatch() {
        if (held != null && heldOpen < 1f) detached = true
    }

    fun depart(page: Rect, hostWidth: Float, direction: LayoutDirection, open: Float): EdgeFrame {
        val last = held
        // A newly laid out parent can acquire its source at the untouched endpoint.
        // After painting an intermediate edge frame, even the same source returning
        // must not pull this gesture sideways onto a different trajectory.
        if (open > 0f && open < 1f) detached = true else loseMatch()
        val reopen = if (last == null || heldOpen >= 1f) 1f
            else ((open - heldOpen) / (1f - heldOpen)).coerceIn(0f, 1f)
        val frame = when {
            last == null || reopen >= 1f -> page
            reopen <= 0f -> last
            else -> lerp(last, page, reopen)
        }
        val travel = if (heldOpen <= 0f) 0f else (1f - open / heldOpen).coerceIn(0f, 1f)
        val distance = if (direction == LayoutDirection.Ltr) hostWidth - frame.left else -frame.right
        return EdgeFrame(frame, (distance * travel).roundToInt().toFloat(), corner * (1f - reopen), 1f - reopen)
    }
}

internal class EdgeFrame(val rect: Rect, val dx: Float, val radius: Float, val retained: Float)

private class PaneEdgeOutline(private val edge: EdgeFrame) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(Rect(Offset.Zero, edge.rect.size), CornerRadius(edge.radius)))
}

@Composable
internal fun AnimatedVisibilityScope.containerOpen(enabled: Boolean, easing: Easing): State<Float> = transition.animateFloat(
    { if (enabled) tween(ContainerDuration, easing = easing) else snap() }, label = "container openness",
) { if (it == EnterExitState.Visible) 1f else 0f }

/** A Popup/Dialog has a different coordinate root. Clipped or detached nodes have no landing. */
internal fun LayoutCoordinates.fullBoundsIn(host: LayoutCoordinates?): Rect? {
    if (host == null || !host.isAttached || !isAttached || findRootCoordinates() !== host.findRootCoordinates()) return null
    val whole = host.localBoundingBoxOf(this, clipBounds = false)
    if (whole.width <= 1f || whole.height <= 1f || whole.left < -1f || whole.top < -1f ||
        whole.right > host.size.width + 1f || whole.bottom > host.size.height + 1f) return null
    val visible = boundsInWindow()
    val uncut = boundsInWindow(clipBounds = false)
    return whole.takeIf { visible.width + 1f >= uncut.width && visible.height + 1f >= uncut.height }
}

internal data class OriginSite(val owner: String?, val target: String, val site: Long)
internal open class ContainerAnchor(val site: OriginSite, val shape: Shape, val density: Density, val rowVisible: (() -> Boolean)? = null) {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null, referentialEqualityPolicy())
    var bounds by mutableStateOf<Rect?>(null)
        private set
    fun place(node: LayoutCoordinates, host: LayoutCoordinates?) { coordinates = node; locate(host) }
    fun locate(host: LayoutCoordinates?): Rect? = coordinates?.fullBoundsIn(host).also { bounds = it }
    fun radius(box: Rect) = (shape as? CornerBasedShape)?.topStart?.toPx(box.size, density)?.coerceIn(0f, box.minDimension / 2) ?: 0f
}

/** Remembers identity and host metrics, never a rectangle to animate back to. */
internal class ContainerLink(val site: OriginSite, val hostSize: IntSize, val density: Density, val radius: Float,
    val keepSource: Boolean) {
    var destination by mutableStateOf<LayoutCoordinates?>(null)
    var pageSize by mutableStateOf(IntSize.Zero)
    var frameSize by mutableStateOf(IntSize.Zero)
    var sourcePlaced by mutableStateOf(false)
    var driver by mutableStateOf<PairVisibility?>(null)
    var open by mutableStateOf<State<Float>?>(null)
    val shape = object : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
            Outline.Rounded(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius * (1f - (open?.value ?: 0f)))))
    }
}

internal class PairVisibility(val row: AnimatedVisibilityScope, val page: AnimatedVisibilityScope, val open: State<Float>)

@Composable
private fun Transition<Boolean>.side(label: String, page: Boolean): AnimatedVisibilityScope {
    val child = createChildTransition(label) { if (it == page) EnterExitState.Visible else EnterExitState.PreEnter }
    return remember(child) { object : AnimatedVisibilityScope { override val transition = child } }
}

/** Both endpoints, corners and ink sample one already-positioned fraction. */
@Composable
internal fun Transition<Boolean>.placedPair(link: ContainerLink): PairVisibility {
    val row = side("source", false)
    val page = side("destination", true)
    val sample = animateFloat({ tween(ContainerDuration, easing = LinearEasing) }, label = "sampled frame") { if (it) 1f else 0f }
    val driver = remember(row, page, sample) { PairVisibility(row, page, sample) }
    DisposableEffect(link, driver) {
        link.driver = driver
        onDispose { if (link.driver === driver) link.driver = null }
    }
    return driver
}

internal open class ContainerOrigins {
    private val anchors = mutableStateMapOf<OriginSite, ContainerAnchor>()
    val links = mutableStateMapOf<String, ContainerLink>()
    var host: LayoutCoordinates? = null
    var size by mutableStateOf(IntSize.Zero)
    fun attach(anchor: ContainerAnchor) { anchors[anchor.site] = anchor }
    fun detach(anchor: ContainerAnchor) { anchors.remove(anchor.site, anchor) }
    fun source(site: OriginSite) = anchors[site]
    protected open fun tracks(site: OriginSite) = links[site.target]?.site == site
    fun place(anchor: ContainerAnchor, node: LayoutCoordinates) {
        // Only the captured launcher needs live clipped geometry. Keep the other
        // buttons' coordinates so a later click can resolve them at that instant.
        anchor.place(node, if (tracks(anchor.site)) host else null)
    }
    protected open fun keepSource(anchor: ContainerAnchor) = false
    fun capture(target: String, owner: String?, chosen: OriginSite? = null) {
        val a = if (chosen != null) anchors[chosen]?.takeIf { chosen.owner == owner && chosen.target == target && it.locate(host) != null }
            else anchors.values.singleOrNull { it.site.owner == owner && it.site.target == target && it.locate(host) != null }
        val bounds = a?.bounds
        if (bounds == null || a.rowVisible?.invoke() == false) links.remove(target)
        else links[target] = ContainerLink(a.site, size, a.density, a.radius(bounds), keepSource(a))
    }
    /** The endpoint is geometry only. Moving the painted control through an overlay
     * and counter-translating it still filters its text at fractional display pixels. */
    @Composable
    fun SharedTransitionScope.PlacedSources() {
        for ((_, link) in links) key(link) {
            val side = link.driver?.row // Entity cards have no shared-bounds spacer.
            val anchor = anchors[link.site]
            val bounds = anchor?.bounds
            if (side != null && anchor != null && anchor.rowVisible == null && bounds != null) {
                val state = gatedContent(link) { live(link) }
                Spacer(Modifier.layout { measurable, constraints ->
                    val source = measurable.measure(Constraints.fixed(bounds.width.roundToInt(), bounds.height.roundToInt()))
                    // Do not contribute to the real host's size (especially a wrap-content sheet).
                    layout(constraints.minWidth, constraints.minHeight) {
                        val plane = host
                        val here = coordinates
                        if (plane?.isAttached == true && here != null) {
                            val at = here.localPositionOf(plane, bounds.topLeft).round()
                            source.place(at.x, at.y)
                        }
                    }
                }.onGloballyPositioned { link.sourcePlaced = true }
                    .sharedBounds(state, side, boundsTransform = containerBounds, enter = EnterTransition.None,
                        exit = ExitTransition.None, renderInOverlayDuringTransition = false)
                    .skipToLookaheadSize { state.isMatchFound })
            }
        }
    }

    fun source(link: ContainerLink) = anchors[link.site]
    fun sourceCoordinates(link: ContainerLink) = source(link)?.coordinates
    fun sourceBounds(link: ContainerLink) = anchors[link.site]?.bounds
    fun live(link: ContainerLink): Boolean {
        val a = anchors[link.site] ?: return false
        return a.bounds != null && a.coordinates?.isAttached == true && a.density == link.density && size == link.hostSize
    }
}

@Composable
internal fun SharedTransitionScope.gatedContent(key: Any, enabled: () -> Boolean): SharedTransitionScope.SharedContentState {
    val current by rememberUpdatedState(enabled)
    val config = remember { object : SharedTransitionScope.SharedContentConfig {
        override val SharedTransitionScope.SharedContentState.isEnabled get() = current()
        override val shouldKeepEnabledForOngoingAnimation get() = false
    } }
    return rememberSharedContentState(key, config)
}

@Composable
internal fun Modifier.containerSource(origins: ContainerOrigins, anchor: ContainerAnchor): Modifier {
    DisposableEffect(origins, anchor) { origins.attach(anchor); onDispose { origins.detach(anchor) } }
    return onGloballyPositioned { origins.place(anchor, it) }
}

@Composable
internal fun ContainerPage(origins: ContainerOrigins, link: ContainerLink?, visibility: AnimatedVisibilityScope,
    scope: SharedTransitionScope?, open: State<Float>, returning: Boolean, front: Boolean, moving: Boolean,
    modifier: Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val shared = if (link == null || scope == null) null else with(scope) {
        // Keep the resting destination registered while its parent is off-stack. Its
        // own measured bounds start Back; the source separately gates the live match.
        gatedContent(link) { front && origins.size == link.hostSize && link.density == density }
    }
    if (link != null) {
        SideEffect { link.open = open }
        DisposableEffect(link) { onDispose { link.destination = null; link.open = null } }
    }
    val ink = { containerInk(open.value, returning) }
    val direction = LocalLayoutDirection.current
    val departure = remember(origins, link, returning, moving) { EdgeDeparture() }
    val orphan = moving && (link == null || departure.detached || !origins.live(link) || density != link.density)
    if (orphan) departure.loseMatch()
    val paired = { moving && !orphan && link != null && link.drawingMatch(origins, shared) }
    val frame = if (departure.detached || shared == null || scope == null || link?.sourcePlaced != true) Modifier else with(scope) {
        Modifier.sharedBounds(shared, visibility,
            enter = EnterTransition.None, exit = ExitTransition.None, boundsTransform = containerBounds,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            // Nav3 / AnimatedContent already own the z-order. Keep the painted page with
            // its parent and its donor; sharedBounds still places it at the animated rect.
            renderInOverlayDuringTransition = false).placedPage(link)
    }
    val finalSize = if (orphan || scope == null || shared == null) Modifier else with(scope) {
        Modifier.skipToLookaheadSize { shared.isMatchFound }
    }
    var page by remember(departure, orphan) { mutableStateOf<Rect?>(null) }
    val placement = if (!moving) Modifier else Modifier.onGloballyPositioned { c ->
        val host = origins.host
        if (host?.isAttached == true && c.isAttached) {
            if (orphan) page = host.localBoundingBoxOf(c, clipBounds = false)
            else if (link != null && paired()) {
                val t = open.value
                departure.placed(host.localBoundingBoxOf(c, clipBounds = false), link.radius * (1f - t), t)
            }
        }
    }
    val edge by remember(departure, orphan, open, direction) { derivedStateOf {
        if (!orphan) null else page?.let { full ->
            departure.depart(full, origins.host?.size?.width?.toFloat() ?: full.right, direction, open.value)
        }
    } }
    val carry = if (!orphan) Modifier else Modifier.graphicsLayer {
        val e = edge
        val full = page
        if (e != null && full != null && !full.isEmpty) {
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = e.rect.left + e.dx - full.left
            translationY = e.rect.top - full.top
            scaleX = e.rect.width / full.width
            scaleY = e.rect.height / full.height
            clip = true
        }
    }
    // A narrow sheet isn't necessarily clipped by its window. Never paint the strip
    // of the carried page that has already crossed the sheet's physical edge.
    val viewport = if (!orphan) Modifier else Modifier.drawWithContent {
        val e = edge ?: return@drawWithContent
        val x = e.rect.left + e.dx
        val width = origins.host?.size?.width?.toFloat() ?: return@drawWithContent
        val left = maxOf(0f, -x)
        val right = minOf(e.rect.width, width - x)
        if (right > left) clipRect(left = left, right = right) { this@drawWithContent.drawContent() }
    }
    androidx.compose.foundation.layout.Box(frame.then(placement).then(carry).then(modifier).graphicsLayer {
        val e = edge
        if (e != null && !e.rect.isEmpty) {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = size.width / e.rect.width
            scaleY = size.height / e.rect.height
            shape = PaneEdgeOutline(e)
            clip = true
        } else {
            if (link != null) shape = link.shape
            clip = paired()
        }
        alpha = if (moving && (open.value <= 0f || orphan && e == null)) 0f else 1f
    }.then(viewport).containerPaper(origins, link, moving, paired, ink).then(finalSize), propagateMinConstraints = true) { content() }
}

/** Immediately inside sharedBounds: its lookahead placeholder and the painted child's
 * approach constraints can differ. onGloballyPositioned on the wrapper isn't a measurement
 * of that child. Retain only the two currently placed sizes, never a landing position. */
internal fun Modifier.placedPage(link: ContainerLink): Modifier = layout { measurable, constraints ->
    val child = measurable.measure(constraints)
    val measured = IntSize(child.width, child.height)
    val lookahead = isLookingAhead
    layout(child.width, child.height) {
        if (lookahead) link.pageSize = measured else link.frameSize = measured
        child.place(0, 0)
    }
}.onGloballyPositioned { link.destination = it }

internal fun ContainerLink.drawingMatch(origins: ContainerOrigins, shared: SharedTransitionScope.SharedContentState?): Boolean {
    if (destination?.isAttached != true || !sourcePlaced || !origins.live(this) ||
        pageSize == IntSize.Zero || frameSize == IntSize.Zero) return false
    // A held seek can place a real intermediate frame without running the overlay.
    // Conversely, match-found alone must not cover the whole parent with empty material
    // while Compose is still acquiring the source (or releasing it at the end of Back).
    return frameSize != pageSize || shared?.isMatchFound == true &&
        (frameSize == origins.sourceCoordinates(this)?.size || open?.value == 1f)
}

/** Mask the actual material within the real source footprint. The parent, not a captured
 * copy, supplies the text underneath. Sheet donors contain the shell only, never chat text. */
@Composable
internal fun Modifier.containerPaper(origins: ContainerOrigins?, link: ContainerLink?, moving: Boolean,
    paired: () -> Boolean, ink: () -> Float): Modifier {
    val pane = origins is PaneOrigins
    val sheet = pane && LocalPanelBack.current != null
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val paper = if (sheet) colors.surfaceContainerHigh else colors.surface
    if (!moving) return if (pane) this else wallpaperBackground(paper)
    val donor = if (sheet) LocalCapsuleBackdrop.current else LocalWallpaperRecording.current
    if (link == null) return wallpaperBackground(paper, donor)
    var position by remember { mutableStateOf(Offset.Unspecified) }
    return onGloballyPositioned {
        position = origins?.host?.takeIf { host -> host.isAttached }?.localPositionOf(it, Offset.Zero) ?: Offset.Unspecified
    }.sourceHandoff({ if (paired()) ink() else 1f }) {
        if (position.isSpecified) origins?.sourceBounds(link)?.translate(-position) else null
    }.wallpaperBackground(paper, donor)
}

/** The last layer holds live drawing commands, not a bitmap of the page. Keep group
 * opacity (including overlapping ink and glass) but isolate only the launcher's tile. */
internal fun Modifier.sourceHandoff(coverage: () -> Float, slot: () -> Rect?): Modifier = drawWithContent {
    val a = coverage()
    val overlap = if (a < 1f) slot()?.intersect(Rect(Offset.Zero, size)) else null
    when {
        a >= 1f -> drawContent()
        a <= 0f -> if (overlap == null || overlap.isEmpty) drawContent() else
            clipRect(overlap.left, overlap.top, overlap.right, overlap.bottom, ClipOp.Difference) { this@drawWithContent.drawContent() }
        // A fractional group previously had an implicit clip at the layer's own edges.
        overlap == null || overlap.isEmpty -> clipRect { this@drawWithContent.drawContent() }
        else -> clipRect {
            // Split on pixel edges; the original DstOut still supplies the fractional
            // edge inside this tile. Both clips replay the same live child display list.
            val l = floor(overlap.left)
            val t = floor(overlap.top)
            val r = ceil(overlap.right)
            val b = ceil(overlap.bottom)
            if (l > 0f || t > 0f || r < size.width || b < size.height)
                clipRect(l, t, r, b, ClipOp.Difference) { this@drawWithContent.drawContent() }
            clipRect(l, t, r, b) {
                val canvas = drawContext.canvas.nativeCanvas
                val save = canvas.saveLayer(l, t, r, b, null)
                try {
                    this@drawWithContent.drawContent()
                    drawRect(Color.White.copy(alpha = 1f - a), overlap.topLeft, overlap.size, blendMode = BlendMode.DstOut)
                } finally {
                    canvas.restoreToCount(save)
                }
            }
        }
    }
}.graphicsLayer()
