package io.github.nakanomk.stacklife.courses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.GlassBacking
import io.github.nakanomk.stacklife.assistant.AssistantComposer
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.assistant.LocalAssistant
import io.github.nakanomk.stacklife.planner.PreviewResult
import io.github.nakanomk.stacklife.workspace.*
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun ReviewedImport(model: WorkspaceViewModel, draft: ReviewedTimetable, proposal: PreviewResult.Ready,
    busy: Boolean, top: Dp, onImported: (String) -> Unit, onEdit: () -> Unit, onConfirm: () -> Unit) {
    val assistant = LocalAssistant.current
    var staged by remember(proposal.proposalId) { mutableStateOf<String?>(null) }
    if (assistant != null) {
        DisposableEffect(proposal.proposalId) {
            onDispose {
                staged?.let { id ->
                    model.planner.discardCourseImport(id)
                    if ((assistant.state.value.page as? AssistantPage.CourseDraft)?.draftId == id) assistant.close()
                }
            }
        }
        val chat by assistant.state.collectAsState()
        var imported by remember { mutableStateOf(false) }
        LaunchedEffect(chat.importedDraft, chat.page) {
            if (staged != null && chat.importedDraft == staged) imported = true
            if (imported && chat.page == null) onImported(draft.term.id)
        }
    }
    ImportDiff(draft, proposal, busy, top, ask = {
        val id = staged ?: model.planner.stageCourseImport(draft.term, draft.meetings, draft.source).also { staged = it }
        AssistantPage.CourseDraft(id, draft.term.id)
    }, onEdit, onConfirm)
}
private data class CourseGroup(val title: String, val room: String, val day: Int, val first: Int, val last: Int, val start: String, val end: String)

@Composable
private fun ImportDiff(draft: ReviewedTimetable, proposal: PreviewResult.Ready, busy: Boolean, top: Dp,
    ask: () -> AssistantPage.CourseDraft, onEdit: () -> Unit, onConfirm: () -> Unit) {
    val meetings = draft.meetings
    val calendar = calendarFor(draft.source)
    val groups = remember(meetings, calendar) { meetings.groupBy {
        fun time(millis: Long) = Instant.ofEpochMilli(millis).atZone(calendar?.zone ?: ZoneId.systemDefault()).toLocalTime().toString()
        CourseGroup(it.title, it.room, it.day, it.firstPeriod, it.lastPeriod, time(it.startMillis), time(it.endMillis))
    }.entries.toList() }
    val locations = remember(groups) { groups.groupBy { it.key.title }.mapValues { (_, entries) -> entries.map { it.key.room }.distinct() }.filterValues { it.size > 1 } }
    ImportPane(composer = { if (!busy) AssistantComposer(ask) }) { bottom ->
        LazyColumn(Modifier.fillMaxSize().testTag("courseImportDiff"), contentPadding = dockListPadding(top, bottom),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.courses_confirm_import_diff), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.courses_import_summary, draft.term.id, meetings.size, groups.size, sourceLabel(draft.source)))
                ActionPill(stringResource(R.string.courses_confirm_import), onConfirm, Modifier.testTag("confirmCourseImportTop"), enabled = !busy, prominent = true)
                Text(draft.term.calendarSummary(calendar))
                if (locations.isNotEmpty()) Text(stringResource(R.string.courses_different_locations), style = MaterialTheme.typography.titleMedium)
            }
            items(locations.entries.toList()) { (title, rooms) ->
                val noLocation = stringResource(R.string.courses_no_location)
                Text(stringResource(R.string.courses_room_list, title, rooms.joinToString(stringResource(R.string.courses_list_separator)) { it.ifEmpty { noLocation } }))
            }
            items(groups) { (g, classes) ->
                Text(stringResource(R.string.courses_group_summary, g.title, g.room.ifEmpty { stringResource(R.string.courses_no_location) }, classes.joinToString(stringResource(R.string.courses_list_separator)) { it.week.toString() }, weekday(g.day), g.first, g.last, g.start, g.end))
            }
            item { Text(stringResource(R.string.courses_database_diff), style = MaterialTheme.typography.titleMedium)
                if (proposal.changes.isEmpty()) Text(stringResource(R.string.courses_unchanged)) }
            items(proposal.changes) { change -> Text(stringResource(R.string.courses_field_change, change.field, change.before ?: stringResource(R.string.courses_none), change.after ?: stringResource(R.string.courses_none))) }
            item {
                Actions {
                    Secondary(stringResource(R.string.courses_back_to_review), !busy, onEdit)
                    ActionPill(stringResource(R.string.courses_confirm_import), onConfirm, Modifier.testTag("confirmCourseImport"), enabled = !busy, prominent = true)
                }
            }
        }
    }
}

@Composable
internal fun ImportPane(composer: @Composable () -> Unit, content: @Composable (bottom: Dp) -> Unit) {
    val density = LocalDensity.current
    var height by remember { mutableIntStateOf(0) }
    GlassBacking(Modifier.fillMaxSize(), background = { content(with(density) { height.toDp() }) }) {
        Box(Modifier.align(Alignment.BottomCenter).onSizeChanged { height = it.height }.padding(horizontal = 16.dp, vertical = 4.dp)) {
            composer()
        }
    }
}
