package io.github.nakanomk.stacklife.workspace

import androidx.compose.ui.res.stringResource

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
import io.github.nakanomk.stacklife.appearance.wallpaperBackground
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
import io.github.nakanomk.stacklife.LocalPageChromeInsets
import io.github.nakanomk.stacklife.pageControlSize
import io.github.nakanomk.stacklife.RootPane
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.i18n.AppLanguage
import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.planner.NavStyle
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.SchedulePattern
import io.github.nakanomk.stacklife.planner.ScheduleSummary
import io.github.nakanomk.stacklife.i18n.uiText
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
    Surface(modifier = Modifier.fillMaxSize().wallpaperBackground(if (docked) colors.surfaceContainer else colors.surface), color = Color.Transparent) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .imePadding(), contentAlignment = Alignment.TopCenter) {
            val paper = rememberPagePaper(colors.surface)
            // Surface already painted the same window-aligned photo or flat
            // paper. Only a photo-free docked page has a different inner color.
            val insetFill = if (docked && LocalWallpaperRecording.current == null) Modifier.background(colors.surface) else Modifier
            Box(Modifier.fillMaxSize().then(insetFill)) {
                CompositionLocalProvider(LocalCapsuleBackdrop provides paper, LocalPageChromeInsets provides true) {
                    if (assistantPage == null) Column(Modifier.fillMaxSize(), content = content)
                    else DockOverlay(assistantPage = assistantPage) { clearance ->
                        CompositionLocalProvider(LocalRootBottom provides clearance) { Column(Modifier.fillMaxSize(), content = content) }
                    }
                }
            }
        }
    }
}

/** Header and optional dock are drawn after the scrolling plane; neither can record itself. */
@Composable
internal fun PageChrome(title: String, back: () -> Unit, backEnabled: Boolean = true,
    footer: (@Composable () -> Unit)? = null, headerAction: (@Composable () -> Unit)? = null,
    independentFooter: Boolean = false,
    content: @Composable (top: Dp, bottom: Dp) -> Unit) {
    val density = LocalDensity.current
    var footerPixels by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A landscape IME can leave less than two rows of controls plus one editable field.
        // Put the same action dock next to Back instead of covering the remaining form.
        val viewport = pageControlSize()
        val short = footer != null && viewport.width >= 480.dp &&
            viewport.height - LocalRootBottom.current.calculateBottomPadding() < 220.dp
        val footerContent: @Composable () -> Unit = {
            if (footer != null) {
                if (independentFooter) footer() else ActionDock(footer)
            }
        }
        RootPane(header = {
            if (short && footer != null) BoxWithConstraints(Modifier.fillMaxWidth()) {
                val actionWidth = maxWidth * .62f
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { PageTitle(title, back, backEnabled, action = headerAction) }
                    val overflow = if (independentFooter) Modifier else Modifier.horizontalScroll(rememberScrollState())
                    Box(Modifier.widthIn(max = actionWidth).padding(end = 8.dp).then(overflow)) {
                        footerContent()
                    }
                }
            } else PageTitle(title, back, backEnabled, action = headerAction)
        }, overlay = { _, bottom ->
            if (!short && footer != null) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottom)
                .onSizeChanged { footerPixels = it.height }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                footerContent()
            }
        }) { top, bottom ->
            val clearance = bottom + if (short || footer == null) 0.dp else with(density) { footerPixels.toDp() }
            if (short && independentFooter) {
                // Wrapped actions occupy real header rows. A content padding alone still lets
                // bringIntoView place an input behind those controls in a short window.
                Box(Modifier.fillMaxSize().padding(top = top)) { content(0.dp, clearance) }
            } else content(top, clearance)
        }
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
    backEnabled: Boolean = true, footer: (@Composable () -> Unit)? = null, headerAction: (@Composable () -> Unit)? = null,
    independentFooter: Boolean = false,
    content: @Composable (top: Dp, bottom: Dp) -> Unit) {
    Page(assistantPage) {
        PageChrome(title, back, backEnabled, footer, headerAction, independentFooter, content)
    }
}

@Composable
internal fun FormGroup(modifier: Modifier = Modifier, frosted: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val fields: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
    val fill = MaterialTheme.colorScheme.surfaceContainerLow
    if (frosted) {
        StackLifeSurface(CapsuleScene.FROST, modifier.fillMaxWidth(), solid = fill, shape = RoundedCornerShape(16.dp), content = fields)
    } else {
        Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = fill, content = fields)
    }
}

@Composable
internal fun PageTitle(title: String, back: () -> Unit, backEnabled: Boolean = true, help: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassIcon(R.drawable.ic_arrow_back, stringResource(R.string.ui_back), back, enabled = backEnabled)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f, fill = false).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
                if (help != null) SettingInfo(help, title = title)
            }
        }
        if (action == null) Spacer(Modifier.width(48.dp)) else action()
    }
}

