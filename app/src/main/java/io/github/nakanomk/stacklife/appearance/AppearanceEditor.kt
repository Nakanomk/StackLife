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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.chromeGutter
import io.github.nakanomk.stacklife.RootPane
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.rememberDestinationCapsule
import io.github.nakanomk.stacklife.courses.CourseTimetablePreview
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.*
import io.github.nakanomk.stacklife.today.DayWheelPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import io.github.nakanomk.stacklife.transitions.LocalFrontEntry
import io.github.nakanomk.stacklife.transitions.LocalRouteOrigins
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import sh.calvin.reorderable.ReorderableColumn

private val appearanceSaver = Saver<Appearance, String>(save = { encodeAppearance(it) }, restore = ::decodeAppearance)
private val validSeed = Regex("#[0-9A-F]{6}")
private enum class EditorLeadSection { PREVIEW, MATERIAL, WALLPAPER }
private enum class Departure { KEEP, DISCARD, DISPATCHED }
// Activity recreation continues an editing session. After process death the durable Room
// value starts a fresh session; neither a vanished photo pin nor a receipt is replayable.
private val editorProcess = java.util.UUID.randomUUID().toString()

// Undo only this editor's changes. A new value from another owner wins, including
// changes to a different page or crop inside the same material group.
private fun undoFields(entry: JsonElement?, written: JsonElement?, current: JsonElement?): JsonElement? = when {
    current == written -> entry
    entry == written -> current
    entry is JsonObject && written is JsonObject && current is JsonObject -> if ("imageId" in written &&
        (current["imageId"] != written["imageId"] || current["preset"] != written["preset"])) current else
        JsonObject(buildMap {
            for (key in entry.keys + written.keys + current.keys)
                undoFields(entry[key], written[key], current[key])?.let { put(key, it) }
        })
    else -> current
}

