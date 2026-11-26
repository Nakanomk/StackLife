package io.github.nakanomk.stacklife.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.appearance.CardFrostGroup
import io.github.nakanomk.stacklife.courses.CourseReadPanel
import io.github.nakanomk.stacklife.courses.WeekGrid
import io.github.nakanomk.stacklife.courses.occurrenceKey
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.planner.ReminderSubject
import io.github.nakanomk.stacklife.reminders.ReminderRulesEditor
import io.github.nakanomk.stacklife.transitions.CardMetadata
import io.github.nakanomk.stacklife.workspace.*
import kotlinx.coroutines.launch

private const val composeLicense = "StackLife GPL-3.0-or-later · AndroidX Compose Apache-2.0"
private const val preferenceSource = "BiliPai 5893c2c80739e3759ac64ae5f232cf03a94da7f8"

internal val componentExamples: List<GalleryEntry> = listOf(
    GalleryEntry("component-theme-roles", GalleryGroup.COMPONENT, "Type & color roles",
        "StackLifeTheme.kt",
        "StackLifeTheme.kt · workspace/WorkspaceUi.kt (SectionTitle, Hint) · MaterialKolor 6.0.0 (83864a90eeb6f16f63295bceda40a4e795baa860)",
        "$composeLicense · MaterialKolor MIT / color utilities Apache-2.0",
        "The installed theme supplies the actual type scale and foreground/background pairs; no palette is stored in the example.",
        "Change the catalog theme and font size; compare the same labels on each container.") { ThemeRolesExample() },
    GalleryEntry("component-card-pills", GalleryGroup.COMPONENT, "Card & independent action pills",
        "appearance/StackLifeSurface.kt",
        "appearance/StackLifeSurface.kt (CardFrostGroup, FrostedCard) · workspace/WorkspaceUi.kt (Actions, ActionPill) · transitions/DetailCard.kt (card text)",
        "$composeLicense · Miuix Apache-2.0",
        "Production slots use the built-in preview note. The card and both pills change the same local disclosure, not a task record.",
        "Tap the card, then use each enabled pill; the note and disabled action must agree.") { CardPillsExample() },
    GalleryEntry("component-reminder-form", GalleryGroup.COMPONENT, "Expandable course reminder form",
        "reminders/ReminderRulesEditor.kt",
        "reminders/ReminderRulesEditor.kt · workspace/ExpandablePreferenceHeader.kt · AndroidX AnimateBoundsModifierSample.kt (94b9394b) · BiliPai ProfileIdentityHeader.kt (c7f835b3)",
        "$composeLicense · BiliPai GPL-3.0",
        "One live title moves between the measured header anchors; the leading icon fades. The reminder draft belongs to the actual editor and writes only to the Gallery planner.",
        "Expand and reverse halfway. Check the title, icon, and draft at 200% font and in reduced motion. Cancel to discard; reopen and save to update the summary.") {
        ExampleColumn { FormGroup(frosted = true) { ReminderRulesEditor(LocalGalleryWorkspace.current, ReminderSubject.CourseDefault) } }
    },
    GalleryEntry("component-help-snackbar", GalleryGroup.COMPONENT, "Setting help & actionable Snackbar",
        "workspace/SettingInfo.kt",
        "workspace/SettingInfo.kt (SettingInfo, SettingsHelpHost; $preferenceSource, AdaptiveTooltip.kt) · MainActivity.kt (SnackbarHost) · AndroidX SnackbarHost.kt 296313a488a01c84ddcb0801d4efee640a14070c",
        "$composeLicense · BiliPai GPL-3.0",
        "The production help host owns dismissal. Material’s SnackbarHost, as used in MainActivity, offers undo only for a local hidden note, never a fabricated Planner receipt.",
        "Open the info button and press Back. Hide the note, then tap Undo or let the message expire.") { HelpSnackbarExample() },
    GalleryEntry("component-course-grid", GalleryGroup.COMPONENT, "Course tiles & empty day",
        "courses/WeekGrid.kt",
        "courses/WeekGrid.kt (WeekGrid → PeriodGrid → CourseTile) · courses/CourseHome.kt (CourseReadPanel) · PlannerQuery.Week",
        "$composeLicense · MaterialKolor MIT / color utilities Apache-2.0 · Miuix Apache-2.0",
        "The unmodified grid reads the Gallery term. Dates open its actual day sheet, including the no-classes branch; tile detail reads the very same stored occurrence. No import or web route.",
        "Tap a tile and inspect its room and periods; close it. Tap a date without tiles and check the empty-day message.") { CourseGridExample() },
    componentWallpaperEntry,
)

