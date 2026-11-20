package io.github.nakanomk.stacklife.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.DockOverlay
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.planner.NavStyle
import io.github.nakanomk.stacklife.planner.PlannerCommand
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

typealias SharedModifier = @Composable (String) -> Modifier

private val roomyPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp)
private val tightPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp)
private val roomySpacing = Arrangement.spacedBy(8.dp)
private val tightSpacing = Arrangement.spacedBy(4.dp)
internal val pagePadding: PaddingValues @Composable get() = if (LocalAppearance.current.compact) tightPadding else roomyPadding
internal val listSpacing: Arrangement.Vertical @Composable get() = if (LocalAppearance.current.compact) tightSpacing else roomySpacing

@Composable
internal fun Page(assistantPage: AssistantPage? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val docked = LocalAppearance.current.navStyle == NavStyle.DOCKED
    Surface(modifier = Modifier.fillMaxSize(), color = if (docked) colors.surfaceContainer else colors.surface) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().background(colors.surface), contentAlignment = Alignment.TopCenter) {
            Box((if (docked) Modifier else Modifier.widthIn(max = 760.dp)).fillMaxSize()) {
                if (assistantPage == null || LocalAssistant.current == null) Column(Modifier.fillMaxSize(), content = content)
                else DockOverlay(assistantPage = assistantPage) { clearance ->
                    Column(Modifier.fillMaxSize().padding(bottom = clearance), content = content)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageTitle(title: String, back: (() -> Unit)? = null, backEnabled: Boolean = true, settings: (() -> Unit)? = null) {
    CenterAlignedTopAppBar(
        title = { Text(title, Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { if (back != null) ToolbarIcon(R.drawable.ic_arrow_back, "返回", backEnabled, back) },
        actions = { if (settings != null) ToolbarIcon(R.drawable.ic_settings, "设置", onClick = settings) },
        windowInsets = WindowInsets(0), expandedHeight = 56.dp,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
}

@Composable
internal fun ToolbarIcon(icon: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(painterResource(icon), description) }
}

@Composable
internal fun PreferenceRow(label: String, value: String? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 52.dp)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun lineFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
    unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant)

@Composable
internal fun SectionTitle(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Actions(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
internal fun Primary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) { Text(label) }
}

@Composable
internal fun Secondary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) { Text(label) }
}

@Composable
internal fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 48.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun List<String>.toggle(id: String): List<String> = if (id in this) this - id else this + id

private val dateTimeFormat = DateTimeFormatter.ofPattern("uuuu年M月d日 HH:mm", Locale.CHINA)
private val shortDateTime = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
private val shortClock = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
internal fun savedTime(millis: Long): String = dateTimeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
internal fun scheduledRange(start: Long, end: Long): String {
    val zone = ZoneId.systemDefault()
    val from = Instant.ofEpochMilli(start).atZone(zone)
    val to = Instant.ofEpochMilli(end).atZone(zone)
    return "${from.format(shortDateTime)}–${to.format(if (from.toLocalDate() == to.toLocalDate()) shortClock else shortDateTime)}"
}

internal class Submission(
    private val actions: WorkspaceActions,
    private val pending: MutableState<String?>,
) {
    val busy: Boolean get() = pending.value != null

    fun send(command: PlannerCommand): Boolean = submit { actions.start(command) }

    inline fun submit(start: () -> String?): Boolean {
        if (busy) return false
        val id = start() ?: return false
        pending.value = id
        return true
    }
}

@Composable
internal fun rememberSubmission(actions: WorkspaceActions, onError: ((String) -> Unit)? = null,
    onResult: (SaveOutcome) -> Unit = {}): Submission {
    // Save the receipt identity, never a command to replay. Rotation only restarts the await.
    val pending = rememberSaveable { mutableStateOf<String?>(null) }
    val currentHandler by rememberUpdatedState(onResult)
    val currentError by rememberUpdatedState(onError)
    val requestId = pending.value
    LaunchedEffect(actions, requestId) {
        if (requestId != null) {
            val outcome = actions.result(requestId, currentError)
            pending.value = null
            currentHandler(outcome)
        }
    }
    return remember(actions, pending) { Submission(actions, pending) }
}
