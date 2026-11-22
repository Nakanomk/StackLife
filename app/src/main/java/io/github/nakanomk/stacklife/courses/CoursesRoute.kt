package io.github.nakanomk.stacklife.courses

import io.github.nakanomk.stacklife.R
import androidx.compose.ui.res.stringResource
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
    onWeek: (Int) -> Unit, onImport: () -> Unit, onRemove: (TermSpec, String) -> Unit, onSettings: () -> Unit,
    onScope: (AssistantPage) -> Unit) {
    val state by remember(model) { model.terms() }.collectAsStateWithLifecycle(initialValue = ReadState.Loading)
    ReadPanel(state) { all ->
        fun today(id: String): LocalDate = LocalDate.now(calendarFor(all.importedSources[id]?.firstOrNull() ?: MANUAL_SOURCE)?.zone ?: java.time.ZoneId.systemDefault())
        val term = all.terms.firstOrNull { it.id == termId } ?: all.terms.firstOrNull {
            val date = today(it.id)
            date >= it.firstMonday && date < it.firstMonday.plusWeeks(it.weeks.toLong())
        } ?: all.terms.firstOrNull()
        val week = term?.let {
            (selectedWeek?.takeIf { term.id == termId } ?: (ChronoUnit.WEEKS.between(term.firstMonday, today(term.id)).toInt() + 1))
                .coerceIn(1, term.weeks)
        }
        val scope = if (term != null && week != null) AssistantPage.Week(term.id, week) else AssistantPage.Terms
        SideEffect { onScope(scope) }
        CourseHome(model, all, term, week, onTerm, { selected, n -> onTerm(selected); onWeek(n) },
            onImport, onRemove, onSettings)
    }
}

@Composable
internal fun RemoveTermReview(id: String, source: String, model: WorkspaceViewModel, onBack: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<PreviewResult?>(null) }
    val submission = rememberSubmission(model, onError = { preview = PreviewResult.Rejected(it) }) { outcome ->
        if (outcome == SaveOutcome.APPLIED) onBack()
    }
    val saving = submission.busy
    val cancel: () -> Unit = { if (!submission.busy) onBack() }
    val ready = preview as? PreviewResult.Ready
    LaunchedEffect(model, id, source, version) {
        if (!saving) preview = model.previewRemoval(id, source)
    }
    DisposableEffect(model, id, source) {
        onDispose {
            if (!submission.busy) (preview as? PreviewResult.Ready)?.let { model.discardProposal(it.proposalId) }
        }
    }
    BackHandler(enabled = saving) { /* Do not replay or cancel an accepted removal on Back. */ }
    EditorPage(stringResource(R.string.courses_remove_term), cancel, AssistantPage.Week(id, 1), backEnabled = !saving, footer = {
        Actions {
            Secondary(stringResource(R.string.courses_cancel), !saving, cancel)
            if (ready != null || saving) Primary(stringResource(R.string.courses_confirm_remove), !saving) {
                if (ready != null) submission.submit { model.confirmProposal(ready.proposalId) }
            } else if (preview is PreviewResult.Rejected) Secondary(stringResource(R.string.courses_preview_again)) { preview = null; version++ }
        }
    }) { top, bottom ->
        LazyColumn(contentPadding = dockListPadding(top, bottom), verticalArrangement = listSpacing) {
            item { Text(id, style = MaterialTheme.typography.titleLarge); Hint(stringResource(R.string.courses_remove_term_hint, sourceLabel(source))) }
            when (val result = preview) {
                null -> item { CircularProgressIndicator() }
                is PreviewResult.Rejected -> item { Text(result.message) }
                is PreviewResult.Ready -> items(result.changes) { change -> Text("${change.field}\n${change.before.orEmpty()}") }
            }
        }
    }
}
