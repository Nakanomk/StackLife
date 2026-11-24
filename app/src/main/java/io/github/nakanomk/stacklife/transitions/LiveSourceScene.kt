package io.github.nakanomk.stacklife.transitions

import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.appearance.glassUnavailableReason

private const val LiveSource = "stacklife.liveSource"
private const val PlacedParent = "stacklife.placedParent"
internal val placedParentMetadata: Map<String, Any> = mapOf(LiveSource to true, PlacedParent to true)
internal val liveSourceMetadata: Map<String, Any> = mapOf(LiveSource to true)
internal val LocalCoveredSource = compositionLocalOf { false }

/** NavDisplay 1.2.2 gives a shared entry to the target scene on both push and pop.
 * Its SceneSetupNavEntryDecorator moves the source slot, not a second working page.
 * For the root workbench, that slot supplies the owners of a stationary HomePlane. */
internal class LiveSourceSceneStrategy<T : Any> : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        val detail = entries.last()
        if (entries.size < 2 || detail.metadata[LiveSource] != true) return null
        return LiveSourceScene(entries.dropLast(1), detail)
    }
}

private data class LiveSourceScene<T : Any>(
    override val previousEntries: List<NavEntry<T>>,
    val detail: NavEntry<T>,
) : Scene<T> {
    override val key = detail.contentKey
    override val entries = listOf(previousEntries.last(), detail)
    override val content: @Composable () -> Unit = {
        val origins = LocalRouteOrigins.current
        val placedParent = detail.metadata[PlacedParent] == true
        val directReturn = remember { placedParent || origins?.hasEntryAbove(previousEntries.size) != true }
        val scene = LocalNavAnimatedContentScope.current.transition
        val capable = LocalMotionEnabled.current && (placedParent || glassUnavailableReason(LocalAppearance.current) == null)
        Box(Modifier.fillMaxSize()) {
            if (directReturn && (capable || scene.currentState != scene.targetState)) {
                val owner = rememberLifecycleOwner(maxLifecycle = Lifecycle.State.STARTED)
                CompositionLocalProvider(LocalCoveredSource provides true, LocalLifecycleOwner provides owner) {
                    previousEntries.last().Content()
                }
            }
            detail.Content()
        }
    }
}

@Composable
internal fun Modifier.coveredSourceWindow(covered: Boolean): Modifier {
    if (!covered) return this
    val scene = LocalNavAnimatedContentScope.current.transition
    // Keep the launcher placed for the entire return, including layout/data changes.
    // A settled covered page owns no pixels, gestures, accessibility or input focus.
    return drawWithContent {
        if (scene.currentState != EnterExitState.Visible || scene.targetState != EnterExitState.Visible) drawContent()
    }.clearAndSetSemantics {}.focusProperties { onEnter = { cancelFocusChange() } }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
}
