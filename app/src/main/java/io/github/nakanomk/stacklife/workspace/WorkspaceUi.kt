package io.github.nakanomk.stacklife.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.planner.PlannerCommand
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

typealias SharedModifier = @Composable (String) -> Modifier

internal val pagePadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp)
internal val listSpacing = Arrangement.spacedBy(12.dp)

@Composable
internal fun Page(content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize(), content = content)
        }
    }
}

@Composable
internal fun PageTitle(title: String, back: (() -> Unit)? = null, backEnabled: Boolean = true, settings: (() -> Unit)? = null,
    assistantPage: AssistantPage? = LocalAssistantPage.current) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) TextButton(onClick = back, enabled = backEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
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
