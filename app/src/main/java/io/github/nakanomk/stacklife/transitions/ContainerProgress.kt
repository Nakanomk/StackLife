package io.github.nakanomk.stacklife.transitions

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.util.fastRoundToInt
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope
import top.yukonga.miuix.kmp.nav.transition.NavRole
import top.yukonga.miuix.kmp.nav.transition.NavSettlePhase
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope

/** BiliPai's driver already eases programmatic travel and passes predictive travel through. */
internal class ContainerProgress(val scope: NavTransitionScope) {
    val covered by derivedStateOf(structuralEqualityPolicy()) { scope.role == NavRole.Covered || scope.relativeDepth > 0f }
    val moving by derivedStateOf(structuralEqualityPolicy()) { !covered && (scope.relativeDepth < 0f || scope.isRemoving || scope.gesture != null) }
    val returning by derivedStateOf(structuralEqualityPolicy()) { scope.isRemoving || scope.gesture != null || scope.settle?.phase.let {
        it == NavSettlePhase.Cancel || it == NavSettlePhase.Commit
    } }
    val open = derivedStateOf { (1f + scope.relativeDepth).coerceIn(0f, 1f) }
}

@Composable internal fun rememberContainerProgress(): ContainerProgress {
    val scope = LocalNavTransitionScope.current
    return remember(scope) { ContainerProgress(scope) }
}

/** Coordinates are in this host, not the activity window. dx carries only an orphan toward the edge. */
internal class PaintedContainer(val rect: Rect, val radius: Float, val source: Rect?, val dx: Float,
    val retained: Float, val ink: Float)

@Composable
internal fun placedContainer(origins: ContainerOrigins, link: ContainerLink?, source: ContainerAnchor?,
    progress: ContainerProgress?, fixedPlane: Boolean): State<PaintedContainer?> {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val moving = progress?.moving == true
    val aperture = remember(origins, link, moving) { MotionCardWindow() }
    return remember(origins, link, source, progress, aperture, density, direction, moving, fixedPlane) { derivedStateOf {
        val size = origins.viewportSize
        if (!moving || progress == null || size.width <= 0 || size.height <= 0) null else {
            val bounds = source?.bounds?.takeIf { source.coordinates?.isAttached == true && source.density == density &&
                source.rowVisible?.invoke() != false && (link == null || link.entityPresent && origins.size == link.hostSize && density == link.density) }
            aperture.place(size, direction, progress.scope.relativeDepth, bounds, if (source != null && bounds != null) source.radius(bounds) else 0f, progress.returning)
            val left = aperture.left - aperture.edgeX
            // Ordinary routes translate their full-size text with the aperture. Keep that
            // translation on physical pixels; fractional sampling softens otherwise opaque ink.
            // A card's chrome is stationary and its separate body transform tracks the title.
            val x = if (fixedPlane) left else left.fastRoundToInt().toFloat()
            val y = if (fixedPlane) aperture.top else aperture.top.fastRoundToInt().toFloat()
            PaintedContainer(Rect(x, y, x + aperture.width, y + aperture.height), aperture.corner,
                bounds.takeUnless { aperture.detached }, aperture.edgeX, aperture.retained, aperture.ink)
        }
    } }
}
