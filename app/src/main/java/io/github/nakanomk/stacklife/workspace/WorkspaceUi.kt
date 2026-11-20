package io.github.nakanomk.stacklife.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.appearance.AssistantGlassPage
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.planner.NavStyle
import io.github.nakanomk.stacklife.planner.PlannerCommand
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

typealias SharedModifier = @Composable (String) -> Modifier

private val roomyPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp)
private val tightPadding = PaddingValues(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 12.dp)
private val roomySpacing = Arrangement.spacedBy(12.dp)
private val tightSpacing = Arrangement.spacedBy(6.dp)
internal val pagePadding: PaddingValues @Composable get() = if (LocalAppearance.current.compact) tightPadding else roomyPadding
internal val listSpacing: Arrangement.Vertical @Composable get() = if (LocalAppearance.current.compact) tightSpacing else roomySpacing

@Composable
internal fun Page(content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val docked = LocalAppearance.current.navStyle == NavStyle.DOCKED
    Surface(modifier = Modifier.fillMaxSize(), color = if (docked) colors.surfaceContainer else colors.surface) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().background(colors.surface), contentAlignment = Alignment.TopCenter) {
            Box((if (docked) Modifier else Modifier.widthIn(max = 760.dp)).fillMaxSize()) {
                AssistantGlassPage { Column(Modifier.fillMaxSize(), content = content) }
            }
        }
    }
}

@Composable
internal fun PageTitle(title: String, back: (() -> Unit)? = null, backEnabled: Boolean = true, settings: (() -> Unit)? = null,
    assistantPage: AssistantPage? = LocalAssistantPage.current) {
    val config = LocalConfiguration.current
    val largeType = LocalDensity.current.fontScale >= 1.4f
    val scroll = largeType && (config.screenHeightDp < 480 || config.screenWidthDp < 480)
    val row = if (scroll) Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()
    Row(row, verticalAlignment = Alignment.CenterVertically) {
        if (back != null) TextButton(onClick = back, enabled = backEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
        Text(title, (if (scroll) Modifier.padding(end = 8.dp) else Modifier.weight(1f)).semantics { heading() },
            style = if (largeType) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge)
        AssistantEntry(assistantPage)
        if (settings != null) TextButton(onClick = settings, modifier = Modifier.heightIn(min = 48.dp)) { Text("设置") }
    }
}

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
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}

@Composable
internal fun Secondary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
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

private val dateTimeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
internal fun savedTime(millis: Long): String = dateTimeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

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
