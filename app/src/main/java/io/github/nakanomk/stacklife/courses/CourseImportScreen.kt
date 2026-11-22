package io.github.nakanomk.stacklife.courses

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.workspace.EditorPage
import io.github.nakanomk.stacklife.workspace.PreferenceRow
import io.github.nakanomk.stacklife.workspace.dockListPadding

@Composable
internal fun CourseImportScreen(model: WorkspaceViewModel, onBack: () -> Unit, onImported: (String) -> Unit) {
    // A single installed provider opens directly; no extra school-selection step.
    var selected by rememberSaveable { mutableStateOf(SchoolImports.providers.singleOrNull()?.id) }
    val provider = selected?.let(SchoolImports::find)
    if (provider != null) {
        provider.Import(model, onBack = { if (SchoolImports.providers.size == 1) onBack() else selected = null }, onImported = onImported)
    } else {
        EditorPage(stringResource(R.string.courses_choose_source), onBack) { top, bottom ->
            LazyColumn(contentPadding = dockListPadding(top, bottom)) {
                items(SchoolImports.providers, key = { it.id }) { school ->
                    PreferenceRow(stringResource(school.label)) { selected = school.id }
                }
            }
        }
    }
}
