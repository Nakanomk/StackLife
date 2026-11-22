package io.github.nakanomk.stacklife.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.today.SlotEditorKey
import io.github.nakanomk.stacklife.workspace.*

/** NiA ForYouScreen: real selection before explicit completion; no sample rows or startup permissions. */
@Composable
internal fun OnboardingScreen(settings: PolicyView, model: WorkspaceViewModel, onBack: () -> Unit,
    onSchedule: (SlotEditorKey) -> Unit, onAdd: () -> Unit, onImport: () -> Unit) {
    val baselineJson = rememberSaveable { encodeAppearance(settings.appearance) }
    val baseline = remember(baselineJson) { decodeAppearance(baselineJson) }
    var selected by rememberSaveable { mutableStateOf(ArrayList(baseline.visible.map { it.name })) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val receipt = rememberSubmission(model, { error = it }) { if (it == SaveOutcome.APPLIED) onBack() }
    fun leave(saveChoice: Boolean) {
        val choice = if (saveChoice) selected.mapTo(linkedSetOf(), Destination::valueOf) else null
        if (settings.onboardingVersion >= 1 && (choice == null || choice == settings.appearance.visible)) onBack()
        else receipt.send(PlannerCommand.CompleteOnboarding(choice, if (saveChoice) baseline else null))
    }
    val back = { if (!receipt.busy) { if (step > 0) step-- else onBack() }; Unit }
    BackHandler(onBack = back)
    val title = when (step) { 0 -> R.string.guide_features; 1 -> R.string.guide_routines; else -> R.string.guide_first }
    EditorPage(stringResource(title), back, backEnabled = !receipt.busy, footer = {
        Actions {
            ActionPill(stringResource(R.string.guide_later), { leave(false) }, Modifier.testTag("guide-later"), !receipt.busy)
            ActionPill(stringResource(if (step < 2) R.string.guide_next else R.string.ui_done),
                { if (step < 2) step++ else leave(true) }, Modifier.testTag("guide-next"), !receipt.busy && selected.isNotEmpty(), prominent = true)
        }
    }) { top, bottom ->
        LazyColumn(Modifier.fillMaxSize().testTag("guide:$step"), contentPadding = dockListPadding(top, bottom), verticalArrangement = listSpacing) {
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Hint(stringResource(when (step) { 0 -> R.string.guide_features_hint; 1 -> R.string.guide_routines_hint; else -> R.string.guide_first_hint })) }
            when (step) {
                0 -> item { FormGroup {
                    for (dest in baseline.order) {
                        val checked = dest.name in selected
                        val enabled = !receipt.busy && (!checked || selected.size > 1)
                        Row(Modifier.fillMaxWidth().testTag("guide-feature:${dest.key}").toggleable(checked, enabled, role = Role.Checkbox) { on ->
                            selected = ArrayList(selected).apply { if (on) add(dest.name) else remove(dest.name) }
                        }.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Checkbox(checked, onCheckedChange = null, enabled = enabled); Text(dest.label)
                        }
                    }
                } }
                1 -> item { FormGroup { RoutineScheduleRows(model, onSchedule) } }
                else -> item { FormGroup {
                    PreferenceRow(stringResource(R.string.ui_add_task), modifier = Modifier.testTag("guide-add"), onClick = onAdd)
                    HorizontalDivider()
                    PreferenceRow(stringResource(R.string.courses_import), modifier = Modifier.testTag("guide-import"), onClick = onImport)
                } }
            }
        }
    }
}
