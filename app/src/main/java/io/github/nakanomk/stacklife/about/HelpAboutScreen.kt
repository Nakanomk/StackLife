package io.github.nakanomk.stacklife.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.workspace.*
import io.github.nakanomk.stacklife.courses.SchoolImports
import io.github.nakanomk.stacklife.transitions.PaneMotion
import io.github.nakanomk.stacklife.transitions.PaneOrigins
import io.github.nakanomk.stacklife.transitions.paneOrigin
import io.github.nakanomk.stacklife.updates.STACKLIFE_REPOSITORY
import io.github.nakanomk.stacklife.updates.UpdateSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PROJECT_LICENSE = "StackLife-GPL-3.0-or-later.txt"
private enum class AboutPage(val title: Int) {
    HOME(R.string.about_title), HELP(R.string.about_help), BUILD(R.string.about_build),
    LICENSES(R.string.about_licenses), TEXT(R.string.about_license_text), DIAGNOSTICS(R.string.about_diagnostics),
}

private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data object Failed : Load<Nothing>
    class Ready<T>(val value: T) : Load<T>
}

@Composable
private fun <T> localRead(input: Any?, read: () -> T): Load<T> = key(input) {
    val state by produceState<Load<T>>(Load.Loading) {
        value = try { Load.Ready(withContext(Dispatchers.IO) { read() }) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { Load.Failed }
    }
    state
}

@Composable
private fun <T> Load<T>.display(content: @Composable (T) -> Unit) {
    when (this) {
        Load.Loading -> Hint(stringResource(R.string.about_loading))
        Load.Failed -> Hint(stringResource(R.string.about_read_failed))
        is Load.Ready -> content(value)
    }
}

@Composable
internal fun HelpAboutScreen(back: () -> Unit, onGuide: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    var page by rememberSaveable { mutableStateOf(AboutPage.HOME) }
    var license by rememberSaveable { mutableStateOf("") }
    var licenseParent by rememberSaveable { mutableStateOf(AboutPage.LICENSES) }
    val origins = remember { PaneOrigins() }
    fun origin(entry: AboutPage) = if (entry == AboutPage.TEXT) "license:$license" else entry.name
    fun openLicense(file: String, parent: AboutPage) {
        license = file
        licenseParent = parent
        page = AboutPage.TEXT
    }
    var noBrowser by rememberSaveable { mutableStateOf(false) }
    val version = localRead(app) { installedVersion(app) }
    val holder = rememberSaveableStateHolder()
    val up: () -> Unit = {
        when (page) {
            AboutPage.HOME -> back()
            AboutPage.TEXT -> page = licenseParent
            else -> page = AboutPage.HOME
        }
    }
    PaneMotion(page, origins, depth = { when (it) { AboutPage.HOME -> 0; AboutPage.TEXT -> 2; else -> 1 } },
        originKey = ::origin, modifier = Modifier.fillMaxSize(),
        parent = when (page) { AboutPage.HOME -> null; AboutPage.TEXT -> licenseParent; else -> AboutPage.HOME }, onBack = up) { shown ->
        SettingsHelpHost {
        EditorPage(stringResource(shown.title), up) { top, bottom ->
        holder.SaveableStateProvider(origin(shown)) {
            val padding = dockListPadding(top, bottom)
            when (shown) {
                AboutPage.DIAGNOSTICS -> DiagnosticsContent(padding)
                AboutPage.LICENSES -> {
                    val files = localRead(app) { app.assets.list("licenses").orEmpty().filterNot { it == PROJECT_LICENSE }.sorted() }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                        item {
                            PreferenceRow(stringResource(R.string.about_project_license), "GPL-3.0-or-later",
                                Modifier.paneOrigin(origins, "license:$PROJECT_LICENSE").testTag("license:$PROJECT_LICENSE")) {
                                openLicense(PROJECT_LICENSE, AboutPage.LICENSES)
                            }
                        }
                        when (files) {
                            is Load.Ready -> {
                                if (files.value.isEmpty()) item { Hint(stringResource(R.string.about_read_failed)) }
                                items(files.value, key = { it }) { file ->
                                    PreferenceRow(file.removeSuffix(".txt"), modifier = Modifier.paneOrigin(origins, "license:$file").testTag("license:$file")) {
                                        openLicense(file, AboutPage.LICENSES)
                                    }
                                }
                            }
                            else -> item { files.display {} }
                        }
                    }
                }
                AboutPage.TEXT -> {
                    val text = localRead(license) {
                        app.assets.open("licenses/$license").bufferedReader(Charsets.UTF_8).use {
                            it.readText().replace("\r\n", "\n").trimEnd().split("\n\n")
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { SectionTitle(license.removeSuffix(".txt")) }
                        when (text) {
                            is Load.Ready -> items(text.value) { paragraph ->
                                SelectionContainer { Text(paragraph, style = MaterialTheme.typography.bodyMedium) }
                            }
                            else -> item { text.display {} }
                        }
                    }
                }
                AboutPage.HELP -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = listSpacing) {
                    item { PreferenceRow(stringResource(R.string.guide_reopen), modifier = Modifier.testTag("open-guidance"), onClick = onGuide) }
                    item { HelpSection(R.string.about_help_workspace, R.string.about_help_workspace_body) }
                    item { HelpSection(R.string.about_help_today, R.string.about_help_today_body) }
                    item { HelpSection(R.string.about_help_graphs, R.string.about_help_graphs_body) }
                    item { FormGroup {
                        SectionTitle(stringResource(R.string.about_help_courses))
                        Text(stringResource(R.string.about_help_courses_body), style = MaterialTheme.typography.bodyMedium)
                        for (provider in SchoolImports.providers) Text(stringResource(provider.help), style = MaterialTheme.typography.bodyMedium)
                    } }
                    item { HelpSection(R.string.about_help_countdowns, R.string.about_help_countdowns_body) }
                    item { HelpSection(R.string.about_help_assistant, R.string.about_help_assistant_body) }
                }
                AboutPage.BUILD -> {
                    val apk = localRead(app) { baseApkDigest(app) }
                    val signers = localRead(app) { signingDigests(app) }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = listSpacing) {
                        item { UpdateSettings() }
                        item { FormGroup {
                            SectionTitle(stringResource(R.string.about_project))
                            Text(stringResource(R.string.about_developer))
                            SelectionContainer { Text(STACKLIFE_REPOSITORY) }
                            PreferenceRow(stringResource(R.string.about_open_repo), modifier = Modifier.testTag("about-repository")) {
                                noBrowser = try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(STACKLIFE_REPOSITORY))); false }
                                catch (_: android.content.ActivityNotFoundException) { true }
                                catch (_: SecurityException) { true }
                            }
                            if (noBrowser) Hint(stringResource(R.string.about_link_failed))
                        } }
                        item { FormGroup {
                            SectionTitle(stringResource(R.string.about_project_license))
                            Text(stringResource(R.string.about_project_license_body), style = MaterialTheme.typography.bodyMedium)
                            PreferenceRow(stringResource(R.string.about_license_text), "GPL-3.0-or-later",
                                Modifier.paneOrigin(origins, "license:$PROJECT_LICENSE").testTag("about-project-license")) {
                                openLicense(PROJECT_LICENSE, AboutPage.BUILD)
                            }
                        } }
                        item { FormGroup {
                            SettingLabel(stringResource(R.string.about_apk), stringResource(R.string.about_apk_hint))
                            apk.display { SelectionContainer { Text(it, modifier = Modifier.testTag("about-apk-sha256")) } }
                        } }
                        item { FormGroup {
                            SectionTitle(stringResource(R.string.about_signer))
                            signers.display { fingerprints ->
                                if (fingerprints.isEmpty()) Hint(stringResource(R.string.about_read_failed))
                                fingerprints.forEach { SelectionContainer { Text(it) } }
                            }
                        } }
                    }
                }
                AboutPage.HOME -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { AboutIdentity {
                        version.display { Text(stringResource(R.string.about_version, it.name ?: "—", it.code),
                            modifier = Modifier.testTag("about-version"), style = MaterialTheme.typography.labelLarge) }
                    } }
                    item { FormGroup {
                        listOf(AboutPage.HELP, AboutPage.DIAGNOSTICS, AboutPage.BUILD, AboutPage.LICENSES).forEach { entry ->
                            PreferenceRow(stringResource(entry.title), modifier = Modifier.paneOrigin(origins, origin(entry))
                                .testTag("about-${entry.name.lowercase(java.util.Locale.ROOT)}"),
                                help = if (entry == AboutPage.LICENSES) stringResource(R.string.about_license_intro) else null) { page = entry }
                        }
                    } }
                }
            }
        }
        }
        }
    }
}

@Composable
private fun HelpSection(title: Int, body: Int) {
    FormGroup { SectionTitle(stringResource(title)); Text(stringResource(body), style = MaterialTheme.typography.bodyMedium) }
}