@Composable
private fun ExampleColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun ThemeRolesExample() {
    val colors = MaterialTheme.colorScheme
    ExampleColumn {
        FormGroup {
            Text("Title large", style = MaterialTheme.typography.titleLarge)
            SectionTitle("Section title · titleMedium")
            Text("Body large", style = MaterialTheme.typography.bodyLarge)
            Hint("Hint · bodyMedium · onSurfaceVariant")
            CardMetadata { Text("Card metadata · labelMedium") }
        }
        ColorPair("Primary / onPrimary", colors.primary, colors.onPrimary)
        ColorPair("Primary container / onPrimaryContainer", colors.primaryContainer, colors.onPrimaryContainer)
        ColorPair("Secondary container / onSecondaryContainer", colors.secondaryContainer, colors.onSecondaryContainer)
        ColorPair("Tertiary container / onTertiaryContainer", colors.tertiaryContainer, colors.onTertiaryContainer)
        ColorPair("Error container / onErrorContainer", colors.errorContainer, colors.onErrorContainer)
    }
}

@Composable
private fun ColorPair(label: String, background: Color, foreground: Color) {
    Surface(Modifier.fillMaxWidth(), color = background, contentColor = foreground, shape = MaterialTheme.shapes.medium) {
        Text(label, Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun CardPillsExample() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    CardFrostGroup(enabled = true, modifier = Modifier.fillMaxSize()) {
        ExampleColumn {
            MaterialNoteCard(showNote = expanded, onClick = { expanded = !expanded })
            Actions {
                ActionPill(uiText("Show note", "展开笔记"), { expanded = true }, enabled = !expanded, prominent = true)
                ActionPill(uiText("Hide note", "收起笔记"), { expanded = false }, enabled = expanded)
            }
        }
    }
}

@Composable
private fun HelpSnackbarExample() {
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var visible by rememberSaveable { mutableStateOf(true) }
    SettingsHelpHost {
        Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0), snackbarHost = { SnackbarHost(host) }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormGroup {
                    SettingLabel(stringResource(R.string.ui_show_notes), uiText(
                        "This example only changes the visibility of the note below.", "本示例仅改变下方笔记的可见性。"))
                    if (visible) Hint(stringResource(R.string.ui_sample_note))
                }
                ActionPill(if (visible) uiText("Hide note", "收起笔记") else uiText("Show note", "展开笔记"), onClick = {
                    if (visible) {
                        visible = false
                        scope.launch {
                            if (host.showSnackbar(uiText("Note hidden", "已收起笔记"), actionLabel = uiText("Undo", "撤回"),
                                    withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) visible = true
                        }
                    } else {
                        host.currentSnackbarData?.dismiss()
                        visible = true
                    }
                })
            }
        }
    }
}

@Composable
private fun CourseGridExample() {
    val model = LocalGalleryWorkspace.current
    val state by remember(model) { model.week(PlannerQuery.Week("gallery-week", 1)) }.collectAsStateWithLifecycle(ReadState.Loading)
    ReadPanel(state) { week ->
        val term = week.term
        if (term == null) {
            Hint(uiText("The sample term is unavailable.", "未找到示例学期。"))
        } else {
            var selected by rememberSaveable { mutableStateOf<String?>(null) }
            WeekGrid(term, week, 0.dp, 0.dp) { selected = it.occurrenceKey() }
            val course = remember(week.classes, selected) { selected?.let { key -> week.classes.firstOrNull { it.occurrenceKey() == key } } }
            if (course != null) {
                CourseReadPanel(course, dayLabel = null, dayCourses = emptyList(), model = model, close = { selected = null })
            }
        }
    }
}
