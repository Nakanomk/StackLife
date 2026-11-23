package io.github.nakanomk.stacklife.transitions

import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.LocalNavAnimatedContentScope

private const val LiveCard = "stacklife.liveCard"
internal val liveCardMetadata: Map<String, Any> = mapOf(LiveCard to true)

/** NavDisplay 1.2.2 gives a shared entry to the target scene on both push and pop.
 * Its SceneSetupNavEntryDecorator moves the existing composition between those slots;
 * do not put the entire list into a shared-element overlay or create a second list. */
internal class LiveCardSceneStrategy<T : Any> : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        val card = entries.last()
        if (entries.size < 2 || card.metadata[LiveCard] != true) return null
        return LiveCardScene(entries.dropLast(1), card)
    }
}

private data class LiveCardScene<T : Any>(
    override val previousEntries: List<NavEntry<T>>,
    val detail: NavEntry<T>,
) : Scene<T> {
    override val key = detail.contentKey
    override val entries = listOf(previousEntries.last(), detail)
    override val content: @Composable () -> Unit = {
        val origins = LocalRouteOrigins.current
        val keepSource = remember { origins?.hasEntryAbove(previousEntries.size) != true }
        Box(Modifier.fillMaxSize()) {
            if (keepSource) CoveredCardList { previousEntries.last().Content() }
            detail.Content()
        }
    }
}

@Composable
private fun CoveredCardList(content: @Composable () -> Unit) {
    val scene = LocalNavAnimatedContentScope.current.transition
    val owner = rememberLifecycleOwner(maxLifecycle = Lifecycle.State.STARTED)
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    SideEffect { if (focused) focus.clearFocus(force = true) }
    // Keep the list laid out so deletes/reorders and row clipping stay live. No pixels,
    // gestures, accessible actions or input focus belong to a resting, covered list.
    Box(Modifier.fillMaxSize().drawWithContent {
        if (scene.currentState != EnterExitState.Visible || scene.targetState != EnterExitState.Visible) drawContent()
    }.clearAndSetSemantics {}.onFocusChanged { focused = it.hasFocus }
        .focusProperties { onEnter = { cancelFocusChange() } }.focusGroup().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }) {
        CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
    }
}
