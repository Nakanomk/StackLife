package io.github.nakanomk.stacklife.appearance

import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.DockOverlay
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.rememberDestinationCapsule
import io.github.nakanomk.stacklife.courses.CourseTimetablePreview
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.*

private val appearanceSaver = Saver<Appearance, String>(save = { encodeAppearance(it) }, restore = ::decodeAppearance)
private val validSeed = Regex("#[0-9A-F]{6}")
private val AppearanceGroup.title: String @Composable get() = when (this) {
    AppearanceGroup.COLOR -> stringResource(R.string.ui_colors); AppearanceGroup.LAYOUT -> stringResource(R.string.ui_layout); AppearanceGroup.MATERIAL -> stringResource(R.string.ui_material_motion)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceEditor(saved: Appearance, group: AppearanceGroup, actions: WorkspaceActions, onBack: () -> Unit) {
    var baseline by rememberSaveable(stateSaver = appearanceSaver) { mutableStateOf(saved) }
    var draft by rememberSaveable(stateSaver = appearanceSaver) { mutableStateOf(saved) }
    var seedInput by rememberSaveable { mutableStateOf(saved.seed) }
    var message by rememberSaveable { mutableStateOf("") }
    val receipt = rememberSubmission(actions, onError = { message = it }) { if (it == SaveOutcome.APPLIED) onBack() }
    val seedOK = group != AppearanceGroup.COLOR || draft.palette != PaletteMode.SEED || validSeed.matches(seedInput)
    val selected = remember(group, draft) { group.project(draft) }
    val persisted = remember(group, saved) { group.project(saved) }
    val stale = remember(group, baseline, persisted) { group.project(baseline) != persisted }
    val preview = remember(group, selected, saved) { AppearanceFields(group, selected).applyTo(saved) }
    val reload = { baseline = saved; draft = saved; seedInput = saved.seed; message = "" }
    BackHandler(enabled = receipt.busy) { }
    StackLifeTheme(preview) {
        Page {
            CenterAlignedTopAppBar(title = { Text(group.title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { TextButton(onClick = onBack, enabled = !receipt.busy) { Text(stringResource(R.string.ui_cancel)) } },
                actions = {
                    TextButton(enabled = !receipt.busy && seedOK && !stale && selected != persisted,
                        onClick = { receipt.send(PlannerCommand.EditAppearance(AppearanceFields(group, selected), expected = baseline)) }) {
                        Text(stringResource(R.string.ui_save_appearance))
                    }
                }, windowInsets = WindowInsets(0))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("appearance-scroll"), contentPadding = dockListPadding(0.dp, 0.dp),
                verticalArrangement = listSpacing) {
                if (message.isNotEmpty()) item { Text(message, color = MaterialTheme.colorScheme.error) }
                item { AppearancePreview(preview) }
                when (group) {
                    AppearanceGroup.COLOR -> item { ColorFields(draft, seedInput, { seedInput = it }, { draft = it }) }
                    AppearanceGroup.LAYOUT -> item { LayoutFields(draft) { draft = it } }
                    AppearanceGroup.MATERIAL -> {
                        item { MaterialFields(draft) { draft = it } }
                        item {
                            Column {
                                SectionTitle(stringResource(R.string.ui_course_preview))
                                CompositionLocalProvider(LocalPlannerGlassAllowed provides true) { CourseTimetablePreview() }
                                SwitchRow(stringResource(R.string.ui_course_outline), draft.courseTextOutline) { draft = draft.copy(courseTextOutline = !draft.courseTextOutline) }
                            }
                        }
                        item { AccessibilityFields(draft) { draft = it } }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { draft = AppearanceFields(group, null).applyTo(draft); seedInput = draft.seed; message = "" },
                            enabled = !receipt.busy, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.ui_restore_defaults))
                        }
                    }
                }
                if (stale) item { Secondary(stringResource(R.string.ui_reload_settings), enabled = !receipt.busy, onClick = reload) }
            }
        }
    }
}

@Composable
private fun AppearancePreview(value: Appearance) {
    var selected by rememberSaveable(value.start) { mutableStateOf(value.start.key) }
    val nav = rememberDestinationCapsule(selected) { selected = it }
    val scale = LocalDensity.current.fontScale
    Surface(Modifier.fillMaxWidth().height((144 * scale.coerceAtLeast(1f)).dp), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        CompositionLocalProvider(LocalPlannerGlassAllowed provides true) {
            DockOverlay(nav) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (value.compact) 4.dp else 8.dp)) {
                    Text(stringResource(R.string.ui_preview_of, Destination.entries.first { it.key == selected }.label), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.ui_sample_task), style = MaterialTheme.typography.bodyLarge)
                    if (value.showDuration || value.showNotes) Hint(listOfNotNull(stringResource(R.string.ui_sample_duration).takeIf { value.showDuration },
                        stringResource(R.string.ui_sample_note).takeIf { value.showNotes }).joinToString(" · "))
                }
            }
        }
    }
}

