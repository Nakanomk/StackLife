package io.github.nakanomk.stacklife

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultTransitionSpec
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.appearance.LocalPlannerGlassAllowed
import io.github.nakanomk.stacklife.appearance.AppearanceEditor
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.transitions.DetailTarget
import io.github.nakanomk.stacklife.graphs.GraphEditorRoute
import io.github.nakanomk.stacklife.graphs.GraphsRoute
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
@Serializable private data class EditAppearance(val group: String) : NavKey
@Serializable private data object ModelConnection : NavKey
@Serializable private data object AddTask : NavKey
@Serializable private data object ImportCourses : NavKey
@Serializable private data class RemoveTerm(val id: String) : NavKey
@Serializable private data class GraphEditor(val id: String) : NavKey

@Composable
internal fun StackLifeApp(model: WorkspaceViewModel, assistantModel: AssistantModel? = null,
    initialDetail: DetailTarget? = null, onDetailBack: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as StackLifeApplication
    val assistant = assistantModel ?: viewModel { AssistantModel(model.planner, app.connections, app.chat) }
    val settings by model.settings.collectAsStateWithLifecycle()
    ReadPanel(settings) { preferences ->
        StackLifeTheme(preferences.appearance) {
            val messages = remember { SnackbarHostState() }
            LaunchedEffect(model) { model.notices.collect { messages.showSnackbar(it) } }
            Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                Box {
                    CompositionLocalProvider(LocalAssistant provides assistant) {
                        PlannerNavigation(model, assistant, preferences, initialDetail, onDetailBack)
                    }
                    SnackbarHost(messages, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().imePadding().padding(12.dp))
                }
            }
        }
    }
}

@Composable
private fun PlannerNavigation(model: WorkspaceViewModel, assistant: AssistantModel, preferences: PolicyView,
    initialDetail: DetailTarget?, onDetailBack: () -> Unit) {
    val start: NavKey = when (initialDetail?.kind) {
        "task" -> TaskDetail(initialDetail.id)
        "frame" -> FrameDetail(initialDetail.id)
        else -> Home
    }
    val stack = rememberNavBackStack(start)
    var selectedDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var revealStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedTerm by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedWeek by rememberSaveable { mutableStateOf<Int?>(null) }
    val back = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else if (initialDetail != null) onDetailBack(); Unit }
    val navigate: (NavKey) -> Unit = { key -> if (stack.last() != key) stack.add(key) }
    val animate = LocalMotionEnabled.current
    SharedTransitionLayout {
        val shared: SharedModifier = { key ->
            if (animate) Modifier.sharedBounds(rememberSharedContentState(key), LocalNavAnimatedContentScope.current)
            else Modifier
        }
        NavDisplay(
            backStack = stack,
            onBack = back,
            sharedTransitionScope = this,
            transitionSpec = if (animate) defaultTransitionSpec() else { { noMotion() } },
            popTransitionSpec = if (animate) defaultPopTransitionSpec() else { { noMotion() } },
            predictivePopTransitionSpec = if (animate) defaultPredictivePopTransitionSpec() else { { _ -> noMotion() } },
            entryProvider = entryProvider {
                entry<Home> {
                    val startPage = LocalAppearance.current.start.key
                    var destination by rememberSaveable { mutableStateOf(startPage) }
                    val pages = rememberSaveableStateHolder()
                    val bar = rememberDestinationCapsule(destination) { destination = it }
                    pages.SaveableStateProvider(destination) { CompositionLocalProvider(LocalPlannerGlassAllowed provides true) {
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
                        } else if (destination == "graphs") {
                            GraphsRoute(model, { navigate(GraphEditor(it)) }, { navigate(Preferences) }, bar)
                        } else CoursesRoute(model, selectedTerm, selectedWeek,
                            onTerm = { selectedTerm = it.id; selectedWeek = null }, onWeek = { selectedWeek = it },
                            onImport = { navigate(ImportCourses) }, onRemove = { navigate(RemoveTerm(it.id)) },
                            onSettings = { navigate(Preferences) }, navigationBar = bar)
                    } }
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
                entry<GraphEditor> { key -> CompositionLocalProvider(LocalPlannerGlassAllowed provides true) {
                    GraphEditorRoute(model, key.id, back) { navigate(FrameDetail(it)) }
                } }
                entry<TaskDetail> { key -> CompositionLocalProvider(LocalAssistantPage provides AssistantPage.Task(key.id), LocalPlannerGlassAllowed provides true) {
                    WorkspacePanel(model) { TaskScreen(key.id, it, model, back, shared) }
                } }
                entry<FrameDetail> { key -> CompositionLocalProvider(LocalAssistantPage provides AssistantPage.Frame(key.id), LocalPlannerGlassAllowed provides true) {
                    WorkspacePanel(model) { FrameScreen(key.id, it, model, back, shared) }
                } }
                entry<Preferences> {
                    SettingsScreen(preferences.policy, model, back, { navigate(ModelConnection) }, { navigate(EditAppearance(it.key)) })
                }
                entry<EditAppearance> { key ->
                    AppearanceEditor(preferences.appearance, AppearanceGroup.entries.first { it.key == key.group }, model, back)
                }
                entry<ModelConnection> { ConnectionScreen(assistant.connections, back) }
            },
        )
    }
    AssistantSheet(assistant, onSettings = { navigate(ModelConnection) }, onImport = { navigate(ImportCourses) })
}

private fun noMotion() = ContentTransform(EnterTransition.None, ExitTransition.None)

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
