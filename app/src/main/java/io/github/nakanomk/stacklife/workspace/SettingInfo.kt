package io.github.nakanomk.stacklife.workspace

// MD3 branch adapted from BiliPai AdaptiveTooltip.kt and preference titleTrailingContent,
// 5893c2c80739e3759ac64ae5f232cf03a94da7f8 (GPL-3.0). StackLife adds tap, scroll and Back dismissal.
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import kotlinx.coroutines.launch

private class SettingHelp : NestedScrollConnection {
    var dismiss by mutableStateOf<(() -> Unit)?>(null)
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available != Offset.Zero) dismiss?.invoke()
        return Offset.Zero
    }
}
private val LocalSettingHelp = staticCompositionLocalOf<SettingHelp?> { null }

@Composable
internal fun SettingsHelpHost(content: @Composable () -> Unit) {
    val host = remember { SettingHelp() }
    CompositionLocalProvider(LocalSettingHelp provides host) {
        Box(Modifier.nestedScroll(host), propagateMinConstraints = true) { content() }
        BackHandler(host.dismiss != null) { host.dismiss?.invoke() }
    }
}

/** The toolbar and the system Back callback dismiss open help before leaving the page. */
@Composable
internal fun backAfterSettingHelp(back: () -> Unit): () -> Unit {
    val host = LocalSettingHelp.current
    return { val dismiss = host?.dismiss; if (dismiss != null) dismiss() else back() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingInfo(help: String, modifier: Modifier = Modifier, title: String = help) {
    val host = LocalSettingHelp.current
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val dismiss = remember(state) { { state.dismiss() } }
    if (state.isVisible) DisposableEffect(host, state) {
        host?.dismiss?.takeUnless { it === dismiss }?.invoke()
        host?.dismiss = dismiss
        onDispose {
            if (host?.dismiss === dismiss) host.dismiss = null
            state.dismiss()
        }
    }
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), state = state,
        tooltip = { PlainTooltip { Text(help) } }, modifier = modifier, focusable = false, onDismissRequest = dismiss) {
        IconButton(onClick = { if (state.isVisible) dismiss() else scope.launch { state.show() } }, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_info), stringResource(R.string.setting_info, title), Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SettingLabel(title: String, help: String?, modifier: Modifier = Modifier, maxLines: Int = 2) {
    if (help == null) Text(title, modifier, style = MaterialTheme.typography.bodyLarge, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    else Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        SettingInfo(help, title = title)
    }
}

@Composable
internal fun SettingHeading(title: String, help: String, style: TextStyle = MaterialTheme.typography.titleMedium) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f, fill = false).semantics { heading() }, style = style)
        SettingInfo(help, title = title)
    }
}