@Composable
private fun ColorFields(d: Appearance, seed: String, editSeed: (String) -> Unit, update: (Appearance) -> Unit) {
    Column {
        Choice(stringResource(R.string.ui_color_theme), ThemeMode.entries, d.theme, { when (it) { ThemeMode.SYSTEM -> stringResource(R.string.ui_follow_system); ThemeMode.LIGHT -> stringResource(R.string.ui_light); ThemeMode.DARK -> stringResource(R.string.ui_dark) } }) { update(d.copy(theme = it)) }
        Choice(stringResource(R.string.ui_palette), PaletteMode.entries, d.palette, { if (it == PaletteMode.DYNAMIC) stringResource(R.string.ui_dynamic_color) else stringResource(R.string.ui_custom_seed) }) { update(d.copy(palette = it)) }
        if (d.palette == PaletteMode.SEED) TextField(colors = lineFieldColors(), value = seed, onValueChange = { text ->
            val normalized = text.uppercase(); editSeed(normalized)
            if (validSeed.matches(normalized)) update(d.copy(seed = normalized))
        }, label = { Text(stringResource(R.string.ui_seed_hex)) }, singleLine = true, isError = !validSeed.matches(seed), modifier = Modifier.fillMaxWidth().testTag("seed-color"))
        else Hint(stringResource(R.string.ui_dynamic_hint))
    }
}

@Composable
private fun LayoutFields(d: Appearance, update: (Appearance) -> Unit) {
    Column {
        Choice(stringResource(R.string.ui_spacing), listOf(false, true), d.compact, { if (it) stringResource(R.string.ui_compact) else stringResource(R.string.ui_standard) }) { update(d.copy(compact = it)) }
        Choice(stringResource(R.string.ui_nav_style), NavStyle.entries, d.navStyle, { if (it == NavStyle.FLOATING) stringResource(R.string.ui_floating_nav) else stringResource(R.string.ui_bottom_nav) }) { update(d.copy(navStyle = it)) }
        Choice(stringResource(R.string.ui_backlog), listOf(true, false), d.floatingDayTray, { if (it) stringResource(R.string.ui_floating_button) else stringResource(R.string.ui_persistent_tray) }) { update(d.copy(floatingDayTray = it)) }
        Choice(stringResource(R.string.ui_start_page), d.order, d.start, { it.label }) { update(d.copy(start = it)) }
        SectionTitle(stringResource(R.string.ui_nav_order))
        for ((i, dest) in d.order.withIndex()) {
            fun move(to: Int) { val next = d.order.toMutableList(); next[i] = next[to]; next[to] = dest; update(d.copy(order = next)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${dest.label}", Modifier.weight(1f))
                MoveButton(dest, up = true, enabled = i > 0) { move(i - 1) }
                MoveButton(dest, up = false, enabled = i < d.order.lastIndex) { move(i + 1) }
            }
        }
        SwitchRow(stringResource(R.string.ui_show_duration), d.showDuration) { update(d.copy(showDuration = !d.showDuration)) }
        SwitchRow(stringResource(R.string.ui_show_notes), d.showNotes) { update(d.copy(showNotes = !d.showNotes)) }
    }
}

@Composable
private fun MoveButton(dest: Destination, up: Boolean, enabled: Boolean, move: () -> Unit) {
    val label = stringResource(if (up) R.string.ui_move_tab_up else R.string.ui_move_tab_down, dest.label)
    ToolbarIcon(if (up) R.drawable.ic_expand_less else R.drawable.ic_expand_more, label, enabled, move)
}

@Composable
private fun MaterialFields(d: Appearance, update: (Appearance) -> Unit) {
    val reason = glassUnavailableReason(d)
    Column {
        SectionTitle(stringResource(R.string.ui_materials))
        SwitchRow(stringResource(R.string.ui_liquid_glass), d.glass) { update(d.copy(glass = !d.glass)) }
        SwitchRow(stringResource(R.string.ui_glass_navigation), d.navGlass, enabled = d.glass) { update(d.copy(navGlass = !d.navGlass)) }
        SwitchRow(stringResource(R.string.ui_glass_assistant), d.assistantGlass, enabled = d.glass) { update(d.copy(assistantGlass = !d.assistantGlass)) }
        Hint(when { reason != null -> stringResource(R.string.ui_solid_because, reason); !d.glass -> stringResource(R.string.ui_solid_disabled);
            else -> stringResource(R.string.ui_glass_surfaces) })
    }
}

@Composable
private fun AccessibilityFields(d: Appearance, update: (Appearance) -> Unit) {
    Column {
        SectionTitle(stringResource(R.string.ui_motion_accessibility))
        SwitchRow(stringResource(R.string.ui_reduce_motion), d.reduceMotion) { update(d.copy(reduceMotion = !d.reduceMotion)) }
        SwitchRow(stringResource(R.string.ui_reduce_transparency), d.reduceTransparency) { update(d.copy(reduceTransparency = !d.reduceTransparency)) }
        SwitchRow(stringResource(R.string.ui_increase_contrast), d.highContrast) { update(d.copy(highContrast = !d.highContrast)) }
        SwitchRow(stringResource(R.string.ui_system_haptics), d.haptics) { update(d.copy(haptics = !d.haptics)) }
    }
}


@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, change: () -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch) { change() }
        .heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (enabled)
            MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
