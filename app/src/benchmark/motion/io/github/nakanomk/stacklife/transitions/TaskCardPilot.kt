package io.github.nakanomk.stacklife.transitions

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.navigation3.runtime.rememberNavBackStack
import io.github.nakanomk.stacklife.Home
import io.github.nakanomk.stacklife.SourcePanel
import io.github.nakanomk.stacklife.TaskDetail
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.appearance.wallpaperBackground
import io.github.nakanomk.stacklife.planner.GraphLink
import io.github.nakanomk.stacklife.planner.Owner
import io.github.nakanomk.stacklife.workspace.SharedModifier
import io.github.nakanomk.stacklife.workspace.TaskScreen
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder
import top.yukonga.miuix.kmp.nav.transition.NavTransition

/** Only the queue → actual task boundary is on trial. All commands, editors and other routes keep their owners. */
internal object TaskCardPilot : TaskCardTrial {
    @Composable override fun Show(model: WorkspaceViewModel, edit: (String, String?, Long) -> Unit,
        home: @Composable (TrialQueueLinks) -> Unit) {
        val stack = rememberNavBackStack(Home)
        val sources = remember { MotionCardSources() }
        val motions = remember { mutableMapOf<TaskDetail, NavTransition>() }
        val enabled = LocalMotionEnabled.current
        val color = rememberUpdatedState(MaterialTheme.colorScheme.surface)
        val cards: SharedModifier = { key ->
            if (enabled && key.startsWith("task:")) sources.card(key) else cardBoundsModifier(key)
        }
        val links = remember(sources, cards, enabled) { TrialQueueLinks({ id ->
            if (stack.last() == Home) {
                val route = TaskDetail(id)
                val transition = if (enabled) sources.capture("task:$id") { color.value } else null
                if (transition == null) motions.remove(route) else motions[route] = transition
                stack.add(route)
            }
        }, cards) }
        // NavMotionLayout supplies real NavEntry content separately from these per-route motion registrations.
        val motion: NavEntryBuilder.() -> Unit = remember(motions) { {
            entry<Home> { }
            entry<TaskDetail>(transitionForKey = motions::get) { }
        } }
        val back = { if (stack.size > 1) stack.removeAt(stack.lastIndex); Unit }
        val parentFront = LocalFrontEntry.current
        BiliStackDisplay(stack, back, enabled, motion, Modifier.onGloballyPositioned { sources.host = it }) { route ->
            val front = parentFront && stack.last() == route
            val focus = LocalFocusManager.current
            var focused by remember { mutableStateOf(false) }
            if (!front && focused) SideEffect { focus.clearFocus(force = true) }
            val page = Modifier.fillMaxSize().then(if (front) Modifier else Modifier.clearAndSetSemantics {})
                .onFocusChanged { focused = it.hasFocus }
                .focusProperties { onEnter = { if (!front) cancelFocusChange() } }.focusGroup()
            CompositionLocalProvider(LocalFrontEntry provides front) {
                when (route) {
                    Home -> Box(page) { home(links) }
                    is TaskDetail -> {
                        val key = "task:${route.id}"
                        val transition = remember(route) { motions[route] }
                        DisposableEffect(route) { onDispose {
                            if (route !in stack && motions[route] === transition) motions.remove(route)
                        } }
                        Box(page.testTag("detail:$key").wallpaperBackground(color.value)) {
                            SourcePanel(model, GraphLink.Task(route.id)) { view ->
                                // The retained queue may still be at CREATED while this detail sees a newer commit.
                                if (view.source?.owner != Owner.QUEUE) SideEffect { sources.invalidate(key) }
                                TaskScreen(route.id, view, model, back, { cardBoundsModifier(it) }) {
                                    edit(route.id, view.source?.frameId, view.revision)
                                }
                            }
                        }
                    }
                    else -> error("Unsupported task card route: $route")
                }
            }
        }
    }
}
