package io.github.nakanomk.stacklife.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.RootPages
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.appearance.LocalPlannerGlassAllowed
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.assistant.LocalAssistant
import io.github.nakanomk.stacklife.courses.CourseSelection
import io.github.nakanomk.stacklife.courses.CoursesRoute
import io.github.nakanomk.stacklife.courses.RemoveTermReview
import io.github.nakanomk.stacklife.notes.LocalOpenNote
import io.github.nakanomk.stacklife.notes.NoteEditorKey
import io.github.nakanomk.stacklife.notes.NoteEditorScreen
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.today.SlotEditor
import io.github.nakanomk.stacklife.today.SlotEditorKey
import io.github.nakanomk.stacklife.today.TodayRoute
import io.github.nakanomk.stacklife.today.deviceDay
import io.github.nakanomk.stacklife.transitions.*
import io.github.nakanomk.stacklife.workspace.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Only keys in this file and the two local editors are registered. In particular there is
// no production app/Settings tree, update host, model settings or authenticated import route.
@Serializable private data object MotionHome : NavKey
@Serializable private data class MotionTask(val id: String) : NavKey
@Serializable private data class MotionFrame(val id: String) : NavKey
@Serializable private data class MotionEdit(val id: String, val frame: String?, val revision: Long) : NavKey
@Serializable private data object MotionAdd : NavKey
@Serializable private data object MotionCreateFrame : NavKey
@Serializable private data class MotionOccurrence(val link: GraphLink.Occurrence) : NavKey
@Serializable private data class MotionRemoveTerm(val id: String, val source: String) : NavKey

// These are the keys emitted by cardBoundsModifier, routeOrigin and the production FAB.
private fun NavKey.origin(): String = when (this) {
    MotionHome -> "home"
    is MotionTask -> "task:$id"
    is MotionFrame -> "frame:$id"
    is MotionEdit -> "edit:$id"
    MotionAdd -> "add-task"
    MotionCreateFrame -> "create-frame"
    is NoteEditorKey -> "note:$draftId"
    is SlotEditorKey -> origin
    is MotionOccurrence -> "source:${link.storageKey()}"
    is MotionRemoveTerm -> "remove-term:$id:$source"
    else -> error("Not a gallery destination: $this")
}

private val calendarTabs = setOf(Destination.TODAY, Destination.COURSES)

