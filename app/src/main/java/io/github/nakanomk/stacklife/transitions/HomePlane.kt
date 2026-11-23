package io.github.nakanomk.stacklife.transitions

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.savedstate.compose.LocalSavedStateRegistryOwner

/** Nav3 owns whether the root entry exists and which scene supplies its locals. Only its
 * layout parent stays fixed: moving a window-sized pager between two AnimatedContent slots
 * would otherwise detach and remeasure the same live list at the start of every return. */
internal class HomePlane(private val parent: LifecycleOwner) {
    private enum class Slot { Navigation, Home }
    private var home by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** Called inside the decorated NavEntry, never inferred from a back-stack key. */
    @Composable
    fun Entry(content: @Composable () -> Unit) {
        // Nav3 switches scene owners on both push and pop. LocalLifecycleOwner is
        // a static local: replacing its identity restarts every collector under
        // Home. Expose the real scene's state through one entry-lifetime owner.
        val scene = LocalLifecycleOwner.current.lifecycle
        val state by key(scene) { scene.currentStateFlow.collectAsState() }
        val owner = rememberLifecycleOwner(maxLifecycle = state.coerceAtLeast(Lifecycle.State.CREATED), parent = parent)
        val registry = LocalSaveableStateRegistry.current
        val savedOwner = LocalSavedStateRegistryOwner.current
        val motion = LocalNavAnimatedContentScope.current
        val covered = LocalCoveredSource.current
        // Keep the decorated entry's saveable owners and motion, not the changing
        // layout/input context of the AnimatedContent slot holding its registration.
        // Publish owners and content as one claim; two claims cannot own one saved key.
        val render: @Composable () -> Unit = {
            CompositionLocalProvider(LocalLifecycleOwner provides owner,
                LocalSaveableStateRegistry provides registry, LocalSavedStateRegistryOwner provides savedOwner,
                LocalNavAnimatedContentScope provides motion, LocalCoveredSource provides covered, content = content)
        }
        DisposableEffect(render) {
            home = render
            onDispose { if (home === render) home = null }
        }
        Spacer(Modifier.fillMaxSize())
    }

    @Composable
    fun Layout(modifier: Modifier, navigation: @Composable () -> Unit) {
        SubcomposeLayout(modifier.fillMaxSize()) { constraints ->
            // Resolve the actual NavDisplay first. Its claim is applied before the second
            // subcomposition in this measure, not by launching work on the following frame.
            val nav = subcompose(Slot.Navigation, navigation).single().measure(constraints)
            val page = home?.let { render ->
                subcompose(Slot.Home, render).single().measure(Constraints.fixed(nav.width, nav.height))
            }
            layout(nav.width, nav.height) {
                page?.place(0, 0)
                nav.place(0, 0)
            }
        }
    }
}
