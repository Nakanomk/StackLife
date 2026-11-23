package io.github.nakanomk.stacklife

import androidx.compose.ui.res.stringResource

import android.os.Bundle
import android.content.Intent
import io.github.nakanomk.stacklife.i18n.LocalizedActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import io.github.nakanomk.stacklife.appearance.WallpaperHost
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.github.nakanomk.stacklife.about.AppDiagnostics
import io.github.nakanomk.stacklife.about.HelpAboutScreen
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.countdown.*
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.appearance.LocalPlannerGlassAllowed
import io.github.nakanomk.stacklife.appearance.AppearanceEditor
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.appearance.glassUnavailableReason
import io.github.nakanomk.stacklife.transitions.*
import io.github.nakanomk.stacklife.appearance.LocalGlassLight
import io.github.nakanomk.stacklife.appearance.PlannerPopupHost
import io.github.nakanomk.stacklife.appearance.rememberGlassLight
import io.github.nakanomk.stacklife.graphs.GraphEditorRoute
import io.github.nakanomk.stacklife.graphs.GraphsRoute
import io.github.nakanomk.stacklife.notes.LocalOpenNote
import io.github.nakanomk.stacklife.notes.NoteEditorKey
import io.github.nakanomk.stacklife.notes.NoteEditorScreen
import io.github.nakanomk.stacklife.markdown.LocalMarkdownReadCache
import io.github.nakanomk.stacklife.markdown.MarkdownReadCache
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.reminders.ReminderTarget
import io.github.nakanomk.stacklife.reminders.reminderTarget
import io.github.nakanomk.stacklife.i18n.uiText
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import io.github.nakanomk.stacklife.today.*
import io.github.nakanomk.stacklife.workspace.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class MainActivity : LocalizedActivity() {
    private var reminder by mutableStateOf<ReminderTarget?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reminder = intent.reminderTarget()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppDiagnostics.bind(this)
        val planner = (application as StackLifeApplication).planner
        reminder = intent.reminderTarget()
        setContent { StackLifeApp(viewModel { WorkspaceViewModel(planner) }, firstRunGuide = true, reminder = reminder, onReminderHandled = {
            reminder = null
            setIntent(Intent(intent).setData(null))
        }) }
    }
}

@Serializable @SerialName("io.github.nakanomk.stacklife.Workbench") private data object Home : NavKey
@Serializable private data class TaskDetail(val id: String) : NavKey
@Serializable private data class EditTask(val id: String, val frameId: String? = null, val revision: Long? = null) : NavKey
@Serializable private data class CountDetail(val id: String) : NavKey
@Serializable private data class CountEdit(val id: String? = null) : NavKey
@Serializable private data class SourceOccurrence(val link: GraphLink.Occurrence) : NavKey
@Serializable private data class FrameDetail(val id: String, val generated: Boolean = false) : NavKey
@Serializable private data class Feature(val destination: Destination) : NavKey
@Serializable private data object Guidance : NavKey
@Serializable private data object Preferences : NavKey
@Serializable private data object HelpAbout : NavKey
@Serializable private data class EditAppearance(val group: String) : NavKey
@Serializable private data object ModelConnection : NavKey
@Serializable private data object AddTask : NavKey
@Serializable private data object CreateFrame : NavKey
@Serializable private data object ImportCourses : NavKey
@Serializable private data class RemoveTerm(val id: String, val source: String = SchoolImports.default.id) : NavKey
@Serializable private data class GraphEditor(val id: String) : NavKey

private fun NavKey.motionKey(): String = when (this) {
    Home -> "home"
    is TaskDetail -> "task:$id"
    is FrameDetail -> if (generated) "generated-frame" else "frame:$id"
    is EditTask -> "edit:$id"
    is CountDetail -> "countdown:$id"
    is CountEdit -> id?.let { "countdown:edit:$it" } ?: "countdown:new"
    is SourceOccurrence -> "source:" + link.storageKey()
    is NoteEditorKey -> "note:$draftId"
    is SlotEditorKey -> origin
    is GraphEditor -> "graph:$id"
    is EditAppearance -> "appearance:$group"
    is RemoveTerm -> "remove-term:$id:$source"
    is Feature -> "feature:${destination.key}"
    Guidance -> "guidance"
    Preferences -> "settings"
    HelpAbout -> "about"
    ModelConnection -> "model"
    AddTask -> "add-task"
    CreateFrame -> "create-frame"
    ImportCourses -> "course-import"
    else -> error("Unknown planner entry: ${this::class}")
}

