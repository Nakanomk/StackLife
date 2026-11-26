package io.github.nakanomk.stacklife.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.HorizontalDivider
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
import io.github.nakanomk.stacklife.appearance.CardFrostGroup
import io.github.nakanomk.stacklife.courses.SchoolImports
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.transitions.PaneMotion
import io.github.nakanomk.stacklife.transitions.PaneOrigins
import io.github.nakanomk.stacklife.transitions.paneOrigin
import io.github.nakanomk.stacklife.updates.STACKLIFE_REPOSITORY
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class AboutPage(val title: Int) {
    HOME(R.string.about_title), HELP(R.string.about_help), BUILD(R.string.about_build),
    LICENSES(R.string.about_licenses), TEXT(R.string.about_license_text), DIAGNOSTICS(R.string.about_diagnostics),
}

internal sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data object Failed : Load<Nothing>
    class Ready<T>(val value: T) : Load<T>
}

@Composable
internal fun <T> localRead(input: Any?, read: () -> T): Load<T> = key(input) {
    val state by produceState<Load<T>>(Load.Loading) {
        value = try { Load.Ready(withContext(Dispatchers.IO) { read() }) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { Load.Failed }
    }
    state
}

@Composable
internal fun <T> Load<T>.display(content: @Composable (T) -> Unit) {
    when (this) {
        Load.Loading -> Hint(stringResource(R.string.about_loading))
        Load.Failed -> Text(stringResource(R.string.about_read_failed), color = MaterialTheme.colorScheme.error)
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
    val version = localRead(app) { installedVersion(app) }
    // Keep the index and its restored scroll anchor ready when predictive Back reveals it behind a notice.
    var indexOpened by remember { mutableStateOf(page == AboutPage.LICENSES || (page == AboutPage.TEXT && licenseParent == AboutPage.LICENSES)) }
    val files = if (indexOpened) localRead(app) { app.assets.list("licenses").orEmpty().sorted() } else Load.Loading
    val holder = rememberSaveableStateHolder()
    val up: () -> Unit = {
        when (page) {
            AboutPage.HOME -> back()
            AboutPage.TEXT -> page = licenseParent
            else -> page = AboutPage.HOME
        }
    }
    // The outgoing and incoming About pages replay the same wallpaper during their handoff.
    CardFrostGroup(enabled = true, modifier = Modifier.fillMaxSize()) {
    PaneMotion(page, origins, depth = { when (it) { AboutPage.HOME -> 0; AboutPage.TEXT -> 2; else -> 1 } },
        originKey = ::origin, modifier = Modifier.fillMaxSize(),
        parent = when (page) { AboutPage.HOME -> null; AboutPage.TEXT -> licenseParent; else -> AboutPage.HOME }, onBack = up) { shown ->
        SettingsHelpHost {
        EditorPage(stringResource(shown.title), up) { top, bottom ->
        holder.SaveableStateProvider(origin(shown)) {
            val padding = dockListPadding(top, bottom)
            when (shown) {
                AboutPage.DIAGNOSTICS -> DiagnosticsContent(padding)
                AboutPage.LICENSES -> LicenseIndex(files, padding, origins) { openLicense(it, AboutPage.LICENSES) }
                AboutPage.TEXT -> LicenseReader(license, padding)
                AboutPage.HELP -> HelpTopics(padding, onGuide)
                AboutPage.BUILD -> BuildDetails(padding, version, origins) { openLicense(PROJECT_LICENSE, AboutPage.BUILD) }
                AboutPage.HOME -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { AboutIdentity {
                        version.display { Text(stringResource(R.string.about_version, it.name ?: "—", it.code),
                            modifier = Modifier.testTag("about-version"), style = MaterialTheme.typography.labelLarge) }
                    } }
                    item { AboutCard(PaddingValues(horizontal = 14.dp, vertical = 4.dp), Arrangement.spacedBy(4.dp)) {
                        listOf(AboutPage.HELP, AboutPage.DIAGNOSTICS, AboutPage.BUILD, AboutPage.LICENSES).forEach { entry ->
                            PreferenceRow(stringResource(entry.title), modifier = Modifier.paneOrigin(origins, origin(entry))
                                .testTag("about-${entry.name.lowercase(java.util.Locale.ROOT)}"),
                                help = if (entry == AboutPage.LICENSES) stringResource(R.string.about_license_intro) else null) {
                                if (entry == AboutPage.LICENSES) indexOpened = true
                                page = entry
                            }
                        }
                    } }
                }
            }
        }
        }
        }
    }
    }
}

@Composable
private fun HelpTopics(padding: PaddingValues, onGuide: () -> Unit) {
    AboutList(padding) {
        item {
            AboutCard {
                AboutHeading(stringResource(R.string.about_get_started))
                Hint(stringResource(R.string.about_guide_intro))
                PreferenceRow(stringResource(R.string.guide_reopen), modifier = Modifier.testTag("open-guidance"),
                    labelMaxLines = Int.MAX_VALUE, onClick = onGuide)
            }
        }
        item { HelpTopic(Destination.WORKSPACE.label, R.string.about_help_workspace, R.string.about_help_workspace_body) }
        item { HelpTopic(Destination.TODAY.label, R.string.about_help_today, R.string.about_help_today_body) }
        item { HelpTopic(Destination.GRAPHS.label, R.string.about_help_graphs, R.string.about_help_graphs_body) }
        item {
            HelpTopic(Destination.COURSES.label, R.string.about_help_courses, R.string.about_help_courses_body) {
                SchoolImports.providers.forEach { provider ->
                    HorizontalDivider()
                    Hint(stringResource(provider.help))
                }
            }
        }
        item { HelpTopic(Destination.COUNTDOWN.label, R.string.about_help_countdowns, R.string.about_help_countdowns_body) }
        item { HelpTopic(stringResource(R.string.ui_assistant), R.string.about_help_assistant, R.string.about_help_assistant_body) }
    }
}

@Composable
private fun HelpTopic(topic: String, title: Int, body: Int, extra: @Composable () -> Unit = {}) {
    AboutCard {
        AboutLabel(topic)
        AboutHeading(stringResource(title))
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
        extra()
    }
}

@Composable
private fun BuildDetails(padding: PaddingValues, version: Load<InstalledVersion>, origins: PaneOrigins, license: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val apk = localRead(app) { baseApkDigest(app) }
    val signers = localRead(app) { signingDigests(app) }
    var noBrowser by rememberSaveable { mutableStateOf(false) }
    AboutList(padding, Modifier.testTag("about-build-list")) {
        item {
            AboutCard {
                AboutLabel(stringResource(R.string.about_installed))
                version.display {
                    Text(stringResource(R.string.about_version, it.name ?: "—", it.code),
                        Modifier.testTag("about-running-version"), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        item {
            AboutCard {
                AboutHeading(stringResource(R.string.about_project))
                Text(stringResource(R.string.about_developer), style = MaterialTheme.typography.bodyLarge)
                ExactValue(STACKLIFE_REPOSITORY)
                PreferenceRow(stringResource(R.string.about_open_repo), modifier = Modifier.testTag("about-repository"),
                    labelMaxLines = Int.MAX_VALUE) {
                    noBrowser = try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(STACKLIFE_REPOSITORY))); false }
                    catch (_: android.content.ActivityNotFoundException) { true }
                    catch (_: SecurityException) { true }
                }
                if (noBrowser) Text(stringResource(R.string.about_link_failed), color = MaterialTheme.colorScheme.error)
                HorizontalDivider()
                AboutHeading(stringResource(R.string.about_project_license))
                Hint(stringResource(R.string.about_project_license_body))
                PreferenceRow(stringResource(R.string.about_license_text), modifier = Modifier
                    .paneOrigin(origins, "license:$PROJECT_LICENSE").testTag("about-project-license"),
                    labelMaxLines = Int.MAX_VALUE, onClick = license)
            }
        }
        item {
            AboutCard {
                AboutLabel(stringResource(R.string.about_verify_install))
                AboutHeading(stringResource(R.string.about_apk), stringResource(R.string.about_apk_hint))
                apk.display { ExactValue(it, Modifier.testTag("about-apk-sha256")) }
                HorizontalDivider()
                AboutHeading(stringResource(R.string.about_signer))
                signers.display { fingerprints ->
                    if (fingerprints.isEmpty()) Text(stringResource(R.string.about_read_failed), color = MaterialTheme.colorScheme.error)
                    fingerprints.forEach { ExactValue(it, Modifier.testTag("about-signer-sha256")) }
                }
            }
        }
    }
}
