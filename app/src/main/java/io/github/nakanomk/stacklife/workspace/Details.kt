package io.github.nakanomk.stacklife.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.transitions.title
import io.github.nakanomk.stacklife.transitions.detailBodyMotion
import io.github.nakanomk.stacklife.transitions.routeOrigin
import io.github.nakanomk.stacklife.notes.CurrentTaskNote

@Composable
internal fun TaskScreen(id: String, view: SourceView, actions: WorkspaceActions, onBack: () -> Unit,
    shared: SharedModifier, onEdit: () -> Unit) {
    val source = view.source
    val index = source?.queuePosition
    val task = source?.let { QueueTask(id, it.title, it.notes, it.minutes, it.schedule, it.icon) }
    // Room may publish the removal before our receipt arrives. Await it even without a card.
    val action = rememberSubmission(actions) { if (it != SaveOutcome.REJECTED) onBack() }
    val back = { if (!action.busy) onBack() }
    BackHandler(enabled = action.busy) { /* The receipt navigates exactly once. */ }
    if (source == null || task == null) {
        MissingDetail(stringResource(R.string.ui_task_details), stringResource(R.string.graph_source_removed), back)
        return
    }
    EditorPage(stringResource(R.string.ui_task_details), back, AssistantPage.Task(id), backEnabled = !action.busy, footer = {
        Actions {
            if (source.owner != Owner.DONE) Primary(stringResource(if (task.schedule?.pattern is SchedulePattern.Daily) R.string.slot_finish_series else R.string.ui_finish_task), enabled = !action.busy) { action.send(PlannerCommand.TaskFinish(id)) }
            ActionPill(stringResource(R.string.ui_edit_task), onEdit, Modifier.routeOrigin("edit:$id", CircleShape), enabled = !action.busy)
            if (source.owner == Owner.QUEUE) Secondary(stringResource(R.string.ui_remove_from_queue), enabled = !action.busy) { action.send(PlannerCommand.TaskRemove(id)) }
        }
    }) { top, bottom ->
        LazyColumn(Modifier.detailBodyMotion(), contentPadding = dockListPadding(top, bottom), verticalArrangement = listSpacing) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (index == 0) Text(stringResource(R.string.ui_current_priority), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    TaskIconTitle(task.icon) { Text(task.title, shared.title("task:$id"), style = MaterialTheme.typography.titleLarge) }
                    if (index != null && index != 0) Text(stringResource(R.string.ui_queue_position, index + 1), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (source.owner == Owner.FRAME) Text(stringResource(R.string.graph_stays_frame), style = MaterialTheme.typography.labelMedium)
                    if (source.owner == Owner.DONE) Text(stringResource(R.string.today_done), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.ui_duration_estimate, task.minutes), style = MaterialTheme.typography.bodyMedium)
                    task.schedule?.let { Hint(scheduleText(it)) }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTitle(stringResource(R.string.ui_notes))
                    CurrentTaskNote(task, view.revision, enabled = !action.busy, frameId = source.frameId)
                }
            }
        }
    }
}

@Composable
internal fun MissingDetail(title: String, message: String, onBack: () -> Unit) {
    EditorPage(title, onBack) { top, bottom ->
        LazyColumn(contentPadding = dockListPadding(top, bottom), verticalArrangement = listSpacing) {
            item { Hint(message) }
        }
    }
}