@Composable
private fun SourcePanel(model: WorkspaceViewModel, link: GraphLink, content: @Composable (SourceView) -> Unit) {
    val state by remember(model, link) { model.read<SourceView>(PlannerQuery.Source(link)) }.collectAsStateWithLifecycle(ReadState.Loading)
    ReadPanel(state, content)
}

@Composable
internal fun StackLifeApp(model: WorkspaceViewModel, assistantModel: AssistantModel? = null,
    initialDetail: DetailTarget? = null, onDetailBack: () -> Unit = {}, reminder: ReminderTarget? = null,
    firstRunGuide: Boolean = false, onReminderHandled: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as StackLifeApplication
    val assistant = assistantModel ?: viewModel { AssistantModel(model.planner, app.connections, app.chat, app.history, app.attachments, app.backgrounds::collect) }
    val settings by model.settings.collectAsStateWithLifecycle()
    ReadPanel(settings) { preferences ->
        StackLifeTheme(preferences.appearance) {
            val messages = remember { SnackbarHostState() }
            LaunchedEffect(model) { model.notices.collect { messages.showSnackbar(it) } }
            Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                Box {
                    CompositionLocalProvider(LocalAssistant provides assistant, LocalPlannerGlassAllowed provides true,
                        LocalMarkdownReadCache provides remember { MarkdownReadCache() },
                        LocalGlassLight provides rememberGlassLight()) {
                        PlannerNavigation(model, assistant, preferences, initialDetail, onDetailBack, reminder, onReminderHandled, messages, firstRunGuide)
                    }
                    SnackbarHost(messages, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().imePadding().padding(12.dp))
                }
            }
        }
    }
}

