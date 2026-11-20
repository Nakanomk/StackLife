package io.github.nakanomk.stacklife.appearance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.DockOverlay
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.rememberDestinationCapsule
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.*

private val appearanceSaver = Saver<Appearance, String>(save = { encodeAppearance(it) }, restore = ::decodeAppearance)
private val validSeed = Regex("#[0-9A-F]{6}")
private val AppearanceGroup.title get() = when (this) {
    AppearanceGroup.COLOR -> "色彩"; AppearanceGroup.LAYOUT -> "布局"; AppearanceGroup.MATERIAL -> "材质与动态"
}

@Composable
internal fun AppearanceEditor(saved: Appearance, initialGroup: AppearanceGroup, actions: WorkspaceActions, onBack: () -> Unit) {
    var baseline by rememberSaveable(stateSaver = appearanceSaver) { mutableStateOf(saved) }
    var draft by rememberSaveable(stateSaver = appearanceSaver) { mutableStateOf(saved) }
    var seedInput by rememberSaveable { mutableStateOf(saved.seed) }
    var group by rememberSaveable { mutableStateOf(initialGroup) }
    var message by rememberSaveable { mutableStateOf("") }
    val receipt = rememberSubmission(actions, onError = { message = it }) { if (it == SaveOutcome.APPLIED) onBack() }
    val seedOK = draft.palette != PaletteMode.SEED || validSeed.matches(seedInput)
    val reload = { baseline = saved; draft = saved; seedInput = saved.seed; message = "" }
    BackHandler(enabled = receipt.busy) { }
    StackLifeTheme(draft) {
        Page {
            val scroll = rememberLazyListState()
            LazyColumn(Modifier.weight(1f).testTag("appearance-scroll"), state = scroll, contentPadding = pagePadding, verticalArrangement = listSpacing) {
                item { PageTitle("外观预览") }
                item { Hint("预览不会更改日程。取消或返回即可丢弃，保存后下次启动仍然生效。") }
                item { AppearancePreview(draft) }
                item { Choice("设置分组", AppearanceGroup.entries, group, { it.title }) { group = it } }
                item { when (group) {
                    AppearanceGroup.COLOR -> ColorFields(draft, seedInput, { seedInput = it }, { draft = it })
                    AppearanceGroup.LAYOUT -> LayoutFields(draft) { draft = it }
                    AppearanceGroup.MATERIAL -> MaterialFields(draft) { draft = it }
                } }
                item { Secondary("恢复默认外观", enabled = !receipt.busy) { draft = Appearance(); seedInput = draft.seed; message = "" }
                    Hint("只重置外观草稿，不会改动工作台的恢复策略。") }
                if (baseline != saved) item { Secondary("重新载入已保存设置", enabled = !receipt.busy, onClick = reload) }
            }
            HorizontalDivider()
            if (message.isNotEmpty()) Text(message, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = onBack, enabled = !receipt.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                Primary("保存外观", enabled = !receipt.busy && seedOK && baseline == saved && draft != saved) {
                    receipt.send(PlannerCommand.EditAppearance(AppearancePatch.Replace(draft), expected = baseline))
                }
            }
        }
    }
}

@Composable
private fun AppearancePreview(value: Appearance) {
    var selected by rememberSaveable(value.start) { mutableStateOf(value.start.key) }
    val nav = rememberDestinationCapsule(selected) { selected = it }
    val scale = LocalDensity.current.fontScale
    Surface(Modifier.fillMaxWidth().height((204 * scale.coerceAtLeast(1f)).dp), shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        DockOverlay(nav) {
            Column(Modifier.fillMaxWidth().padding(if (value.compact) 10.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(if (value.compact) 2.dp else 6.dp)) {
                Text("${Destination.entries.first { it.key == selected }.label} · 预览", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text("读完一章", style = MaterialTheme.typography.titleLarge)
                if (value.showDuration) Hint("25 分钟")
                if (value.showNotes) Hint("把问题记在书签上")
            }
        }
    }
}

@Composable
private fun ColorFields(d: Appearance, seed: String, editSeed: (String) -> Unit, update: (Appearance) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Choice("明暗", ThemeMode.entries, d.theme, { when (it) { ThemeMode.SYSTEM -> "随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" } }) { update(d.copy(theme = it)) }
        Choice("色彩来源", PaletteMode.entries, d.palette, { if (it == PaletteMode.DYNAMIC) "系统动态色" else "自选种子" }) { update(d.copy(palette = it)) }
        if (d.palette == PaletteMode.SEED) OutlinedTextField(value = seed, onValueChange = { text ->
            val normalized = text.uppercase(); editSeed(normalized)
            if (validSeed.matches(normalized)) update(d.copy(seed = normalized))
        }, label = { Text("种子色 #RRGGBB") }, singleLine = true, isError = !validSeed.matches(seed), modifier = Modifier.fillMaxWidth().testTag("seed-color"))
        else Hint("Android 12 起可用动态色；其他系统使用默认靛蓝。")
    }
}

@Composable
private fun LayoutFields(d: Appearance, update: (Appearance) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("界面留白", listOf(false, true), d.compact, { if (it) "紧凑" else "标准" }) { update(d.copy(compact = it)) }
        Choice("导航样式", NavStyle.entries, d.navStyle, { if (it == NavStyle.FLOATING) "浮动胶囊" else "贴底导航" }) { update(d.copy(navStyle = it)) }
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
        CheckRow("显示任务时长", d.showDuration) { update(d.copy(showDuration = !d.showDuration)) }
        CheckRow("显示任务备注", d.showNotes) { update(d.copy(showNotes = !d.showNotes)) }
    }
}

@Composable
private fun MoveButton(dest: Destination, up: Boolean, enabled: Boolean, move: () -> Unit) {
    val label = if (up) "上移" else "下移"
    IconButton(onClick = move, enabled = enabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .semantics { contentDescription = "$label${dest.label}" }) { Text(if (up) "↑" else "↓") }
}

@Composable
private fun MaterialFields(d: Appearance, update: (Appearance) -> Unit) {
    val reason = glassUnavailableReason(d)
    Column {
        SectionTitle("局部玻璃")
        CheckRow("启用局部玻璃", d.glass) { update(d.copy(glass = !d.glass)) }
        CheckRow("浮动导航玻璃", d.navGlass) { update(d.copy(navGlass = !d.navGlass)) }
        CheckRow("助理胶囊玻璃", d.assistantGlass) { update(d.copy(assistantGlass = !d.assistantGlass)) }
        Hint(when { reason != null -> "当前使用实色：$reason"; !d.glass -> "当前使用实色：总开关已关闭。";
            else -> "仅已开启的浮动胶囊使用折射；正文、对话与登录页保持实色。" })
        SectionTitle("动态与辅助功能")
        CheckRow("减弱动效", d.reduceMotion) { update(d.copy(reduceMotion = !d.reduceMotion)) }
        CheckRow("减少透明度", d.reduceTransparency) { update(d.copy(reduceTransparency = !d.reduceTransparency)) }
        CheckRow("增强对比", d.highContrast) { update(d.copy(highContrast = !d.highContrast)) }
        CheckRow("随系统触感", d.haptics) { update(d.copy(haptics = !d.haptics)) }
    }
}

@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, change: (T) -> Unit) {
    Column {
        SectionTitle(title)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in options) FilterChip(selected = option == selected, onClick = { change(option) },
                label = { Text(label(option)) }, modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton })
        }
    }
}
