package io.github.nakanomk.stacklife.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.DockOverlay
import io.github.nakanomk.stacklife.GlassIcon
import io.github.nakanomk.stacklife.LocalRootBottom
import io.github.nakanomk.stacklife.RootPane
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.planner.NavStyle
import io.github.nakanomk.stacklife.planner.PlannerCommand
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

typealias SharedModifier = @Composable (String) -> Modifier

private val roomySpacing = Arrangement.spacedBy(8.dp)
private val tightSpacing = Arrangement.spacedBy(4.dp)
internal val listSpacing: Arrangement.Vertical @Composable get() = if (LocalAppearance.current.compact) tightSpacing else roomySpacing

@Composable
internal fun dockListPadding(top: Dp, bottom: Dp): PaddingValues {
    val compact = LocalAppearance.current.compact
    return remember(top, bottom, compact) {
        PaddingValues(start = 16.dp, end = 16.dp, top = top + if (compact) 0.dp else 4.dp,
            bottom = bottom + if (compact) 8.dp else 16.dp)
    }
}

@Composable
internal fun Page(assistantPage: AssistantPage? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val docked = LocalAppearance.current.navStyle == NavStyle.DOCKED
    Surface(modifier = Modifier.fillMaxSize(), color = if (docked) colors.surfaceContainer else colors.surface) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            val page = (if (docked) Modifier else Modifier.widthIn(max = 760.dp)).fillMaxSize()
            // Record the actual page surface, not its descendant buttons. Overlays that float
            // over scrolling data receive the nearer RootPane/DockOverlay content source.
            GlassBacking(page, background = { Spacer(Modifier.matchParentSize().background(colors.surface)) }) {
                if (assistantPage == null || LocalAssistant.current == null) Column(Modifier.fillMaxSize(), content = content)
                else DockOverlay(assistantPage = assistantPage) { clearance ->
                    CompositionLocalProvider(LocalRootBottom provides clearance) { Column(Modifier.fillMaxSize(), content = content) }
                }
            }
        }
    }
}

/** Header and optional dock are drawn after the scrolling plane; neither can record itself. */
@Composable
internal fun PageChrome(title: String, back: () -> Unit, backEnabled: Boolean = true,
    footer: (@Composable () -> Unit)? = null, content: @Composable (top: Dp, bottom: Dp) -> Unit) {
    val density = LocalDensity.current
    var footerPixels by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A landscape IME can leave less than two rows of controls plus one editable field.
        // Put the same action dock next to Back instead of covering the remaining form.
        val short = footer != null && maxWidth >= 480.dp && maxHeight - LocalRootBottom.current < 220.dp
        val actionWidth = maxWidth * .62f
        RootPane(header = {
            if (short && footer != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PageTitle(title, back, backEnabled) }
                Box(Modifier.widthIn(max = actionWidth).padding(end = 8.dp).horizontalScroll(rememberScrollState())) {
                    ActionDock(footer)
                }
            } else PageTitle(title, back, backEnabled)
        }, overlay = { _, bottom ->
            if (!short && footer != null) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottom)
                .onSizeChanged { footerPixels = it.height }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ActionDock(footer)
            }
        }) { top, bottom -> content(top, bottom + if (short || footer == null) 0.dp else with(density) { footerPixels.toDp() }) }
    }
}

@Composable
private fun ActionDock(actions: @Composable () -> Unit) {
    StackLifeSurface(CapsuleScene.PANEL, Modifier, MaterialTheme.colorScheme.surfaceContainerHigh,
        elevation = 2.dp, shape = RoundedCornerShape(24.dp)) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            CompositionLocalProvider(LocalActionGroup provides true, content = actions)
        }
    }
}

@Composable
internal fun EditorPage(title: String, back: () -> Unit, assistantPage: AssistantPage? = null,
    backEnabled: Boolean = true, footer: (@Composable () -> Unit)? = null,
    content: @Composable (top: Dp, bottom: Dp) -> Unit) {
    Page(assistantPage) { PageChrome(title, back, backEnabled, footer, content) }
}

@Composable
internal fun FormGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
internal fun PageTitle(title: String, back: () -> Unit, backEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIcon(R.drawable.ic_arrow_back, "返回", back, enabled = backEnabled)
        Text(title, Modifier.weight(1f).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
internal fun ToolbarIcon(icon: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    GlassIcon(icon, description, onClick, enabled = enabled)
}

@Composable
internal fun PreferenceRow(label: String, value: String? = null, modifier: Modifier = Modifier, enabled: Boolean = true,
    leadingIcon: Int? = null, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 52.dp)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (leadingIcon != null) Icon(painterResource(leadingIcon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
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

private val LocalActionGroup = compositionLocalOf { false }

@Composable
internal fun ControlGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    StackLifeSurface(CapsuleScene.CHROME, modifier, MaterialTheme.colorScheme.surfaceContainerHigh, elevation = 1.dp) {
        CompositionLocalProvider(LocalActionGroup provides true) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

@Composable
internal fun ActionPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, prominent: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val emphasized = prominent && enabled
    val fill = if (emphasized) colors.primaryContainer else colors.surfaceContainerHigh
    val ink = if (!enabled) colors.onSurface.copy(alpha = .38f) else if (emphasized) colors.onPrimaryContainer else colors.onSurface
    val interactions = remember { MutableInteractionSource() }
    val target = modifier.clickable(interactionSource = interactions, indication = null,
        enabled = enabled, role = Role.Button, onClick = onClick)
    val labelBox: @Composable (Modifier) -> Unit = {
        Box(it.clip(CircleShape).indication(interactions, LocalIndication.current)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(label, color = ink, style = MaterialTheme.typography.labelLarge)
        }
    }
    if (LocalActionGroup.current) labelBox(if (emphasized) target.background(fill, CircleShape) else target)
    else StackLifeSurface(CapsuleScene.ACTION, target, fill, elevation = 1.dp,
        tint = if (emphasized) fill else Color.Unspecified, interactionSource = interactions) { labelBox(Modifier) }
}

@Composable
internal fun Primary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    ActionPill(label, onClick, enabled = enabled, prominent = true)
}

@Composable
internal fun Secondary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    ActionPill(label, onClick, enabled = enabled)
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
