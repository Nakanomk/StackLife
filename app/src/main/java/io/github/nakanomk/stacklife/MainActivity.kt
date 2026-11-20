package io.github.nakanomk.stacklife

import android.animation.ValueAnimator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import io.github.nakanomk.stacklife.planner.Workspace
import io.github.nakanomk.stacklife.workspace.*
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val planner = (application as StackLifeApplication).planner
        setContent { StackLifeApp(viewModel { WorkspaceViewModel(planner) }) }
    }
}

@Serializable private data object Workbench : NavKey
@Serializable private data class TaskDetail(val id: String) : NavKey
@Serializable private data class FrameDetail(val id: String) : NavKey
@Serializable private data object Preferences : NavKey

@Composable
internal fun StackLifeApp(model: WorkspaceViewModel) {
    StackLifeTheme {
        val state by model.state.collectAsStateWithLifecycle()
        val messages = remember { SnackbarHostState() }
        LaunchedEffect(model) { model.notices.collect { messages.showSnackbar(it) } }
        Surface(Modifier.fillMaxSize()) {
            Box {
                when (val current = state) {
                    WorkspaceState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    WorkspaceState.ReadFailed -> Text("无法读取设备上的工作台。数据未被清除。", Modifier.align(Alignment.Center).safeDrawingPadding())
                    is WorkspaceState.Loaded -> PlannerNavigation(current.workspace, model)
                }
                SnackbarHost(messages, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().imePadding().padding(12.dp))
            }
        }
    }
}

@Composable
private fun PlannerNavigation(workspace: Workspace, submit: WorkspaceActions) {
    val stack = rememberNavBackStack(Workbench)
    val back = { if (stack.size > 1) stack.removeAt(stack.lastIndex); Unit }
    val navigate: (NavKey) -> Unit = { key -> if (stack.last() != key) stack.add(key) }
    val animate = ValueAnimator.areAnimatorsEnabled()
    SharedTransitionLayout {
        val shared: SharedModifier = { key ->
            if (animate) Modifier.sharedBounds(rememberSharedContentState(key), LocalNavAnimatedContentScope.current)
            else Modifier
        }
        NavDisplay(
            backStack = stack,
            onBack = back,
            sharedTransitionScope = this,
            entryProvider = entryProvider {
                entry<Workbench> {
                    WorkspaceScreen(workspace, submit, { navigate(TaskDetail(it)) }, { navigate(FrameDetail(it)) }, { navigate(Preferences) }, shared)
                }
                entry<TaskDetail> { TaskScreen(it.id, workspace, submit, back, shared) }
                entry<FrameDetail> { FrameScreen(it.id, workspace, submit, back, shared) }
                entry<Preferences> { SettingsScreen(workspace, submit, back) }
            },
        )
    }
}
