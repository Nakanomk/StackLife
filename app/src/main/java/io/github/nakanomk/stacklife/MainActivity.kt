package io.github.nakanomk.stacklife

import androidx.compose.animation.core.tween
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.appearance.LocalPlannerGlassAllowed
import io.github.nakanomk.stacklife.appearance.AppearanceEditor
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.transitions.*
import io.github.nakanomk.stacklife.appearance.LocalGlassLight
import io.github.nakanomk.stacklife.appearance.PlannerPopupHost
import io.github.nakanomk.stacklife.appearance.rememberGlassLight
import io.github.nakanomk.stacklife.graphs.GraphEditorRoute
import io.github.nakanomk.stacklife.graphs.GraphsRoute
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.today.*
import io.github.nakanomk.stacklife.workspace.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch
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
@Serializable private data class EditTask(val id: String) : NavKey
@Serializable private data class FrameDetail(val id: String) : NavKey
@Serializable private data object Preferences : NavKey
@Serializable private data class EditAppearance(val group: String) : NavKey
@Serializable private data object ModelConnection : NavKey
@Serializable private data object AddTask : NavKey
@Serializable private data object PushTasks : NavKey
@Serializable private data object CreateFrame : NavKey
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
                    CompositionLocalProvider(LocalAssistant provides assistant, LocalPlannerGlassAllowed provides true,
                        LocalGlassLight provides rememberGlassLight()) {
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
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val privateScreens = remember { mutableIntStateOf(0) }
    PlannerPopupHost(stack.last() != ModelConnection && stack.last() != ImportCourses && privateScreens.intValue == 0) {
    SharedTransitionLayout {
        val shared: SharedModifier = { key ->
            if (animate && initialDetail == null) cardBoundsModifier(key) else Modifier
        }
        NavDisplay(
            backStack = stack,
            onBack = back,
            sharedTransitionScope = this,
            transitionSpec = { sceneMotion(animate, pop = false, direction = direction) },
            popTransitionSpec = { sceneMotion(animate, pop = true, direction = direction) },
            predictivePopTransitionSpec = { sceneMotion(animate, pop = true, direction = direction) },
            entryProvider = entryProvider {
                entry<Home> {
                    val order = LocalAppearance.current.order
                    val startPage = LocalAppearance.current.start
                    var destination by rememberSaveable { mutableStateOf(startPage) }
                    var frames by rememberSaveable { mutableStateOf(false) }
                    var courseScope by remember { mutableStateOf<AssistantPage>(AssistantPage.Terms) }
                    val local = deviceDay()
                    val date = selectedDate?.let(LocalDate::ofEpochDay) ?: local.date
                    val pager = rememberPagerState(initialPage = order.indexOf(destination)) { order.size }
                    val moves = rememberCoroutineScope()
                    LaunchedEffect(pager, order) {
                        pager.scrollToPage(order.indexOf(destination))
                        snapshotFlow { pager.settledPage.takeUnless { pager.isScrollInProgress } }.collect { settled ->
                            if (settled != null) destination = order[settled]
                        }
                    }
                    val position = remember(pager) { derivedStateOf { pager.currentPage + pager.currentPageOffsetFraction } }
                    val bar = rememberDestinationCapsule(destination.key, position) { key ->
                        val target = order.indexOfFirst { it.key == key }
                        destination = order[target]
                        moves.launch { if (animate) pager.animateScrollToPage(target, animationSpec = tween(320)) else pager.scrollToPage(target) }
                    }
                    val scope = when (destination) {
                        Destination.TODAY -> AssistantPage.Day(date, local.zone)
                        Destination.WORKSPACE -> if (frames) AssistantPage.Frames else AssistantPage.Queue
                        Destination.GRAPHS -> AssistantPage.Graphs
                        Destination.COURSES -> courseScope
                    }
                    CompositionLocalProvider(LocalPlannerGlassAllowed provides true) {
                        Page {
                            DockOverlay(bar, scope) { bottom ->
                                CompositionLocalProvider(LocalRootBottom provides bottom) {
                                    HorizontalPager(pager, Modifier.fillMaxSize(),
                                        beyondViewportPageCount = order.lastIndex, key = { order[it].key }) { index ->
                                        val page = order[index]
                                        val front = page == destination
                                        val inViewport by remember(pager, index) {
                                            derivedStateOf { kotlin.math.abs(position.value - index) < 1f }
                                        }
                                        PagerPane(front, front || inViewport) {
                                            when (page) {
                                                Destination.TODAY -> TodayRoute(model, date, local.zone,
                                                    onDate = { selectedDate = it.takeUnless { d -> d == local.date }?.toEpochDay() },
                                                    onEdit = navigate, onSettings = { navigate(Preferences) }, revealStart = revealStart,
                                                    onRevealed = { revealStart = null }, onAdd = { navigate(AddTask) })
                                                Destination.WORKSPACE -> WorkspacePanel(model) { workspace ->
                                                    WorkspaceScreen(workspace, model, { navigate(TaskDetail(it)) }, { navigate(FrameDetail(it)) },
                                                        { navigate(Preferences) }, if (front) shared else noCardBounds, frames, { frames = it }, { navigate(PushTasks) },
                                                        { navigate(CreateFrame) }, { navigate(AddTask) })
                                                }
                                                Destination.GRAPHS -> GraphsRoute(model, { navigate(GraphEditor(it)) }, { navigate(Preferences) })
                                                Destination.COURSES -> CoursesRoute(model, selectedTerm, selectedWeek,
                                                    onTerm = { selectedTerm = it.id; selectedWeek = null }, onWeek = { selectedWeek = it },
                                                    onImport = { navigate(ImportCourses) }, onRemove = { navigate(RemoveTerm(it.id)) },
                                                    onSettings = { navigate(Preferences) }, onScope = { courseScope = it })
                                            }
                                        }
                                    }
                                }
                            }
                        }
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
                entry<PushTasks> { WorkspacePanel(model) { PushEditor(it.queue, model, back) } }
                entry<CreateFrame> { TaskEditor(model, back, createFrame = true) }
                entry<AddTask> { TaskEditor(model, back) }
                entry<ImportCourses> { PrivateScreen(privateScreens) {
                    CourseImportScreen(model, back) { id -> selectedTerm = id; selectedWeek = 1; back() }
                } }
                entry<RemoveTerm> { key -> RemoveTermReview(key.id, model, back) }
                entry<GraphEditor> { key -> GraphEditorRoute(model, key.id, back) { navigate(FrameDetail(it)) } }
                entry<TaskDetail> { key ->
                    DetailRoute("task:${key.id}", animate && initialDetail == null) {
                        WorkspacePanel(model) { TaskScreen(key.id, it, model, back, shared) { navigate(EditTask(key.id)) } }
                    }
                }
                entry<EditTask> { key -> WorkspacePanel(model) { workspace ->
                    val task = workspace.queue.find { it.id == key.id }
                    if (task == null) MissingDetail("编辑任务", "这项任务已不在队列中。", back)
                    else TaskEditor(model, back, original = task)
                } }
                entry<FrameDetail> { key ->
                    DetailRoute("frame:${key.id}", animate && initialDetail == null) {
                        WorkspacePanel(model) { FrameScreen(key.id, it, model, back, shared) }
                    }
                }
                entry<Preferences> {
                    SettingsScreen(preferences.policy, model, back, { navigate(ModelConnection) }, { navigate(EditAppearance(it.key)) })
                }
                entry<EditAppearance> { key -> AppearanceEditor(preferences.appearance,
                    AppearanceGroup.entries.first { it.key == key.group }, model, back) }
                entry<ModelConnection> { PrivateScreen(privateScreens) { ConnectionScreen(assistant.connections, back) } }
            },
        )
    }
    AssistantSheet(assistant, onSettings = { navigate(ModelConnection) }, onImport = { navigate(ImportCourses) })
    }
}

@Composable
private fun PrivateScreen(count: MutableIntState, content: @Composable () -> Unit) {
    DisposableEffect(count) {
        count.intValue++
        onDispose { count.intValue-- }
    }
    content()
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
