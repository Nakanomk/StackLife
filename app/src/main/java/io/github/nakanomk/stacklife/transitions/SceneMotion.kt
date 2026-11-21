package io.github.nakanomk.stacklife.transitions

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import io.github.nakanomk.stacklife.workspace.SharedModifier

private const val Duration = 320
internal val noCardBounds: SharedModifier = { Modifier }
internal val LocalCardList = compositionLocalOf<LazyListState?> { null }
internal typealias DetailViewport = @Composable (Dp, Dp, @Composable () -> Unit) -> Unit
internal val LocalDetailViewport = staticCompositionLocalOf<DetailViewport?> { null }

private const val TitlePart = "title/"
@Composable
internal fun SharedModifier.title(entityKey: String) = this("$TitlePart$entityKey")

/** The NavDisplay transition owns push, pop, seeking and cancellation. No independent route clocks. */
internal fun sceneMotion(
    enabled: Boolean, pop: Boolean, direction: Int,
): ContentTransform {
    if (!enabled) return EnterTransition.None togetherWith ExitTransition.None
    return if (pop) {
        slideInHorizontally(tween(Duration)) { -direction * it / 3 } togetherWith
            slideOutHorizontally(tween(Duration)) { direction * it }
    } else {
        slideInHorizontally(tween(Duration)) { direction * it } togetherWith
            slideOutHorizontally(tween(Duration)) { -direction * it / 3 }
    }
}

private val cardBounds = BoundsTransform { _, _ -> tween(Duration) }

@Composable
internal fun SharedTransitionScope.cardBoundsModifier(key: String, detail: Boolean = false): Modifier {
    val list = LocalCardList.current
    val config = if (list == null) SharedContentConfig() else remember(list, key) {
        val id = key.removePrefix(TitlePart).substringAfter(':')
        object : SharedTransitionScope.SharedContentConfig {
            override val SharedTransitionScope.SharedContentState.isEnabled: Boolean
                get() {
                    val viewport = list.layoutInfo
                    val row = viewport.visibleItemsInfo.firstOrNull { it.key == id } ?: return false
                    return row.offset >= viewport.viewportStartOffset + viewport.beforeContentPadding &&
                        row.offset + row.size <= viewport.viewportEndOffset - viewport.afterContentPadding
                }
        }
    }
    val state = rememberSharedContentState(key, config)
    val visibility = LocalNavAnimatedContentScope.current
    // Only the incoming title is rendered: during the transform neither endpoint paints
    // another copy below it. Keep it above both opaque surface layers.
    if (key.startsWith(TitlePart)) {
        val owner = if (list == null) "detail" else "row"
        return Modifier.sharedElement(state, visibility, boundsTransform = cardBounds, zIndexInOverlay = 2f)
            .testTag("$owner-$key")
    }
    return Modifier.sharedBounds(state, visibility,
        enter = EnterTransition.None, exit = ExitTransition.None, boundsTransform = cardBounds,
        resizeMode = if (detail) SharedTransitionScope.ResizeMode.RemeasureToBounds
            else SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopStart),
        clipInOverlayDuringTransition = OverlayClip(RectangleShape), zIndexInOverlay = if (detail) 1f else 0f)
}

/** Only the reading viewport transforms into the card. Back, the page heading and the
 * action dock stay in the Nav3 scene: their horizontal travel never carries them down
 * through the moving title. Insets come from the actual chrome, including large fonts. */
@Composable
internal fun SharedTransitionScope.DetailRoute(key: String, enabled: Boolean, content: @Composable () -> Unit) {
    val viewport: DetailViewport = { top, bottom, body ->
        val bounds = if (enabled) cardBoundsModifier(key, detail = true) else Modifier
        Box(Modifier.fillMaxSize().padding(top = top, bottom = bottom).then(bounds).fillMaxSize()
            .testTag("detail:$key").background(MaterialTheme.colorScheme.surface)) {
            // Keep the scroll plane at its original size and origin beneath the bars. At
            // rest it still paints their real backdrop. During the morph the shared layer's
            // rectangular overlay clip reveals only the interpolated reading viewport.
            val stable = if (enabled) Modifier.skipToLookaheadSize() else Modifier
            Box(stable.fillMaxSize().layout { child, constraints ->
                val before = top.roundToPx()
                val full = child.measure(constraints.offset(vertical = before + bottom.roundToPx()))
                layout(constraints.maxWidth, constraints.maxHeight) { full.place(0, -before) }
            }) { body() }
        }
    }
    CompositionLocalProvider(LocalDetailViewport provides viewport, content = content)
}
