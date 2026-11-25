// SPDX-License-Identifier: GPL-3.0-only
// Adapted from BiliPai navigation3/BiliPaiAndroidXNavDisplay.kt, c7f835b34d8323358be57be298f86f29fecb53c8.
package io.github.nakanomk.stacklife.transitions

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastRoundToInt
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.NavigationBackHandler
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.rememberNavigationEventState
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder
import top.yukonga.miuix.kmp.nav.core.NavMotionLayout
import top.yukonga.miuix.kmp.nav.core.rememberNavEntryDispatcherOwner
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

private val LocalStackSceneContent = compositionLocalOf<@Composable (() -> Unit) -> Unit> {
    error("The stack scene requires its display")
}

private class BiliStackScene(
    override val entries: List<NavEntry<NavKey>>,
    onBack: () -> Unit,
) : Scene<NavKey> {
    override val key: Any get() = BiliStackScene::class
    override val previousEntries: List<NavEntry<NavKey>> get() = entries.subList(0, entries.lastIndex)
    override val content: @Composable () -> Unit = { LocalStackSceneContent.current(onBack) }

    override fun equals(other: Any?) = other is BiliStackScene && entries == other.entries
    override fun hashCode() = entries.hashCode()
}

// The entering/leaving half of NavTransitions.MiuixDefault. Unlike that preset's covered
// treatment, the live parent neither shifts nor dims. Per-route container motion takes precedence.
private val StackEdgeTransition = navGraphicsTransition { scope ->
    val depth = scope.relativeDepth
    if (depth <= 0f) {
        val sign = if (scope.layoutDirection == LayoutDirection.Rtl) -1f else 1f
        translationX = (sign * (-depth).coerceIn(0f, 1f) * scope.layoutSize.width).fastRoundToInt().toFloat()
    }
}
private val StackEffects = NavDisplayEffects(enableCornerClip = false, dimAmount = 0f)

@Composable
internal fun BiliStackDisplay(
    backStack: NavBackStack<NavKey>,
    onBack: () -> Unit,
    enabled: Boolean,
    motion: NavEntryBuilder.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (NavKey) -> Unit,
) {
    val latestContent = rememberUpdatedState(content)
    val entryContent: @Composable (NavKey) -> Unit = remember { { route -> latestContent.value(route) } }
    val routes = backStack.toList()
    // The AndroidX saveable owner belongs to the entry, not to the selected motion mode.
    val decorator = rememberSaveableStateHolderNavEntryDecorator<NavKey>()
    val entries = rememberDecoratedNavEntries(routes, remember(decorator) { listOf(decorator) }) { route ->
        NavEntry(route, content = entryContent)
    }
    val windowLifecycleOwner = LocalLifecycleOwner.current
    val sceneStrategies = remember {
        listOf(SceneStrategy<NavKey> { sceneEntries -> BiliStackScene(sceneEntries, this.onBack) })
    }
    val sceneState = rememberSceneState(entries, sceneStrategies, onBack = onBack)
    val eventState = rememberNavigationEventState(sceneState)
    val sceneEntries = sceneState.currentScene.entries
    val dispatcher = rememberNavEntryDispatcherOwner(LocalFrontEntry.current, checkNotNull(LocalNavigationEventDispatcherOwner.current))
    val sceneContent: @Composable (() -> Unit) -> Unit = { sceneOnBack ->
        if (!enabled) {
            // No settling presentation or departed input surface when motion is disabled.
            sceneEntries.last().Content()
        } else {
            val pageContent = remember(routes, sceneEntries) {
                val byRoute = buildMap<Any, @Composable (Any) -> Unit>(routes.size) {
                    sceneEntries.forEachIndexed { index, entry -> put(routes[index]) { entry.Content() } }
                }
                val lookup: (Any) -> (@Composable (Any) -> Unit) = { byRoute.getValue(it) }
                lookup
            }
            // Each retained page owns its lifecycle ceiling and back scope. The fixed scene's
            // transient lifecycle must not stall them when the logical stack changes.
            CompositionLocalProvider(LocalLifecycleOwner provides windowLifecycleOwner) {
                NavMotionLayout(
                    routes = routes,
                    liveBackStack = backStack,
                    modifier = Modifier.fillMaxSize(),
                    onBack = sceneOnBack,
                    onBackStarted = {},
                    onBackCancelled = {},
                    transition = StackEdgeTransition,
                    effects = StackEffects,
                    backCompletionPolicy = null,
                    motion = motion,
                    entryContent = pageContent,
                )
            }
        }
    }
    // With motion enabled, only NavMotionLayout registers a handler for this stack.
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides dispatcher, LocalStackSceneContent provides sceneContent) {
        if (!enabled) NavigationBackHandler(sceneState, eventState, onBackCompleted = onBack)
        NavDisplay(
            sceneState = sceneState,
            navigationEventState = eventState,
            modifier = modifier.fillMaxSize(),
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
        )
    }
}