@Composable
private fun PlannerNavigation(model: WorkspaceViewModel, assistant: AssistantModel, preferences: PolicyView,
    initialDetail: DetailTarget?, onDetailBack: () -> Unit, reminder: ReminderTarget?, onReminderHandled: () -> Unit, messages: SnackbarHostState, firstRunGuide: Boolean) {
    val start: NavKey = when (initialDetail?.kind) {
        "task" -> TaskDetail(initialDetail.id)
        "frame" -> FrameDetail(initialDetail.id)
        else -> Home
    }
    val stack = rememberNavBackStack(start)
    var selectedDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var revealIdentity by rememberSaveable { mutableStateOf<String?>(null) }
    val revealKey = remember(revealIdentity) { revealIdentity?.let { Json.decodeFromString<OccurrenceKey>(it) } }
    var visitToday by rememberSaveable { mutableLongStateOf(0L) }
    var destination by rememberSaveable { mutableStateOf(preferences.appearance.start) }
    var selectedTerm by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedWeek by rememberSaveable { mutableStateOf<Int?>(null) }
    val origins = remember { RouteOrigins() }
    val hostOwner = LocalLifecycleOwner.current
    val homePlane = remember(hostOwner) { HomePlane(hostOwner) }
    val back = {
        if (stack.size > 1) origins.pop(stack.removeAt(stack.lastIndex).motionKey())
        else if (initialDetail != null) onDetailBack()
        Unit
    }
    val navigate: (NavKey) -> Unit = { key ->
        if (stack.last() != key) {
            origins.push(key.motionKey(), stack.last().motionKey())
            stack.add(key)
        }
    }
    val openSource: (GraphLink) -> Unit = { link -> navigate(when (link) {
        is GraphLink.Task -> TaskDetail(link.taskId)
        is GraphLink.Occurrence -> SourceOccurrence(link)
    }) }
    val pages = rememberSaveableStateHolder()
    var frames by rememberSaveable { mutableStateOf(false) }
    var courseScope by remember { mutableStateOf<AssistantPage>(AssistantPage.Terms) }
    val local = deviceDay()
    val date = selectedDate?.let(LocalDate::ofEpochDay) ?: local.date
    fun featureScope(page: Destination): AssistantPage = when (page) {
        Destination.TODAY -> AssistantPage.Day(date, local.zone)
        Destination.WORKSPACE -> if (frames) AssistantPage.Frames else AssistantPage.Queue
        Destination.GRAPHS -> AssistantPage.Graphs
        Destination.COURSES -> courseScope
        Destination.COUNTDOWN -> AssistantPage.Countdowns
    }
    var offerGuide by rememberSaveable { mutableStateOf(firstRunGuide && initialDetail == null && reminder == null && preferences.onboardingVersion == 0) }
    LaunchedEffect(offerGuide) { if (offerGuide) { offerGuide = false; if (stack.last() == Home) navigate(Guidance) } }
    val notifications = rememberCoroutineScope()
    LaunchedEffect(reminder) {
        val target = reminder ?: return@LaunchedEffect
        val zone = ZoneId.systemDefault()
        val state = model.read<OccurrenceView>(PlannerQuery.Occurrence(target.key,zone)).first()
        val occurrence = (state as? ReadState.Loaded)?.value?.occurrence
        if (occurrence != null) {
            val hidden = Destination.TODAY !in preferences.appearance.visible
            if (!hidden) while (stack.size > 1) origins.pop(stack.removeAt(stack.lastIndex).motionKey())
            if (hidden) navigate(Feature(Destination.TODAY)) else if (stack.last() != Home) navigate(Home)
            selectedDate = java.time.Instant.ofEpochMilli(occurrence.startMillis).atZone(zone).toLocalDate().toEpochDay()
            revealIdentity = occurrence.key.storageKey()
            if (!hidden) destination = Destination.TODAY
            visitToday++
        }
        onReminderHandled()
        if (occurrence == null) notifications.launch { messages.showSnackbar(if (state == ReadState.ReadFailed)
            uiText("Could not read the event.", "无法读取事件。") else uiText("This event no longer exists.", "该事件已不存在。")) }
    }
    val animate = LocalMotionEnabled.current
    val canKeepSource = animate && glassUnavailableReason(preferences.appearance) == null
    // The source chooses retention at click time. A platform preference changing
    // must not replace an existing navigation entry or its scroll state.
    SideEffect { origins.canKeepSource = canKeepSource }
    val privateScreens = remember { mutableIntStateOf(0) }
    var historySource by rememberSaveable { mutableStateOf<String?>(null) }
    val rootDraft = rememberSaveable { java.util.UUID.randomUUID().toString() }
    CompositionLocalProvider(LocalRootDraftId provides rootDraft, LocalRouteOrigins provides origins, LocalOpenNote provides { navigate(it) },
        LocalOpenChatHistory provides { historySource = it.historyKey() }) {
    PlannerPopupHost(stack.last() != ModelConnection && stack.last() != ImportCourses && privateScreens.intValue == 0) {
    val latestAppearance by rememberUpdatedState(preferences.appearance)
    val home = preferences.appearance.nearestVisible(destination)
    // Keep Home's decoded photo available for the first frame of Back, without painting it
    // over another entry or using a private wallpaper as the cross-window popup donor.
    WallpaperHost(preferences.appearance.wallpaper(home), drawBehindContent = false) {
        val shared: SharedModifier = { key ->
            if (animate && initialDetail == null) cardBoundsModifier(key) else Modifier
        }
        val showFeature: @Composable (Destination, Boolean) -> Unit = { page, front ->
            pages.SaveableStateProvider(page.key) {
            when (page) {
                Destination.TODAY -> TodayRoute(model, date, local.zone,
                    onDate = { selectedDate = it.takeUnless { d -> d == local.date }?.toEpochDay() },
                    onEdit = navigate, onSettings = { navigate(Preferences) }, revealKey = revealKey,
                    onRevealed = { revealIdentity = null }, onAdd = { navigate(AddTask) })
                Destination.WORKSPACE -> WorkspacePanel(model) { workspace ->
                    WorkspaceScreen(workspace, model, { navigate(TaskDetail(it)) }, { navigate(FrameDetail(it)) },
                        { navigate(Preferences) }, if (front) shared else noCardBounds, frames, { frames = it },
                        { navigate(CreateFrame) }, { navigate(AddTask) })
                }
                Destination.GRAPHS -> GraphsRoute(model, { navigate(GraphEditor(it)) }, { navigate(Preferences) })
                Destination.COUNTDOWN -> CountdownRoute(model, { navigate(CountDetail(it)) }, { navigate(CountEdit()) }, { navigate(Preferences) })
                Destination.COURSES -> CoursesRoute(model, selectedTerm, selectedWeek,
                    onTerm = { selectedTerm = it.id; selectedWeek = null }, onWeek = { selectedWeek = it },
                    onImport = { navigate(ImportCourses) }, onRemove = { term, source -> navigate(RemoveTerm(term.id, source)) },
                    onSettings = { navigate(Preferences) }, onScope = { courseScope = it })
            }
            }
        }
        val entries = entryProvider<NavKey> {
                entry<Home> {
                    val order = preferences.appearance.visibleOrder
                    val active = preferences.appearance.nearestVisible(destination)
                    RootPages(order, active, visitToday, { destination = it }, featureScope(active), showFeature)
                }
                entry<Feature> { key ->
                    CompositionLocalProvider(LocalFeatureBack provides back, LocalPlannerGlassAllowed provides true) {
                        Page { DockOverlay(assistantPage = featureScope(key.destination)) { bottom ->
                            CompositionLocalProvider(LocalRootBottom provides bottom) { showFeature(key.destination, true) }
                        } }
                    }
                }
                entry<Guidance> { io.github.nakanomk.stacklife.onboarding.OnboardingScreen(preferences, model, back,
                    onSchedule = navigate, onAdd = { navigate(AddTask) }, onImport = { navigate(ImportCourses) }) }
                entry<CountDetail> { key -> CountdownDetail(model, key.id, { navigate(CountEdit(it)) }, back) }
                entry<CountEdit> { key -> CountdownEditor(model, key.id, back) }
                entry<SlotEditorKey> { key ->
                    SlotEditor(key, model, onBack = back, onSaved = { occurrence, start ->
                        if (start != null) {
                            val zone = ZoneId.systemDefault()
                            selectedDate = start.atZone(zone).toLocalDate().takeUnless { it == LocalDate.now(zone) }?.toEpochDay()
                            revealIdentity = occurrence?.storageKey()
                        }
                        back()
                    })
                }
                entry<CreateFrame> { TaskEditor(model, back, createFrame = true) }
                entry<AddTask> { TaskEditor(model, back) }
                entry<NoteEditorKey> { key -> NoteEditorScreen(key, model, back) }
                entry<ImportCourses> { PrivateScreen(privateScreens) {
                    CourseImportScreen(model, back) { id -> selectedTerm = id; selectedWeek = 1; back() }
                } }
                entry<RemoveTerm> { key -> RemoveTermReview(key.id, key.source, model, back) }
                entry<GraphEditor> { key -> GraphEditorRoute(model, key.id, back, openSource) { navigate(FrameDetail(it, generated = true)) } }
                entry<SourceOccurrence> { key -> SourcePanel(model, key.link) { view ->
                    val source = view.source
                    val occurrence = source?.occurrence
                    if (occurrence == null) MissingDetail(stringResource(R.string.graph_linked_event), stringResource(R.string.graph_source_removed), back)
                    else {
                        val zone = ZoneId.systemDefault()
                        val day = java.time.Instant.ofEpochMilli(occurrence.startMillis).atZone(zone).toLocalDate()
                        val revision = rememberSaveable { view.revision }
                        val editor = remember(key.link, revision) { SlotEditorKey(key.link.scheduleId, source.title, day.toEpochDay(), zone.id,
                            source.minutes, revision, key.link.key(), occurrence.role) }
                        SlotEditor(editor, model, onBack = back, onSaved = { _, _ -> back() })
                    }
                } }
                entry<TaskDetail>(metadata = { key -> origins.sourceSceneMetadata(key.motionKey()) }) { key ->
                    DetailRoute("task:${key.id}", animate && initialDetail == null) {
                        SourcePanel(model, GraphLink.Task(key.id)) { view ->
                            TaskScreen(key.id, view, model, back, shared) { navigate(EditTask(key.id, view.source?.frameId, view.revision)) }
                        }
                    }
                }
                entry<EditTask> { key -> SourcePanel(model, GraphLink.Task(key.id)) { view ->
                    val task = view.source
                    if (task == null || key.frameId != null && task.frameId != key.frameId) MissingDetail(stringResource(R.string.ui_edit_task),
                        stringResource(if (key.frameId == null) R.string.graph_source_removed else R.string.ui_task_not_in_frame), back)
                    else TaskEditor(model, back, original = QueueTask(key.id, task.title, task.notes, task.minutes, task.schedule),
                        frameId = key.frameId, expectedRevision = key.revision ?: rememberSaveable { view.revision })
                } }
                entry<FrameDetail>(metadata = { key -> origins.sourceSceneMetadata(key.motionKey()) }) { key ->
                    DetailRoute("frame:${key.id}", animate && initialDetail == null && !key.generated) {
                        WorkspacePanel(model) { FrameScreen(key.id, it, model, back, shared, openSource) { task -> navigate(EditTask(task, key.id, it.revision)) } }
                    }
                }
                entry<Preferences>(metadata = { key -> origins.sourceSceneMetadata(key.motionKey()) }) {
                    SettingsHelpHost { SettingsScreen(preferences, model, back, { navigate(ModelConnection) }, { navigate(EditAppearance(it.key)) },
                        { navigate(HelpAbout) }, { navigate(Feature(it)) }, navigate) }
                }
                entry<HelpAbout> { HelpAboutScreen(back) { navigate(Guidance) } }
                entry<EditAppearance> { key -> SettingsHelpHost { AppearanceEditor(preferences.appearance,
                    AppearanceGroup.entries.first { it.key == key.group }, model, back) } }
                entry<ModelConnection> { PrivateScreen(privateScreens) { SettingsHelpHost { ConnectionScreen(assistant.connections, back) } } }
        }
        homePlane.Layout(Modifier.onGloballyPositioned { origins.host = it; origins.size = it.size }) {
            NavDisplay(backStack = stack, onBack = back,
                sceneStrategies = remember { listOf(LiveSourceSceneStrategy<NavKey>()) },
                transitionSpec = { sceneMotion(animate) }, popTransitionSpec = { sceneMotion(animate) },
                predictivePopTransitionSpec = { sceneMotion(animate) },
                entryProvider = { key ->
                    val entry = entries(key)
                    NavEntry(navEntry = entry) {
                        val depth = remember(key) { stack.indexOfLast { it == key } }
                        val parent = stack.getOrNull(depth - 1)?.motionKey()
                        // A retained parent must keep its own wallpaper while the next entry reveals
                        // itself, including the first and last frame of predictive Back.
                        // Nav3 caches the entry factory while the stack is unchanged.
                        val appearance = latestAppearance
                        val owner = when (key) {
                            Home -> appearance.nearestVisible(destination)
                            Preferences, HelpAbout, ModelConnection, Guidance, is EditAppearance -> null
                            is Feature -> key.destination
                            else -> remember(key) {
                                (depth - 1 downTo 0).firstNotNullOfOrNull { (stack.getOrNull(it) as? Feature)?.destination }
                                    ?: appearance.nearestVisible(destination)
                            }
                        }
                        val page: @Composable () -> Unit = {
                            WallpaperHost(appearance.wallpaper(owner), drawBehindContent = false) {
                                origins.Entry(key.motionKey(), depth, stack.lastIndex, parent, animate && initialDetail == null,
                                    ordinary = key != Home && key !is TaskDetail && (key !is FrameDetail || key.generated)) { entry.Content() }
                            }
                        }
                        if (key == Home && depth == 0) homePlane.Entry(page) else page()
                    }
                })
        }
    }
    val source = historySource
    AssistantPanels(assistant, source?.let { remember(it) { historyPage(it) } }, configuring = stack.last() == ModelConnection,
        onCloseHistory = { historySource = null }, onSettings = { navigate(ModelConnection) }, onImport = { navigate(ImportCourses) })
    }
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
        if (state == ReadState.Loading) CircularProgressIndicator() else Text(stringResource(R.string.ui_read_failed))
    }
}