@Composable
internal fun ToolbarIcon(icon: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    GlassIcon(icon, description, onClick, enabled = enabled)
}

// Preference title/action and constrained value slots adapted from BiliPai AppPreferenceComponents
// (5893c2c80739e3759ac64ae5f232cf03a94da7f8, GPL-3.0). StackLife retains its row geometry.
@Composable
internal fun PreferenceRow(label: String, value: String? = null, modifier: Modifier = Modifier, enabled: Boolean = true,
    leadingIcon: Int? = null, help: String? = null, valueMaxLines: Int = 1, labelMaxLines: Int = 2, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 52.dp)
        .padding(vertical = if (help == null) 8.dp else 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (leadingIcon != null) Icon(painterResource(leadingIcon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        if (value == null) SettingLabel(label, help, Modifier.weight(1f), labelMaxLines)
        else BoxWithConstraints(Modifier.weight(1f)) {
            val valueLimit = minOf(200.dp, ((maxWidth - 8.dp) / 2).coerceAtLeast(0.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingLabel(label, help, Modifier.weight(1f), labelMaxLines)
                Text(value, Modifier.widthIn(max = valueLimit), maxLines = valueMaxLines, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
internal fun ControlGroup(modifier: Modifier = Modifier, frosted: Boolean = false, content: @Composable RowScope.() -> Unit) {
    StackLifeSurface(if (frosted) CapsuleScene.FROST else CapsuleScene.CHROME, modifier, MaterialTheme.colorScheme.surfaceContainerHigh,
        elevation = if (frosted) 0.dp else 1.dp) {
        CompositionLocalProvider(LocalActionGroup provides true) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

@Composable
internal fun ActionPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, prominent: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
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
            Text(label, color = ink, style = MaterialTheme.typography.labelLarge, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    }
    if (LocalActionGroup.current) labelBox(if (emphasized) target.background(fill, CircleShape) else target)
    else StackLifeSurface(CapsuleScene.ACTION, target, fill, elevation = 1.dp,
        tint = if (emphasized) fill else Color.Unspecified, interactionSource = interactions.takeIf { enabled }) { labelBox(Modifier) }
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
internal fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 48.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun List<String>.toggle(id: String): List<String> = if (id in this) this - id else this + id

private val fullZh = DateTimeFormatter.ofPattern("uuuu年M月d日 HH:mm", Locale.CHINA)
private val shortZh = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
private val fullEn = DateTimeFormatter.ofPattern("MMM d, uuuu HH:mm", Locale.ENGLISH)
private val shortEn = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH)
private val shortClock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
internal fun savedTime(millis: Long): String {
    val formatter = if (AppLanguage.locale.language == "zh") fullZh else fullEn
    return formatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}
internal fun scheduledRange(start: Long, end: Long): String {
    val zone = ZoneId.systemDefault()
    val from = Instant.ofEpochMilli(start).atZone(zone)
    val to = Instant.ofEpochMilli(end).atZone(zone)
    val shortDateTime = if (AppLanguage.locale.language == "zh") shortZh else shortEn
    if (start == end) return from.format(shortDateTime)
    return "${from.format(shortDateTime)}–${to.format(if (from.toLocalDate() == to.toLocalDate()) shortClock else shortDateTime)}"
}

internal fun scheduleText(s: ScheduleSummary): String = when (val pattern = s.pattern) {
    is SchedulePattern.Once -> scheduledRange(pattern.interval.startMillis,pattern.interval.endMillis)
    is SchedulePattern.Daily -> {
        pattern.segments.mapIndexed { index, segment ->
            val start = java.time.LocalDate.ofEpochDay(segment.fromDay)
            val endDay = listOfNotNull(segment.endDay,pattern.segments.getOrNull(index+1)?.fromDay?.minus(1)).minOrNull()
            val range = if (endDay == null) uiText("from $start", "自 $start 起") else "$start – ${java.time.LocalDate.ofEpochDay(endDay)}"
            val time = java.time.LocalTime.ofSecondOfDay(segment.minute * 60L).format(shortClock)
            val zone = segment.zone ?: uiText("device time zone", "设备时区")
            val duration = if (segment.minutes == 0) uiText("point","单点") else uiText("${segment.minutes} min", "${segment.minutes} 分钟")
            uiText("Daily $time ($zone), $duration, $range", "每日 $time（$zone），$duration，$range")
        }.joinToString("\n")
    }
}.let { if (s.enabled) it else uiText("Disabled: $it", "已停用：$it") }

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
