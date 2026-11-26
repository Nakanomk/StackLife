package io.github.nakanomk.stacklife.about

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.appearance.FrostedCard
import io.github.nakanomk.stacklife.workspace.SettingInfo

// Design references, implemented with StackLife/MD3 (no upstream code or artwork copied):
// BiliPai AppPreferenceGroup/AppPreference: a card bounds related entries; title help is a separate action.
// https://github.com/jay3-yy/BiliPai/blob/5893c2c80739e3759ac64ae5f232cf03a94da7f8/design-system/src/main/java/com/android/purebilibili/core/ui/components/AppPreferenceComponents.kt
// Now in Android SettingsDialogSectionTitle/LinksPanel: titleMedium sections and explicit link actions.
// https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/feature/settings/impl/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/settings/impl/SettingsDialog.kt
@Composable
internal fun AboutList(padding: PaddingValues, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(modifier.widthIn(max = 640.dp).fillMaxSize(), contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun AboutCard(padding: PaddingValues = PaddingValues(16.dp), spacing: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    provideContainerUnderlay: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    FrostedCard(Modifier.fillMaxWidth(), provideContainerUnderlay = provideContainerUnderlay) {
        Column(Modifier.padding(padding), verticalArrangement = spacing, content = content)
    }
}

@Composable
internal fun AboutHeading(title: String, help: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f, fill = false).semantics { heading() },
            style = MaterialTheme.typography.titleMedium)
        if (help != null) SettingInfo(help, title = title)
    }
}

@Composable
internal fun AboutLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun ExactValue(text: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        Text(text, modifier.fillMaxWidth(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}
