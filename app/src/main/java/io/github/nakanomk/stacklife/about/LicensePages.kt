package io.github.nakanomk.stacklife.about

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.FrostedCard
import io.github.nakanomk.stacklife.transitions.PaneOrigins
import io.github.nakanomk.stacklife.transitions.paneOrigin
import kotlinx.coroutines.launch

internal const val PROJECT_LICENSE = "StackLife-GPL-3.0-or-later.txt"
private val licenseSuffix = Regex("-(Apache-2\\.0|GPL-3\\.0(-or-later)?|EPL-2\\.0|LGPL-2\\.1|MIT|LICENSE)$")
private fun componentName(file: String) = file.removeSuffix(".txt").replace(licenseSuffix, "").replace('-', ' ')

@Composable
internal fun LicenseIndex(files: Load<List<String>>, padding: PaddingValues, origins: PaneOrigins, open: (String) -> Unit) {
    if (files !is Load.Ready) {
        AboutList(padding) { item { files.display {} } }
        return
    }
    val thirdParty = remember(files) { files.value.filterNot { it == PROJECT_LICENSE } }
    AboutList(padding, Modifier.testTag("about-license-list")) {
        if (PROJECT_LICENSE in files.value) {
            item { AboutHeading(stringResource(R.string.about_project_license)) }
            item(key = PROJECT_LICENSE) { LicenseEntry(PROJECT_LICENSE, origins, open) }
        }
        item { AboutHeading(stringResource(R.string.about_third_party)) }
        items(thirdParty, key = { it }) { LicenseEntry(it, origins, open) }
        if (files.value.isEmpty()) item { Text(stringResource(R.string.about_read_failed), color = MaterialTheme.colorScheme.error) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseEntry(file: String, origins: PaneOrigins, open: (String) -> Unit) {
    FrostedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, provideContainerUnderlay = true) {
        ListItem(modifier = Modifier.paneOrigin(origins, "license:$file").testTag("license:$file")
            .clickable(role = Role.Button) { open(file) },
            supportingContent = { Text(file, style = MaterialTheme.typography.bodyMedium) },
            trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)) {
            Text(remember(file) { componentName(file) }, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private val paragraphBreak = Regex("\\r?\\n(?:[ \\t]*\\r?\\n)+")
private class Notice(val original: String) {
    // Only index the source here. Text layout, selection and substring creation are limited to visible paragraphs.
    val paragraphs = buildList {
        var start = 0
        for (separator in paragraphBreak.findAll(original)) {
            if (start < separator.range.first) add(start until separator.range.first)
            start = separator.range.last + 1
        }
        if (start < original.length) add(start until original.length)
    }
}

@Composable
internal fun LicenseReader(file: String, padding: PaddingValues) {
    val assets = LocalContext.current.assets
    val notice = localRead(file) { assets.open("licenses/$file").bufferedReader(Charsets.UTF_8).use { Notice(it.readText()) } }
    if (notice !is Load.Ready) {
        AboutList(padding) { item { notice.display {} } }
        return
    }
    val document = notice.value
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // A continuous, contrast-stable reading surface; the frame never measures the entire license at once.
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        FrostedCard(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
            LazyColumn(Modifier.testTag("about-license-reader"), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AboutHeading(componentName(file))
                        ExactValue(file)
                        TextButton(onClick = { scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(file, document.original)))
                        } }) { Text(stringResource(R.string.about_copy_notice)) }
                        HorizontalDivider()
                    }
                }
                items(document.paragraphs.size) { index ->
                    // Drop the shared print margin (e.g. the centered GPL title), not relative indentation within examples.
                    val paragraph = remember(document, index) { document.original.substring(document.paragraphs[index]).trimIndent() }
                    SelectionContainer { Text(paragraph, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}
