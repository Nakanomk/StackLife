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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.today.*
import io.github.nakanomk.stacklife.workspace.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val planner = (application as StackLifeApplication).planner
        setContent { StackLifeApp(viewModel { WorkspaceViewModel(planner) }) }
    }
}

@Serializable @SerialName("io.github.nakanomk.stacklife.Workbench") private data object Home : NavKey
@Serializable private data class TaskDetail(val id: String) : NavKey
@Serializable private data class FrameDetail(val id: String) : NavKey
@Serializable private data object Preferences : NavKey
@Serializable private data object AddTask : NavKey
@Serializable private data object ImportCourses : NavKey
@Serializable private data class RemoveTerm(val id: String) : NavKey

@Composable
internal fun StackLifeApp(model: WorkspaceViewModel) {
    StackLifeTheme {
        val messages = remember { SnackbarHostState() }
        LaunchedEffect(model) { model.notices.collect { messages.showSnackbar(it) } }
        Surface(Modifier.fillMaxSize()) {
            Box {
                PlannerNavigation(model)
                SnackbarHost(messages, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().imePadding().padding(12.dp))
            }
        }
    }
}

@Composable
private fun PlannerNavigation(model: WorkspaceViewModel) {
    val stack = rememberNavBackStack(Home)
    var selectedDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var revealStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedTerm by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedWeek by rememberSaveable { mutableStateOf<Int?>(null) }
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
                entry<Home> {
                    var destination by rememberSaveable { mutableStateOf("today") }
                    val pages = rememberSaveableStateHolder()
                    val bar = rememberDestinationCapsule(destination) { destination = it }
                    pages.SaveableStateProvider(destination) {
                        if (destination == "today") {
                            val local = deviceDay()
                            val date = selectedDate?.let(LocalDate::ofEpochDay) ?: local.date
                            val query = remember(date, local.zone) { PlannerQuery.Day(date, local.zone) }
                            key(query) {
                                val state by remember(model, query) { model.day(query) }
                                    .collectAsStateWithLifecycle(initialValue = ReadState.Loading)
                                ReadPanel(state) { plan ->
                                    TodayScreen(plan, model, model::checkSlot,
                                        onDate = { selectedDate = it.takeUnless { d -> d == local.date }?.toEpochDay() },
                                        onEdit = { id -> plan.editorFor(id)?.let(navigate) },
                                        onSettings = { navigate(Preferences) }, onAdd = { navigate(AddTask) }, navigationBar = bar,
                                        revealStart = revealStart, onRevealed = { revealStart = null })
                                }
                            }
                        } else if (destination == "workspace") WorkspacePanel(model) { workspace ->
                            WorkspaceScreen(workspace, model, { navigate(TaskDetail(it)) }, { navigate(FrameDetail(it)) },
                                { navigate(Preferences) }, shared, navigationBar = bar, onAdd = { navigate(AddTask) })
                        } else CoursesRoute(model, selectedTerm, selectedWeek,
                            onTerm = { selectedTerm = it.id; selectedWeek = null }, onWeek = { selectedWeek = it },
                            onImport = { navigate(ImportCourses) }, onRemove = { navigate(RemoveTerm(it.id)) },
                            onSettings = { navigate(Preferences) }, navigationBar = bar)
                    }
                }
                entry<SlotEditorKey> { key ->
                    SlotEditor(key, model, model::checkSlot, onBack = back, onSaved = { start ->
                        if (start != null) {
                            val zone = ZoneId.systemDefault()
                            selectedDate = start.atZone(zone).toLocalDate().takeUnless { it == LocalDate.now(zone) }?.toEpochDay()
                            revealStart = start.toEpochMilli()
                        }
                        back()
                    })
                }
                entry<AddTask> { TaskEditor(model, back) }
                entry<ImportCourses> { CourseImportScreen(model, back) { id ->
                    selectedTerm = id; selectedWeek = 1; back()
                } }
                entry<RemoveTerm> { key -> RemoveTermReview(key.id, model, back) }
                entry<TaskDetail> { key -> WorkspacePanel(model) { TaskScreen(key.id, it, model, back, shared) } }
                entry<FrameDetail> { key -> WorkspacePanel(model) { FrameScreen(key.id, it, model, back, shared) } }
                entry<Preferences> { WorkspacePanel(model) { SettingsScreen(it, model, back) } }
            },
        )
    }
}

@Composable
private fun WorkspacePanel(model: WorkspaceViewModel, content: @Composable (Workspace) -> Unit) {
    val state by model.workspace.collectAsStateWithLifecycle()
    ReadPanel(state, content)
}

@Composable
internal fun <T> ReadPanel(state: ReadState<T>, content: @Composable (T) -> Unit) {
    if (state is ReadState.Loaded) content(state.value)
    else Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        if (state == ReadState.Loading) CircularProgressIndicator() else Text("无法读取设备上的日程。数据未被清除。")
    }
}
