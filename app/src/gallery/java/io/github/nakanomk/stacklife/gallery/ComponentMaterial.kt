package io.github.nakanomk.stacklife.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.RootPane
import io.github.nakanomk.stacklife.RootToolbar
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.courses.CourseTimetablePreview
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.planner.WallpaperPreset
import io.github.nakanomk.stacklife.planner.WallpaperSpec
import io.github.nakanomk.stacklife.transitions.CardMetadata
import io.github.nakanomk.stacklife.transitions.CardSummary
import io.github.nakanomk.stacklife.transitions.CardTitle
import io.github.nakanomk.stacklife.workspace.FormGroup
import io.github.nakanomk.stacklife.workspace.Hint
import io.github.nakanomk.stacklife.workspace.SectionTitle

internal val componentWallpaperEntry = GalleryEntry("material-wallpaper-chrome", GalleryGroup.MATERIAL, "Wallpaper, chrome & solid fallback",
    "RootChrome.kt",
    "RootChrome.kt (RootPane, RootToolbar, ScrollEdge) · StackLifeTheme.kt (system bars) · appearance/{WallpaperHost,StackLifeSurface,PlatformAppearance}.kt · courses/WeekGrid.kt (CourseTimetablePreview)",
    "StackLife GPL-3.0-or-later · BiliPai wallpaper GPL-3.0 (5893c2c80739e3759ac64ae5f232cf03a94da7f8) · Miuix blur 0.9.5-beta02 / AndroidX Apache-2.0",
    "Both cards share the same built-in wallpaper. The reference opts out of card frost; the other uses the real backdrop and device gates. The Activity theme owns the actual protected status bar; this content does not draw a replica or extend chrome into it.",
    "Scroll under the toolbar. Open its gear to switch glass off, withhold the backdrop, increase contrast or reduce transparency. Compare card text and edges before and after; a device restriction must still win.") { WallpaperChromeExample() }

private val bluePlaid = WallpaperSpec(preset = WallpaperPreset.BLUE)

@Composable
private fun WallpaperChromeExample() {
    val base = LocalAppearance.current
    val hostAllowsGlass = LocalPlannerGlassAllowed.current
    var settings by rememberSaveable { mutableStateOf(false) }
    var solid by rememberSaveable { mutableStateOf(false) }
    var withhold by rememberSaveable { mutableStateOf(false) }
    var contrast by rememberSaveable { mutableStateOf(false) }
    var reduceTransparency by rememberSaveable { mutableStateOf(false) }
    val appearance = remember(base, solid, contrast, reduceTransparency) {
        base.copy(glass = base.glass && !solid, highContrast = base.highContrast || contrast,
            reduceTransparency = base.reduceTransparency || reduceTransparency)
    }
    val allowed = hostAllowsGlass && !withhold
    StackLifeTheme(appearance) {
        CompositionLocalProvider(LocalPlannerGlassAllowed provides allowed) {
            // If the outer Gallery already owns this preset, RootPane replays its recorded plane.
            // Contrast/transparency must create a solid local surface instead of inheriting that image.
            WallpaperHost(bluePlaid, drawBehindContent = wallpaperUsesSolidSurface()) {
                RootPane(header = {
                    RootToolbar(Destination.WORKSPACE, onSettings = { settings = true }) {
                        Text(stringResource(R.string.ui_materials), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }) { top, bottom ->
                    CardFrostGroup(true, Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = top + 12.dp, bottom = bottom + 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val reason = glassUnavailableReason(appearance)
                            FormGroup {
                                SectionTitle(uiText("Material policy", "材质策略"))
                                Hint(when {
                                    reason != null -> stringResource(R.string.ui_solid_because, reason)
                                    !appearance.glass -> stringResource(R.string.ui_solid_disabled)
                                    !allowed -> uiText("Solid: the preview backdrop is disabled.", "实色：示例背景采样已关闭。")
                                    else -> uiText("Glass enabled. Compare the two card surfaces below.", "已启用玻璃。请比较下方两张卡片的表面。")
                                })
                            }
                            SectionTitle(uiText("Current policy", "当前策略"))
                            MaterialNoteCard()
                            SectionTitle(uiText("Solid reference", "实色对照"))
                            CardFrostGroup(enabled = false) { MaterialNoteCard() }
                            SectionTitle(Destination.COURSES.label)
                            CourseTimetablePreview()
                        }
                    }
                }
                if (settings) PlannerAlertDialog(onDismissRequest = { settings = false }, title = {
                    Text(stringResource(R.string.ui_materials))
                }, text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SwitchRow(stringResource(R.string.ui_liquid_glass), appearance.glass, enabled = base.glass) { solid = !solid }
                        SwitchRow(uiText("Allow preview backdrop", "允许示例背景采样"), allowed, enabled = hostAllowsGlass) { withhold = !withhold }
                        SwitchRow(stringResource(R.string.ui_increase_contrast), appearance.highContrast, enabled = !base.highContrast) { contrast = !contrast }
                        SwitchRow(stringResource(R.string.ui_reduce_transparency), appearance.reduceTransparency, enabled = !base.reduceTransparency) { reduceTransparency = !reduceTransparency }
                    }
                }, confirmButton = { TextButton(onClick = { settings = false }) { Text(stringResource(R.string.ui_close_panel)) } })
            }
        }
    }
}

@Composable
internal fun MaterialNoteCard(showNote: Boolean = true, onClick: (() -> Unit)? = null) {
    FrostedCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardTitle(stringResource(R.string.ui_sample_task))
            CardMetadata { Text(stringResource(R.string.ui_sample_duration)) }
            if (showNote) CardSummary(stringResource(R.string.ui_sample_note))
        }
    }
}
