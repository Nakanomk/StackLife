package io.github.nakanomk.stacklife.courses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
internal fun CoursesRoute(model: WorkspaceViewModel, termId: String?, selectedWeek: Int?, onTerm: (TermSpec) -> Unit,
    onWeek: (Int) -> Unit, onImport: () -> Unit, onRemove: (TermSpec) -> Unit, onSettings: () -> Unit,
    navigationBar: @Composable () -> Unit) {
    val state by remember(model) { model.terms() }.collectAsStateWithLifecycle(initialValue = ReadState.Loading)
    ReadPanel(state) { all ->
        val today = LocalDate.now(campusZone)
        val term = all.terms.firstOrNull { it.id == termId } ?: all.terms.firstOrNull {
            today >= it.firstMonday && today < it.firstMonday.plusWeeks(it.weeks.toLong())
        } ?: all.terms.firstOrNull()
        if (term == null) CourseHome(all.terms, null, onTerm, onWeek, onImport, onRemove, onSettings, navigationBar)
        else {
            val week = (selectedWeek?.takeIf { term.id == termId } ?: (ChronoUnit.WEEKS.between(term.firstMonday, today).toInt() + 1)).coerceIn(1, term.weeks)
            val query = PlannerQuery.Week(term.id, week)
            key(query) {
                val data by remember(model) { model.week(query) }.collectAsStateWithLifecycle(initialValue = ReadState.Loading)
                ReadPanel(data) { CourseHome(all.terms, it, onTerm, { n -> onTerm(term); onWeek(n) }, onImport, onRemove, onSettings, navigationBar) }
            }
        }
    }
}

@Composable
internal fun RemoveTermReview(id: String, model: WorkspaceViewModel, onBack: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<PreviewResult?>(null) }
    val submission = rememberSubmission(model, onError = { preview = PreviewResult.Rejected(it) }) { outcome ->
        if (outcome == SaveOutcome.APPLIED) onBack()
    }
    val saving = submission.busy
    val cancel: () -> Unit = { if (!submission.busy) onBack() }
    val ready = preview as? PreviewResult.Ready
    LaunchedEffect(model, id, version) {
        if (!saving) preview = model.previewRemoval(id)
    }
    DisposableEffect(model, id) {
        onDispose {
            if (!submission.busy) (preview as? PreviewResult.Ready)?.let { model.discardProposal(it.proposalId) }
        }
    }
    BackHandler(enabled = saving) { /* Do not replay or cancel an accepted removal on Back. */ }
    Page {
        LazyColumn(Modifier.weight(1f), contentPadding = pagePadding, verticalArrangement = listSpacing) {
            item { PageTitle("移除学期课表", back = cancel, backEnabled = !saving, assistantPage = AssistantPage.Week(id, 1)) }
            item { Text(id, style = MaterialTheme.typography.titleLarge); Hint("仅移除本学期的华科课程。队列、预约和其他学期不动。") }
            when (val result = preview) {
                null -> item { CircularProgressIndicator() }
                is PreviewResult.Rejected -> item { Text(result.message) }
                is PreviewResult.Ready -> items(result.changes) { change -> Text("${change.field}\n${change.before.orEmpty()}") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Secondary("取消", !saving, cancel)
            if (ready != null || saving) Primary("确认移除", !saving) {
                if (ready != null) submission.submit { model.confirmProposal(ready.proposalId) }
            } else if (preview is PreviewResult.Rejected) Secondary("重新预览") { preview = null; version++ }
        }
    }
}