private fun AppearanceGroup.undo(entry: Appearance, written: Appearance, current: Appearance): Appearance {
    val fields = undoFields(project(entry), project(written), project(current)) as JsonObject
    // If a concurrent edit hid the old start page, keep its valid navigation pair
    // while still undoing independent fields such as spacing or duration.
    val restored = if (this == AppearanceGroup.LAYOUT &&
        fields.getValue("start") !in (fields.getValue("visible") as kotlinx.serialization.json.JsonArray)) {
        val actual = project(current)
        JsonObject(fields + mapOf("start" to actual.getValue("start"), "visible" to actual.getValue("visible")))
    } else fields
    return AppearanceFields(this, restored).applyTo(current)
}
private val AppearanceGroup.title: String @Composable get() = when (this) {
    AppearanceGroup.COLOR -> stringResource(R.string.ui_colors); AppearanceGroup.LAYOUT -> stringResource(R.string.ui_layout); AppearanceGroup.MATERIAL -> stringResource(R.string.ui_material_motion)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceEditor(saved: Appearance, group: AppearanceGroup, actions: WorkspaceActions,
    planner: PlannerApi, startAtWallpaper: Boolean = false, onBack: () -> Unit) = key(editorProcess, group) {
    var baseline by rememberSaveable(stateSaver = appearanceSaver) { mutableStateOf(saved) }
    var confirmed by rememberSaveable(stateSaver = appearanceSaver) { mutableStateOf(saved) }
    var submitted by rememberSaveable(stateSaver = appearanceSaver) { mutableStateOf(saved) }
    var ticket by rememberSaveable { mutableStateOf<String?>(null) }
    var staged by rememberSaveable(stateSaver = appearanceSaver) { mutableStateOf(saved) }
    var seedInput by rememberSaveable { mutableStateOf(saved.seed) }
    var message by rememberSaveable { mutableStateOf("") }
    var leases by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var wallpaperTarget by rememberSaveable { mutableStateOf<Destination?>(if (group == AppearanceGroup.COLOR) saved.palettePage else null) }
    var departure by rememberSaveable { mutableStateOf<Departure?>(null) }
    var undoSubmitted by rememberSaveable { mutableStateOf(false) }
    val owner = rememberSaveable { java.util.UUID.randomUUID().toString() }
    // Non-null from the first pin attempt: prompt cancellation returning from IO may
    // have pinned the file before the caller received its result. Back must still release it.
    var pins by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val draft = if (message.isEmpty()) staged else saved
    val backgrounds = LocalBackgroundStore.current
    val seedForm = group == AppearanceGroup.COLOR && draft.palette != PaletteMode.DYNAMIC
    val target = if (draft.palette == PaletteMode.WALLPAPER && seedForm) draft.palettePage else wallpaperTarget
    val seedSource = saved.wallpaper(target)
    var sample by remember(seedForm, seedSource.imageId, seedSource.preset, target) { mutableStateOf<Any?>(null) }
    var sampleError by remember(seedForm, seedSource.imageId, seedSource.preset, target) { mutableStateOf<Int?>(null) }
    fun discardSample() { sample = null; sampleError = null }
    val latestBack by rememberUpdatedState(onBack)
    val latestSaved by rememberUpdatedState(saved)
    val front by rememberUpdatedState(LocalFrontEntry.current)
    val cleanupFailure = stringResource(R.string.appearance_image_cleanup_failed)
    val writeFailure = stringResource(R.string.appearance_write_failed)
    fun fail(reason: String) {
        message = reason
        staged = confirmed
        seedInput = latestSaved.seed
        departure = null
        undoSubmitted = false
    }
    // The guarded transaction can reject before Room's external value reaches the
    // page. If that happens the editable hex text must catch up with the form too.
    LaunchedEffect(message, saved.seed) { if (message.isNotEmpty()) seedInput = saved.seed }
    fun change(next: Appearance) {
        if (departure != null) return
        if (message.isNotEmpty()) {
            // After a rejection the form shows durable values. Carry only still-owned
            // undo fields across a fresh, explicit user edit.
            baseline = group.undo(baseline, confirmed, saved)
            confirmed = saved
            message = ""
        }
        staged = next
    }
    val picker = if (group == AppearanceGroup.MATERIAL) rememberWallpaperPicker(departure == null) { page, id ->
        leases = ArrayList(leases).apply { add(id) }
        change(draft.replacingWallpaper(page, WallpaperSpec(imageId = id)))
    } else null
    val dirty = staged != confirmed
    val busy = departure != null || picker?.preparing == true
    fun exit(how: Departure) {
        if (departure == null) {
            picker?.cancel()
            discardSample()
            departure = how
        }
    }
    // One consumer owns both ends of each receipt. An effect keyed on "busy" can be
    // launched for an old frame just as another effect clears that receipt; it must
    // never submit the same value twice or dispatch a second pop.
    LaunchedEffect(actions, planner, backgrounds) {
        while (true) {
            snapshotFlow { ticket != null || staged != confirmed || departure != null }.first { it }
            if (departure == Departure.DISPATCHED) return@LaunchedEffect
            try {
                val id = ticket
                if (id != null) {
                    var reason: String? = null
                    val outcome = actions.result(id) { reason = it }
                    ticket = null
                    if (outcome == SaveOutcome.APPLIED) {
                        message = ""
                        confirmed = submitted
                        if (undoSubmitted) {
                            undoSubmitted = false
                            staged = submitted
                            departure = Departure.KEEP
                        }
                    } else fail(reason ?: writeFailure)
                    continue
                }
                val undo = departure == Departure.DISCARD
                val expected = if (undo) (planner.observe(PlannerQuery.Settings).first() as PolicyView).appearance else confirmed
                val next = if (undo) group.undo(baseline, confirmed, expected) else staged
                if (next != expected) {
                    val fields = group.project(next)
                    if (!undo && fields == group.project(expected)) {
                        // Other groups may have changed underneath this form. Their values
                        // are neither a reason to write nor part of this editor's undo.
                        staged = confirmed
                        continue
                    }
                    if (!undo && group == AppearanceGroup.MATERIAL) {
                        val originals = baseline.backgroundIds()
                        val held = pins.orEmpty()
                        if (originals.any { it !in held }) {
                            val attached = next.backgroundIds()
                            if (originals.any { it !in attached && it !in held }) {
                                val all = (held + (originals - attached)).toSet()
                                if (pins == null) pins = arrayListOf()
                                backgrounds.pinEditorOriginals(owner, all)
                                pins = ArrayList(all)
                                if (staged != next || departure == Departure.DISCARD) continue
                            }
                        }
                    }
                    submitted = next
                    undoSubmitted = undo
                    ticket = actions.start(PlannerCommand.EditAppearance(AppearanceFields(group, fields), expected))
                    if (ticket == null) fail(writeFailure)
                    continue
                }
                if (departure != null) {
                    if (pins != null || leases.isNotEmpty()) {
                        backgrounds.finishEditor(owner, leases)
                        pins = null
                        leases = arrayListOf()
                    }
                    if (front) {
                        departure = Departure.DISPATCHED
                        latestBack()
                    } else departure = null
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { ticket = null; fail(error.message ?: if (departure != null) cleanupFailure else writeFailure) }
        }
    }
    val request = sample
    if (request != null && !busy) LaunchedEffect(request) {
        try {
            val argb = backgrounds.wallpaperSeed(seedSource)
            if (sample === request) {
                if (argb == null) sampleError = R.string.wallpaper_seed_no_color
                else {
                    val seed = "#%06X".format(java.util.Locale.ROOT, argb and 0xffffff)
                    seedInput = seed
                    change(draft.copy(seed = seed))
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (sample === request) sampleError = R.string.wallpaper_seed_unavailable }
        finally { if (sample === request) sample = null }
    }
    val preview = if (group == AppearanceGroup.LAYOUT) draft else saved
    val material = if (group == AppearanceGroup.MATERIAL) draft else saved
    var previewPage by rememberSaveable(preview.start, target) { mutableStateOf(target ?: preview.start) }
    val transfer = if (group == AppearanceGroup.MATERIAL) rememberAppearanceTransfer(planner, backgrounds,
        available = !busy && !dirty && ticket == null) { exit(Departure.KEEP) } else null
    val origins = LocalRouteOrigins.current
    fun needsExitWork() = departure != Departure.DISPATCHED && (departure != null || staged != confirmed ||
        ticket != null || pins != null || leases.isNotEmpty() || picker?.preparing == true || sample != null)
    // Let the real stack seek or reverse untouched. Only a committed Back needs to flush IO.
    origins?.GuardCommittedPop {
        if (needsExitWork()) { exit(Departure.KEEP); true } else false
    }
    BackHandler(enabled = origins == null && front && needsExitWork()) { exit(Departure.KEEP) }
    val hasMessage = message.isNotEmpty()
    val leadSections = remember(group) {
        buildList {
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
    // The navigation entry already supplies the committed global wallpaper. A second
    // screen-sized host switched donors twice per slider change (preview, then receipt).
    Page {
        RootPane(header = {
            Column(Modifier.testTag("appearance-header")) {
                PageTitle(group.title, back = { exit(Departure.KEEP) }, backEnabled = departure == null,
                    action = {
                        TextButton(onClick = { exit(Departure.DISCARD) }, enabled = departure == null &&
                            (draft != baseline || confirmed != baseline || seedInput != baseline.seed || ticket != null)) {
                            Text(stringResource(R.string.appearance_discard))
                        }
                    })
                if (hasMessage) Text(message, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            }
        }) { top, bottom ->
            LazyColumn(Modifier.fillMaxSize().testTag("appearance-scroll"), state = scroll, contentPadding = dockListPadding(top, bottom),
                verticalArrangement = listSpacing) {
                items(leadSections, key = { it }) { section ->
                    when (section) {
                        EditorLeadSection.PREVIEW -> {
                            val page = previewPage.takeIf { it in preview.visible || it == target } ?: preview.start
                            AppearancePreview(preview, page, material.wallpaper(if (seedForm) target else page)) {
                                if (seedForm) {
                                    discardSample(); wallpaperTarget = it
                                    if (draft.palette == PaletteMode.WALLPAPER) change(draft.copy(palettePage = it))
                                }
                                previewPage = it
                            }
                        }
                        EditorLeadSection.MATERIAL -> MaterialFields(draft, ::change)
                        EditorLeadSection.WALLPAPER -> if (picker != null) {
                            WallpaperControls(draft, ::change, wallpaperTarget, { wallpaperTarget = it }, picker)
                        }
                    }
                }
                when (group) {
                    AppearanceGroup.COLOR -> item {
                        ColorFields(draft, seedInput, { discardSample(); seedInput = it }, { discardSample(); change(it) }) {
                            Choice(stringResource(R.string.wallpaper_seed_source), wallpaperTargets, target,
                                { it?.label ?: stringResource(R.string.wallpaper_global) }) {
                                discardSample(); wallpaperTarget = it
                                if (draft.palette == PaletteMode.WALLPAPER) change(draft.copy(palettePage = it))
                            }
                            if (draft.palette == PaletteMode.SEED) {
                                Secondary(stringResource(R.string.wallpaper_seed_pick),
                                    enabled = !busy && seedSource.hasImage && request == null) {
                                    sampleError = null; sample = Any()
                                }
                                if (request != null) Hint(stringResource(R.string.wallpaper_seed_reading))
                                sampleError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                            }
                            if (!seedSource.hasImage) Hint(stringResource(R.string.wallpaper_seed_no_photo))
                        }
                    }
                    AppearanceGroup.LAYOUT -> {
                        item { LayoutFields(draft, ::change) }
                        item { CompositionLocalProvider(LocalAppearance provides preview) { DayWheelPreview() } }
                        item {
                            FormGroup(frosted = true) {
                                SwitchRow(stringResource(R.string.now_marker_line), draft.showNowLine) { change(draft.copy(showNowLine = !draft.showNowLine)) }
                                SwitchRow(stringResource(R.string.now_marker_arrow), draft.nowArrow) { change(draft.copy(nowArrow = !draft.nowArrow)) }
                            }
                        }
                        item { SectionTitle(stringResource(R.string.ui_nav_order)) }
                        // Only these five rows reorder. Measure their intervals in this section,
                        // not in the lazy viewport that also contains previews and preferences.
                        item {
                            ReorderableColumn(draft.order, onSettle = { from, to -> change(draft.moveTab(from, to)) },
                                verticalArrangement = listSpacing) { index, dest, dragging ->
                                key(dest) {
                                    ReorderableItem {
                                        NavigationField(dest, index, draft, enabled = !busy, dragging = dragging, update = ::change)
                                    }
                                }
                            }
                        }
                    }
                    AppearanceGroup.MATERIAL -> {
                        item {
                            FormGroup(frosted = true) {
                                SectionTitle(stringResource(R.string.ui_course_preview))
                                // Paint the setting the adjacent switch already shows. Do not wait
                                // for Room to re-emit it or start a second window wallpaper donor.
                                CompositionLocalProvider(LocalPlannerGlassAllowed provides true, LocalAppearance provides material) {
                                    WallpaperHost(material.wallpaper(Destination.COURSES)) { CourseTimetablePreview() }
                                }
                                SwitchRow(stringResource(R.string.appearance_grid_lines), draft.showCourseGrid) { change(draft.copy(showCourseGrid = !draft.showCourseGrid)) }
                                SwitchRow(stringResource(R.string.ui_course_outline), draft.courseTextOutline) { change(draft.copy(courseTextOutline = !draft.courseTextOutline)) }
                            }
                        }
                        item { AccessibilityFields(draft, ::change) }
                        if (transfer != null) item(key = "appearance-transfer") { transfer() }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = {
                            discardSample()
                            val defaults = AppearanceFields(group, null).applyTo(draft)
                            change(defaults); seedInput = defaults.seed
                        },
                            enabled = !busy, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.ui_restore_defaults))
                        }
                    }
                }
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
        Choice(stringResource(R.string.ui_palette), PaletteMode.entries, d.palette, { stringResource(when (it) {
            PaletteMode.DYNAMIC -> R.string.ui_dynamic_color
            PaletteMode.SEED -> R.string.ui_custom_seed
            PaletteMode.WALLPAPER -> R.string.ui_wallpaper_color
        }) },
            help = stringResource(R.string.ui_dynamic_hint)) { update(d.copy(palette = it)) }
        if (d.palette == PaletteMode.SEED) TextField(colors = lineFieldColors(), value = seed, onValueChange = { text ->
            val normalized = text.uppercase(); editSeed(normalized)
            if (validSeed.matches(normalized)) update(d.copy(seed = normalized))
        }, label = { Text(stringResource(R.string.ui_seed_hex)) }, singleLine = true, isError = !validSeed.matches(seed), modifier = Modifier.fillMaxWidth().testTag("seed-color"))
        if (d.palette != PaletteMode.DYNAMIC) photoSource()
    }
}

@Composable
private fun LayoutFields(d: Appearance, update: (Appearance) -> Unit) {
    FormGroup(frosted = true) {
        Choice(stringResource(R.string.ui_spacing), listOf(false, true), d.compact, { if (it) stringResource(R.string.ui_compact) else stringResource(R.string.ui_standard) }) { update(d.copy(compact = it)) }
        Choice(stringResource(R.string.ui_nav_style), NavStyle.entries, d.navStyle, { if (it == NavStyle.FLOATING) stringResource(R.string.ui_floating_nav) else stringResource(R.string.ui_bottom_nav) }) { update(d.copy(navStyle = it)) }
        SwitchRow(stringResource(R.string.appearance_show_nav_labels), d.showNavLabels) { update(d.copy(showNavLabels = !d.showNavLabels)) }
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
        SwitchRow(stringResource(R.string.ui_frosted_glass), d.frostedGlass, enabled = d.glass,
            help = stringResource(R.string.ui_frosted_glass_help)) { update(d.copy(frostedGlass = !d.frostedGlass)) }
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
