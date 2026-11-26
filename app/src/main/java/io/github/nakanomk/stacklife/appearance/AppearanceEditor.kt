package io.github.nakanomk.stacklife.appearance

import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.chromeGutter
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.rememberDestinationCapsule
import io.github.nakanomk.stacklife.courses.CourseTimetablePreview
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.*
import io.github.nakanomk.stacklife.today.DayWheelPreview
import kotlinx.coroutines.CancellationException
import sh.calvin.reorderable.ReorderableColumn

private val appearanceSaver = Saver<Appearance, String>(save = { encodeAppearance(it) }, restore = ::decodeAppearance)
private val validSeed = Regex("#[0-9A-F]{6}")
private class PhotoSeedRequest(val imageId: String)
private enum class EditorLeadSection { MESSAGE, PREVIEW, MATERIAL, WALLPAPER }
private val AppearanceGroup.title: String @Composable get() = when (this) {
    AppearanceGroup.COLOR -> stringResource(R.string.ui_colors); AppearanceGroup.LAYOUT -> stringResource(R.string.ui_layout); AppearanceGroup.MATERIAL -> stringResource(R.string.ui_material_motion)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceEditor(saved: Appearance, group: AppearanceGroup, actions: WorkspaceActions,
    planner: PlannerApi, startAtWallpaper: Boolean = false, onBack: () -> Unit) {
    var baseline by rememberSaveable(stateSaver = appearanceSaver) { mutableStateOf(saved) }
    var draft by rememberSaveable(stateSaver = appearanceSaver) { mutableStateOf(saved) }
    var seedInput by rememberSaveable { mutableStateOf(saved.seed) }
    var message by rememberSaveable { mutableStateOf("") }
    var leases by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var wallpaperTarget by rememberSaveable { mutableStateOf<Destination?>(null) }
    var closing by rememberSaveable { mutableStateOf(false) }
    var applied by rememberSaveable { mutableStateOf(false) }
    val backgrounds = LocalBackgroundStore.current
    // COLOR owns the seed, not the wallpaper: resolve the current saved private file.
    val seedForm = group == AppearanceGroup.COLOR && draft.palette == PaletteMode.SEED
    val imageId = if (seedForm) saved.wallpaper(wallpaperTarget).imageId else null
    var sample by remember(imageId, wallpaperTarget) { mutableStateOf<PhotoSeedRequest?>(null) }
    var sampleError by remember(imageId, wallpaperTarget) { mutableStateOf<Int?>(null) }
    fun discardSample() { sample = null; sampleError = null }
    val latestBack by rememberUpdatedState(onBack)
    val cleanupFailure = stringResource(R.string.appearance_image_cleanup_failed)
    val receipt = rememberSubmission(actions, onError = { message = it }) {
        if (it == SaveOutcome.APPLIED) { applied = true; closing = true }
    }
    LaunchedEffect(closing) {
        if (closing) {
            try {
                for (id in leases) backgrounds.release(id)
                leases = arrayListOf()
                latestBack()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = cleanupFailure; closing = false }
        }
    }
    val picker = if (group == AppearanceGroup.MATERIAL && !receipt.busy && !closing && !applied) rememberWallpaperPicker { page, id ->
        leases = ArrayList(leases).apply { add(id) }
        draft = draft.replacingWallpaper(page, WallpaperSpec(imageId = id))
    } else null
    val busy = receipt.busy || closing || picker?.preparing == true
    val leave: () -> Unit = { if (!receipt.busy) { discardSample(); closing = true } }
    val seedOK = group != AppearanceGroup.COLOR || draft.palette != PaletteMode.SEED || validSeed.matches(seedInput)
    val selected = remember(group, draft) { group.project(draft) }
    val persisted = remember(group, saved) { group.project(saved) }
    val stale = remember(group, baseline, persisted) { group.project(baseline) != persisted }
    val request = sample
    if (request != null && !stale && !busy && !applied) LaunchedEffect(request) {
        try {
            val argb = backgrounds.photoSeed(request.imageId)
            if (sample === request) {
                if (argb == null) sampleError = R.string.wallpaper_seed_no_color
                else {
                    val seed = "#%06X".format(java.util.Locale.ROOT, argb and 0xffffff)
                    seedInput = seed
                    draft = draft.copy(seed = seed)
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (sample === request) sampleError = R.string.wallpaper_seed_unavailable }
        finally { if (sample === request) sample = null }
    }
    val preview = remember(group, selected, saved) { AppearanceFields(group, selected).applyTo(saved) }
    var previewPage by rememberSaveable(preview.start, wallpaperTarget) { mutableStateOf(wallpaperTarget ?: preview.start) }
    val reload = { discardSample(); baseline = saved; draft = saved; seedInput = saved.seed; message = "" }
    val transfer = if (group == AppearanceGroup.MATERIAL) rememberAppearanceTransfer(planner, backgrounds,
        available = !busy && !applied && !stale && selected == persisted) { applied = true; closing = true } else null
    BackHandler(enabled = busy || leases.isNotEmpty() || request != null) { leave() }
    val hasMessage = message.isNotEmpty()
    val leadSections = remember(group, hasMessage) {
        buildList {
            if (hasMessage) add(EditorLeadSection.MESSAGE)
            add(EditorLeadSection.PREVIEW)
            if (group == AppearanceGroup.MATERIAL) {
                add(EditorLeadSection.MATERIAL)
                add(EditorLeadSection.WALLPAPER)
            }
        }
    }
    // The entry point only chooses the initial viewport. LazyListState restores subsequent user scrolls.
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex =
        if (startAtWallpaper && group == AppearanceGroup.MATERIAL) leadSections.indexOf(EditorLeadSection.WALLPAPER) else 0)
    StackLifeTheme(preview) {
        Page {
            CenterAlignedTopAppBar(title = { Text(group.title, style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier.testTag("appearance-header"),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                navigationIcon = { TextButton(onClick = leave, enabled = !receipt.busy && !closing) { Text(stringResource(R.string.ui_cancel)) } },
                actions = {
                    TextButton(enabled = !busy && request == null && !applied && seedOK && !stale && selected != persisted,
                        onClick = { receipt.send(PlannerCommand.EditAppearance(AppearanceFields(group, selected), expected = baseline)) }) {
                        Text(stringResource(R.string.ui_save_appearance))
                    }
                }, windowInsets = WindowInsets(0))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("appearance-scroll"), state = scroll, contentPadding = dockListPadding(0.dp, 0.dp),
                verticalArrangement = listSpacing) {
                items(leadSections, key = { it }) { section ->
                    when (section) {
                        EditorLeadSection.MESSAGE -> Text(message, color = MaterialTheme.colorScheme.error)
                        EditorLeadSection.PREVIEW -> {
                            val page = previewPage.takeIf { it in preview.visible || it == wallpaperTarget } ?: preview.start
                            AppearancePreview(preview, page, preview.wallpaper(if (seedForm) wallpaperTarget else page)) {
                                if (seedForm) { discardSample(); wallpaperTarget = it }
                                previewPage = it
                            }
                        }
                        EditorLeadSection.MATERIAL -> MaterialFields(draft) { draft = it }
                        EditorLeadSection.WALLPAPER -> if (picker != null) {
                            WallpaperControls(draft, { draft = it }, wallpaperTarget, { wallpaperTarget = it }, picker)
                        }
                    }
                }
                when (group) {
                    AppearanceGroup.COLOR -> item {
                        ColorFields(draft, seedInput, { discardSample(); seedInput = it }, { discardSample(); draft = it }) {
                            Choice(stringResource(R.string.wallpaper_seed_source), wallpaperTargets, wallpaperTarget,
                                { it?.label ?: stringResource(R.string.wallpaper_global) }) { discardSample(); wallpaperTarget = it }
                            Secondary(stringResource(R.string.wallpaper_seed_pick),
                                enabled = !busy && !applied && !stale && imageId != null && request == null) {
                                if (imageId != null) { sampleError = null; sample = PhotoSeedRequest(imageId) }
                            }
                            if (request != null) Hint(stringResource(R.string.wallpaper_seed_reading))
                            else if (imageId == null) Hint(stringResource(R.string.wallpaper_seed_no_photo))
                            sampleError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    AppearanceGroup.LAYOUT -> {
                        item { LayoutFields(draft) { draft = it } }
                        item { DayWheelPreview() }
                        item {
                            FormGroup(frosted = true) {
                                Choice(stringResource(R.string.now_marker_line), NowLineStyle.entries, draft.nowLine, {
                                    stringResource(when (it) {
                                        NowLineStyle.WAVE -> R.string.now_marker_wave
                                        NowLineStyle.STRAIGHT -> R.string.now_marker_straight
                                        NowLineStyle.OFF -> R.string.now_marker_off
                                    })
                                }) { draft = draft.copy(nowLine = it) }
                                SwitchRow(stringResource(R.string.now_marker_arrow), draft.nowArrow) { draft = draft.copy(nowArrow = !draft.nowArrow) }
                                SwitchRow(stringResource(R.string.now_marker_trail), draft.nowTrail) { draft = draft.copy(nowTrail = !draft.nowTrail) }
                            }
                        }
                        item { SectionTitle(stringResource(R.string.ui_nav_order)) }
                        // Only these five rows reorder. Measure their intervals in this section,
                        // not in the lazy viewport that also contains previews and preferences.
                        item {
                            ReorderableColumn(draft.order, onSettle = { from, to -> draft = draft.moveTab(from, to) },
                                verticalArrangement = listSpacing) { index, dest, dragging ->
                                key(dest) {
                                    ReorderableItem {
                                        NavigationField(dest, index, draft, enabled = !busy, dragging = dragging, update = { draft = it })
                                    }
                                }
                            }
                        }
                    }
                    AppearanceGroup.MATERIAL -> {
                        item {
                            FormGroup(frosted = true) {
                                SectionTitle(stringResource(R.string.ui_course_preview))
                                CompositionLocalProvider(LocalPlannerGlassAllowed provides true) {
                                    WallpaperHost(preview.wallpaper(Destination.COURSES)) { CourseTimetablePreview() }
                                }
                                SwitchRow(stringResource(R.string.appearance_grid_lines), draft.showCourseGrid) { draft = draft.copy(showCourseGrid = !draft.showCourseGrid) }
                                SwitchRow(stringResource(R.string.ui_course_outline), draft.courseTextOutline) { draft = draft.copy(courseTextOutline = !draft.courseTextOutline) }
                            }
                        }
                        item { AccessibilityFields(draft) { draft = it } }
                        if (transfer != null) item(key = "appearance-transfer") { transfer() }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { discardSample(); draft = AppearanceFields(group, null).applyTo(draft); seedInput = draft.seed; message = "" },
                            enabled = !busy && !applied, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.ui_restore_defaults))
                        }
                    }
                }
                if (stale && !applied) item { Secondary(stringResource(R.string.ui_reload_settings), enabled = !busy, onClick = reload) }
            }
        }
    }
}

@Composable
private fun AppearancePreview(value: Appearance, selected: Destination, wallpaper: WallpaperSpec, choose: (Destination) -> Unit) {
    val nav = if (selected in value.visible) rememberDestinationCapsule(selected.key) { key ->
        choose(value.visibleOrder.first { it.key == key })
    } else null
    Surface(Modifier.fillMaxWidth().testTag("appearance-page-preview"), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        CompositionLocalProvider(LocalPlannerGlassAllowed provides true) {
            WallpaperHost(wallpaper) {
                // Unlike the full-screen overlay, this preview wraps its content. Measure the
                // actual note and dock in sequence, including wrapped text and docked labels.
                GlassBacking(Modifier.fillMaxWidth(), background = {
                    Spacer(Modifier.matchParentSize().wallpaperBackground(MaterialTheme.colorScheme.surfaceContainerLow))
                }) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (value.compact) 4.dp else 8.dp)) {
                            Text(stringResource(R.string.ui_preview_of, selected.label), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
                            Text(stringResource(R.string.ui_sample_task), style = MaterialTheme.typography.bodyLarge)
                            if (value.showDuration) Hint(stringResource(R.string.ui_sample_duration))
                            if (value.showNotes) Hint(stringResource(R.string.ui_sample_note))
                        }
                        if (nav != null) {
                            if (value.navStyle == NavStyle.DOCKED) nav()
                            else Box(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = chromeGutter, vertical = 4.dp)) { nav() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorFields(d: Appearance, seed: String, editSeed: (String) -> Unit, update: (Appearance) -> Unit,
    photoSource: @Composable () -> Unit) {
    FormGroup(frosted = true) {
        Choice(stringResource(R.string.ui_color_theme), ThemeMode.entries, d.theme, { when (it) { ThemeMode.SYSTEM -> stringResource(R.string.ui_follow_system); ThemeMode.LIGHT -> stringResource(R.string.ui_light); ThemeMode.DARK -> stringResource(R.string.ui_dark) } }) { update(d.copy(theme = it)) }
        Choice(stringResource(R.string.ui_palette), PaletteMode.entries, d.palette, { if (it == PaletteMode.DYNAMIC) stringResource(R.string.ui_dynamic_color) else stringResource(R.string.ui_custom_seed) },
            help = stringResource(R.string.ui_dynamic_hint)) { update(d.copy(palette = it)) }
        if (d.palette == PaletteMode.SEED) TextField(colors = lineFieldColors(), value = seed, onValueChange = { text ->
            val normalized = text.uppercase(); editSeed(normalized)
            if (validSeed.matches(normalized)) update(d.copy(seed = normalized))
        }, label = { Text(stringResource(R.string.ui_seed_hex)) }, singleLine = true, isError = !validSeed.matches(seed), modifier = Modifier.fillMaxWidth().testTag("seed-color"))
        if (d.palette == PaletteMode.SEED) photoSource()
    }
}

@Composable
private fun LayoutFields(d: Appearance, update: (Appearance) -> Unit) {
    FormGroup(frosted = true) {
        Choice(stringResource(R.string.ui_spacing), listOf(false, true), d.compact, { if (it) stringResource(R.string.ui_compact) else stringResource(R.string.ui_standard) }) { update(d.copy(compact = it)) }
        Choice(stringResource(R.string.ui_nav_style), NavStyle.entries, d.navStyle, { if (it == NavStyle.FLOATING) stringResource(R.string.ui_floating_nav) else stringResource(R.string.ui_bottom_nav) }) { update(d.copy(navStyle = it)) }
        Choice(stringResource(R.string.ui_backlog), listOf(true, false), d.floatingDayTray, { if (it) stringResource(R.string.ui_floating_button) else stringResource(R.string.ui_persistent_tray) }) { update(d.copy(floatingDayTray = it)) }
        Choice(stringResource(R.string.ui_start_page), d.visibleOrder, d.start, { it.label }) { update(d.copy(start = it)) }
        Choice(stringResource(R.string.appearance_day_scroll), DayScrollEffect.entries, d.dayScrollEffect,
            { stringResource(if (it == DayScrollEffect.FLAT) R.string.appearance_scroll_flat else R.string.appearance_scroll_wheel) }) {
            update(d.copy(dayScrollEffect = it))
        }
        SwitchRow(stringResource(R.string.ui_show_duration), d.showDuration) { update(d.copy(showDuration = !d.showDuration)) }
        SwitchRow(stringResource(R.string.ui_show_notes), d.showNotes) { update(d.copy(showNotes = !d.showNotes)) }
    }
}


@Composable
private fun MaterialFields(d: Appearance, update: (Appearance) -> Unit) {
    val reason = glassUnavailableReason(d)
    FormGroup(frosted = true) {
        SectionTitle(stringResource(R.string.ui_materials))
        SwitchRow(stringResource(R.string.ui_liquid_glass), d.glass, help = stringResource(R.string.ui_glass_surfaces)) { update(d.copy(glass = !d.glass)) }
        SwitchRow(stringResource(R.string.ui_glass_navigation), d.navGlass, enabled = d.glass) { update(d.copy(navGlass = !d.navGlass)) }
        SwitchRow(stringResource(R.string.ui_glass_assistant), d.assistantGlass, enabled = d.glass) { update(d.copy(assistantGlass = !d.assistantGlass)) }
        if (reason != null) Hint(stringResource(R.string.ui_solid_because, reason))
        else if (!d.glass) Hint(stringResource(R.string.ui_solid_disabled))
    }
}

@Composable
private fun AccessibilityFields(d: Appearance, update: (Appearance) -> Unit) {
    FormGroup(frosted = true) {
        SectionTitle(stringResource(R.string.ui_motion_accessibility))
        SwitchRow(stringResource(R.string.ui_reduce_motion), d.reduceMotion) { update(d.copy(reduceMotion = !d.reduceMotion)) }
        SwitchRow(stringResource(R.string.ui_reduce_transparency), d.reduceTransparency) { update(d.copy(reduceTransparency = !d.reduceTransparency)) }
        SwitchRow(stringResource(R.string.ui_increase_contrast), d.highContrast) { update(d.copy(highContrast = !d.highContrast)) }
        SwitchRow(stringResource(R.string.ui_system_haptics), d.haptics) { update(d.copy(haptics = !d.haptics)) }
        if (d.haptics) {
            val label: @Composable (HapticLevel) -> String = { level ->
                stringResource(when (level) {
                    HapticLevel.OFF -> R.string.haptic_off
                    HapticLevel.LIGHT -> R.string.haptic_light
                    HapticLevel.STANDARD -> R.string.ui_standard
                })
            }
            Choice(stringResource(R.string.haptic_navigation), HapticLevel.entries, d.navigationHaptics, label) {
                update(d.copy(navigationHaptics = it))
            }
            Choice(stringResource(R.string.haptic_edges), HapticLevel.entries, d.edgeHaptics, label) {
                update(d.copy(edgeHaptics = it))
            }
        }
    }
}


@Composable
internal fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, help: String? = null, change: () -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch) { change() }
        .heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) {
            SettingLabel(label, help, Modifier.weight(1f))
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
