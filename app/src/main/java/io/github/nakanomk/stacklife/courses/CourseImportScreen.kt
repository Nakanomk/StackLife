package io.github.nakanomk.stacklife.courses

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.transitions.PaneMotion
import io.github.nakanomk.stacklife.transitions.PaneOrigins
import io.github.nakanomk.stacklife.transitions.paneOrigin
import io.github.nakanomk.stacklife.workspace.EditorPage
import io.github.nakanomk.stacklife.workspace.PreferenceRow
import io.github.nakanomk.stacklife.workspace.dockListPadding

private sealed class SourcePane(val depth: Int, val key: String) {
    data object Method : SourcePane(0, "method")
    data object Schools : SourcePane(1, "schools")
    data class Provider(val school: SchoolImportProvider, val entry: ImportEntry) :
        SourcePane(if (entry == ImportEntry.WEB) 2 else 1, "$entry:${school.id}")
}

@Composable
internal fun CourseImportScreen(model: WorkspaceViewModel, onBack: () -> Unit, onImported: (String) -> Unit) {
    var entry by rememberSaveable { mutableStateOf<ImportEntry?>(null) }
    var schoolId by rememberSaveable { mutableStateOf<String?>(null) }
    val origins = remember { PaneOrigins() }
    val school = schoolId?.let(SchoolImports::find)
    val page = when (entry) {
        null -> SourcePane.Method
        ImportEntry.FILE -> SourcePane.Provider(HustCourseImporter, ImportEntry.FILE)
        ImportEntry.WEB -> school?.let { SourcePane.Provider(it, ImportEntry.WEB) } ?: SourcePane.Schools
    }
    val parent = when (page) {
        SourcePane.Method -> null
        SourcePane.Schools -> SourcePane.Method
        is SourcePane.Provider -> if (page.entry == ImportEntry.WEB) SourcePane.Schools else SourcePane.Method
    }
    fun back() {
        when (parent) {
            null -> onBack()
            SourcePane.Schools -> schoolId = null
            else -> { schoolId = null; entry = null }
        }
    }
    PaneMotion(page, origins, { it.depth }, { it.key }, Modifier.fillMaxSize(), parent, ::back) { shown ->
        when (shown) {
            is SourcePane.Provider -> shown.school.Import(model, shown.entry, onBack = ::back, onCancel = onBack,
                onFileEntry = { schoolId = null; entry = ImportEntry.FILE }, onImported = onImported)
            SourcePane.Schools -> EditorPage(stringResource(R.string.courses_choose_school), ::back, AssistantPage.Terms) { top, bottom ->
                LazyColumn(contentPadding = dockListPadding(top, bottom)) {
                    items(SchoolImports.providers, key = { it.id }) { provider ->
                        PreferenceRow(stringResource(provider.label), modifier = Modifier.paneOrigin(origins, SourcePane.Provider(provider, ImportEntry.WEB).key)
                            .testTag("importSchool:${provider.id}"), labelMaxLines = 1) { schoolId = provider.id }
                    }
                }
            }
            SourcePane.Method -> EditorPage(stringResource(R.string.courses_choose_source), onBack, AssistantPage.Terms) { top, bottom ->
                LazyColumn(contentPadding = dockListPadding(top, bottom)) {
                    item {
                        PreferenceRow(stringResource(R.string.courses_import_web), modifier = Modifier.paneOrigin(origins, SourcePane.Schools.key)
                            .testTag("importMethodWeb"), labelMaxLines = 1) { entry = ImportEntry.WEB }
                    }
                    item {
                        PreferenceRow(stringResource(R.string.courses_import_file), modifier = Modifier.paneOrigin(origins, SourcePane.Provider(HustCourseImporter, ImportEntry.FILE).key)
                            .testTag("importMethodFile"), labelMaxLines = 1) { entry = ImportEntry.FILE }
                    }
                }
            }
        }
    }
}
