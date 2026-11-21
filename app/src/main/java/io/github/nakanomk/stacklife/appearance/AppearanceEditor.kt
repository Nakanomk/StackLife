package io.github.nakanomk.stacklife.appearance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
private val AppearanceGroup.title get() = when (this) {
    AppearanceGroup.COLOR -> "色彩"; AppearanceGroup.LAYOUT -> "布局"; AppearanceGroup.MATERIAL -> "材质与动态"
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
                navigationIcon = { TextButton(onClick = onBack, enabled = !receipt.busy) { Text("取消") } },
                actions = {
                    TextButton(enabled = !receipt.busy && seedOK && !stale && selected != persisted,
                        onClick = { receipt.send(PlannerCommand.EditAppearance(AppearanceFields(group, selected), expected = baseline)) }) {
                        Text("保存外观")
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
                                SectionTitle("课表预览 · 示例课程")
                                CompositionLocalProvider(LocalPlannerGlassAllowed provides true) { CourseTimetablePreview() }
                                SwitchRow("课程文字描边", draft.courseTextOutline) { draft = draft.copy(courseTextOutline = !draft.courseTextOutline) }
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
                            Text("恢复默认设置")
                        }
                    }
                }
                if (stale) item { Secondary("重新载入已保存设置", enabled = !receipt.busy, onClick = reload) }
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
                    Text("${Destination.entries.first { it.key == selected }.label} · 预览", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Text("读完一章", style = MaterialTheme.typography.bodyLarge)
                    if (value.showDuration || value.showNotes) Hint(listOfNotNull("25 分钟".takeIf { value.showDuration },
                        "把问题记在书签上".takeIf { value.showNotes }).joinToString(" · "))
                }
            }
        }
    }
}

@Composable
private fun ColorFields(d: Appearance, seed: String, editSeed: (String) -> Unit, update: (Appearance) -> Unit) {
    Column {
        Choice("明暗", ThemeMode.entries, d.theme, { when (it) { ThemeMode.SYSTEM -> "随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" } }) { update(d.copy(theme = it)) }
        Choice("色彩来源", PaletteMode.entries, d.palette, { if (it == PaletteMode.DYNAMIC) "系统动态色" else "自选种子" }) { update(d.copy(palette = it)) }
        if (d.palette == PaletteMode.SEED) TextField(colors = lineFieldColors(), value = seed, onValueChange = { text ->
            val normalized = text.uppercase(); editSeed(normalized)
            if (validSeed.matches(normalized)) update(d.copy(seed = normalized))
        }, label = { Text("种子色 #RRGGBB") }, singleLine = true, isError = !validSeed.matches(seed), modifier = Modifier.fillMaxWidth().testTag("seed-color"))
        else Hint("Android 12 起可用动态色；其他系统使用默认靛蓝。")
    }
}

@Composable
private fun LayoutFields(d: Appearance, update: (Appearance) -> Unit) {
    Column {
        Choice("界面留白", listOf(false, true), d.compact, { if (it) "紧凑" else "标准" }) { update(d.copy(compact = it)) }
        Choice("导航样式", NavStyle.entries, d.navStyle, { if (it == NavStyle.FLOATING) "浮动胶囊" else "贴底导航" }) { update(d.copy(navStyle = it)) }
        Choice("待排队列", listOf(true, false), d.floatingDayTray, { if (it) "悬浮小球" else "常驻托盘" }) { update(d.copy(floatingDayTray = it)) }
        Choice("启动页", d.order, d.start, { it.label }) { update(d.copy(start = it)) }
        SectionTitle("导航次序")
        for ((i, dest) in d.order.withIndex()) {
            fun move(to: Int) { val next = d.order.toMutableList(); next[i] = next[to]; next[to] = dest; update(d.copy(order = next)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${dest.label}", Modifier.weight(1f))
                MoveButton(dest, up = true, enabled = i > 0) { move(i - 1) }
                MoveButton(dest, up = false, enabled = i < d.order.lastIndex) { move(i + 1) }
            }
        }
        SwitchRow("显示任务时长", d.showDuration) { update(d.copy(showDuration = !d.showDuration)) }
        SwitchRow("显示任务备注", d.showNotes) { update(d.copy(showNotes = !d.showNotes)) }
    }
}

@Composable
private fun MoveButton(dest: Destination, up: Boolean, enabled: Boolean, move: () -> Unit) {
    val label = if (up) "上移" else "下移"
    ToolbarIcon(if (up) R.drawable.ic_expand_less else R.drawable.ic_expand_more, "$label${dest.label}", enabled, move)
}

@Composable
private fun MaterialFields(d: Appearance, update: (Appearance) -> Unit) {
    val reason = glassUnavailableReason(d)
    Column {
        SectionTitle("材质")
        SwitchRow("启用液态玻璃", d.glass) { update(d.copy(glass = !d.glass)) }
        SwitchRow("浮动导航玻璃", d.navGlass, enabled = d.glass) { update(d.copy(navGlass = !d.navGlass)) }
        SwitchRow("助理胶囊玻璃", d.assistantGlass, enabled = d.glass) { update(d.copy(assistantGlass = !d.assistantGlass)) }
        Hint(when { reason != null -> "当前使用实色：$reason"; !d.glass -> "当前使用实色：总开关已关闭。";
            else -> "页眉、圆形操作与课程块使用玻璃；关闭后保持相同布局。" })
    }
}

@Composable
private fun AccessibilityFields(d: Appearance, update: (Appearance) -> Unit) {
    Column {
        SectionTitle("动态与辅助功能")
        SwitchRow("减弱动效", d.reduceMotion) { update(d.copy(reduceMotion = !d.reduceMotion)) }
        SwitchRow("减少透明度", d.reduceTransparency) { update(d.copy(reduceTransparency = !d.reduceTransparency)) }
        SwitchRow("增强对比", d.highContrast) { update(d.copy(highContrast = !d.highContrast)) }
        SwitchRow("随系统触感", d.haptics) { update(d.copy(haptics = !d.haptics)) }
    }
}

@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, change: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    PreferenceRow(title, label(selected)) { open = true }
    if (open) PlannerAlertDialog(onDismissRequest = { open = false }, title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                for (option in options) Row(Modifier.fillMaxWidth()
                    .selectable(option == selected, role = Role.RadioButton) { change(option); open = false }
                    .heightIn(min = 48.dp).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(option == selected, onClick = null)
                    Text(label(option), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text("取消") } })
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