@Composable
internal fun MotionWorkbench(start: Destination, startOnStack: Boolean = false, calendarNavigation: Boolean = false) {
    val model = LocalGalleryWorkspace.current
    val settings = LocalOpenGallerySettings.current
    val stack = rememberNavBackStack(MotionHome)
    val origins = remember { RouteOrigins() }
    val animate by rememberUpdatedState(LocalMotionEnabled.current)
    val appearance = LocalAppearance.current
    val glassAllowed = LocalPlannerGlassAllowed.current
    val back: () -> Unit = {
        if (stack.size > 1 && !origins.interceptPop(stack.last().origin())) {
            origins.pop(stack.removeAt(stack.lastIndex).origin())
        }
    }
    val navigate: (NavKey) -> Unit = { next -> origins.navigate(stack, next, NavKey::origin) }
    val shared: SharedModifier = { key -> if (animate) cardBoundsModifier(key) else Modifier }
    var destination by rememberSaveable { mutableStateOf(start) }
    var frames by rememberSaveable { mutableStateOf(startOnStack) }
    val local = deviceDay()
    var dateEpoch by rememberSaveable { mutableStateOf<Long?>(null) }
    var todayVisit by rememberSaveable { mutableIntStateOf(0) }
    val date = dateEpoch?.let(LocalDate::ofEpochDay) ?: local.date
    var revealIdentity by rememberSaveable { mutableStateOf<String?>(null) }
    val reveal = remember(revealIdentity) { revealIdentity?.let { Json.decodeFromString<OccurrenceKey>(it) } }
    var courseSelection by rememberSaveable { mutableStateOf(CourseSelection()) }
    var courseScope by remember { mutableStateOf<AssistantPage>(AssistantPage.Terms) }
    var importBoundary by rememberSaveable { mutableStateOf(false) }
    val pages = rememberSaveableStateHolder()
    val notices = remember { SnackbarHostState() }
    LaunchedEffect(model) { model.notices.collect { notices.showSnackbar(it) } }
    // RootPages gets its dock items from LocalAppearance. Limit both pager and dock to the
    // same two real pages without storing an appearance change in the planner or catalog.
    val scopedAppearance = remember(appearance, calendarNavigation, glassAllowed) {
        val scoped = if (calendarNavigation) appearance.withVisible(calendarTabs) else appearance
        if (glassAllowed) scoped else scoped.copy(glass = false)
    }
    CompositionLocalProvider(LocalAppearance provides scopedAppearance, LocalAssistant provides null,
        io.github.nakanomk.stacklife.workspace.LocalWorkspaceFeedback provides model,
        LocalRouteOrigins provides origins, LocalOpenNote provides { navigate(it) }) {
        val features: @Composable (Destination, Boolean) -> Unit = { page, front ->
            pages.SaveableStateProvider(page.key) {
                when (page) {
                    Destination.WORKSPACE -> WorkspaceData(model) { data ->
                        WorkspaceScreen(data, model, { navigate(MotionTask(it)) }, { navigate(MotionFrame(it)) }, settings,
                            if (front) shared else noCardBounds, frames, { frames = it },
                            { navigate(MotionCreateFrame) }, { navigate(MotionAdd) })
                    }
                    Destination.TODAY -> TodayRoute(model, date, local.zone,
                        onDate = { day ->
                            if (day != date) revealIdentity = null
                            dateEpoch = day.takeUnless { it == local.date }?.toEpochDay()
                        },
                        onEdit = navigate, onSettings = settings, revealKey = reveal,
                        onRevealed = { located -> if (revealIdentity == located.storageKey()) revealIdentity = null }, onAdd = { navigate(MotionAdd) },
                        todayVisit = todayVisit.takeIf { front && stack.last() == MotionHome })
                    Destination.COURSES -> CoursesRoute(model, courseSelection, onSelection = { courseSelection = it },
                        onImport = { importBoundary = true }, onRemove = { selected, source -> navigate(MotionRemoveTerm(selected.id, source)) },
                        onSettings = settings, onScope = { courseScope = it })
                    else -> error("Unexpected Gallery tab: $page")
                }
            }
        }
        val entries = entryProvider<NavKey> {
            entry<MotionHome> {
                if (calendarNavigation) {
                    val returnToNow = { dateEpoch = null; revealIdentity = null; todayVisit++; Unit }
                    RootPages(scopedAppearance.visibleOrder, destination, 0L, { next ->
                        if (next == Destination.TODAY && destination != next) returnToNow()
                        destination = next
                    }, if (destination == Destination.TODAY) AssistantPage.Day(date, local.zone) else courseScope,
                        onReselectToday = returnToNow, content = features)
                } else if (start == Destination.WORKSPACE) {
                    val scope = if (frames) AssistantPage.Frames else AssistantPage.Queue
                    Page(scope) { features(start, true) }
                } else Page { features(start, true) }
            }
            entry<MotionTask> { key ->
                DetailRoute(key.origin(), animate) {
                    SourceData(model, GraphLink.Task(key.id)) { data ->
                        DetailSourceAvailable(data.source?.owner == Owner.QUEUE)
                        TaskScreen(key.id, data, model, back, shared) { navigate(MotionEdit(key.id, data.source?.frameId, data.revision)) }
                    }
                }
            }
            entry<MotionFrame> { key ->
                DetailRoute(key.origin(), animate) {
                    WorkspaceData(model) { data ->
                        DetailSourceAvailable(model.transfers.current?.fromFrame == key.id || data.frames.any { it.id == key.id })
                        FrameScreen(key.id, data, model, back, shared, onSource = { link ->
                            navigate(when (link) {
                                is GraphLink.Task -> MotionTask(link.taskId)
                                is GraphLink.Occurrence -> MotionOccurrence(link)
                            })
                        }, onEdit = { navigate(MotionEdit(it, key.id, data.revision)) })
                    }
                }
            }
            entry<MotionEdit> { key -> SourceData(model, GraphLink.Task(key.id)) { data ->
                val task = data.source
                if (task == null || key.frame != null && task.frameId != key.frame) {
                    MissingDetail(stringResource(R.string.ui_edit_task),
                        stringResource(if (key.frame == null) R.string.graph_source_removed else R.string.ui_task_not_in_frame), back)
                } else TaskEditor(model, back, original = QueueTask(key.id, task.title, task.notes, task.minutes, task.schedule, task.icon),
                    expectedRevision = key.revision, frameId = key.frame)
            } }
            entry<MotionOccurrence> { key -> SourceData(model, key.link) { data ->
                val source = data.source
                val occurrence = source?.occurrence
                if (occurrence == null) MissingDetail(stringResource(R.string.graph_linked_event), stringResource(R.string.graph_source_removed), back)
                else {
                    val revision = rememberSaveable { data.revision }
                    val editor = remember(key, revision) {
                        val zone = ZoneId.systemDefault()
                        val day = Instant.ofEpochMilli(occurrence.startMillis).atZone(zone).toLocalDate()
                        SlotEditorKey(key.link.scheduleId, source.title, day.toEpochDay(), zone.id,
                            source.minutes, revision, key.link.key(), occurrence.role)
                    }
                    SlotEditor(editor, model, back) { _, _ -> back() }
                }
            } }
            entry<MotionAdd> { TaskEditor(model, back) }
            entry<MotionCreateFrame> { TaskEditor(model, back, createFrame = true) }
            entry<NoteEditorKey> { NoteEditorScreen(it, model, back) }
            entry<MotionRemoveTerm> { RemoveTermReview(it.id, it.source, model, back) }
            entry<SlotEditorKey> { key -> SlotEditor(key, model, back) { occurrence, instant ->
                if (instant != null) {
                    dateEpoch = instant.atZone(local.zone).toLocalDate().takeUnless { it == local.date }?.toEpochDay()
                    revealIdentity = occurrence?.storageKey()
                }
                back()
            } }
        }
        Box(Modifier.fillMaxSize().clipToBounds()) {
            BiliStackDisplay(stack, back, animate, Modifier.onGloballyPositioned {
                origins.host = it; origins.size = it.size; origins.viewportSize = it.size
            }) { key ->
                val depth = remember(key) { stack.indexOfLast { it == key } }
                val parent = remember(key) { stack.getOrNull(depth - 1)?.origin() }
                origins.Entry(key.origin(), key == stack.last(), parent, animate,
                    ordinary = key != MotionHome && key !is MotionTask && key !is MotionFrame) { entries(key).Content() }
            }
            SnackbarHost(notices, Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
        if (importBoundary) OfflineBoundary { importBoundary = false }
    }
}

@Composable
private fun WorkspaceData(model: WorkspaceViewModel, content: @Composable (Workspace) -> Unit) {
    val data by model.workspace.collectAsStateWithLifecycle()
    ReadPanel(data, content)
}

@Composable
private fun SourceData(model: WorkspaceViewModel, link: GraphLink, content: @Composable (SourceView) -> Unit) {
    val data by remember(model, link) { model.read<SourceView>(PlannerQuery.Source(link)) }
        .collectAsStateWithLifecycle(ReadState.Loading)
    ReadPanel(data, content)
}
