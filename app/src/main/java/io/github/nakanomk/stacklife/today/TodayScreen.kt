package io.github.nakanomk.stacklife.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.planner.DayPlan
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.workspace.Page
import io.github.nakanomk.stacklife.workspace.WorkspaceActions
import io.github.nakanomk.stacklife.workspace.rememberSubmission
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.first

private val dateHeading = DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINESE)

@Composable
internal fun TodayScreen(
    plan: DayPlan, actions: WorkspaceActions, checkSlot: suspend (PlannerCommand.SlotSet) -> String?,
    onDate: (LocalDate) -> Unit, onEdit: (String) -> Unit, onSettings: () -> Unit, navigationBar: @Composable () -> Unit,
    revealStart: Long?, onRevealed: () -> Unit,
) {
    val submission = rememberSubmission(actions)
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    var trayOpen by rememberSaveable { mutableStateOf(true) }
    if (choosingDate) DateChooser(plan.day.date, { choosingDate = false; onDate(it) }, { choosingDate = false })
    Page {
        DayHeader(plan.day.date, onPick = { choosingDate = true }, onDate = onDate,
            onToday = { onDate(LocalDate.now(plan.day.zone)) }, onSettings = onSettings)
        key(plan.day) {
            val density = LocalDensity.current
            val type = MaterialTheme.typography
            val minCard = with(density) {
                (type.titleSmall.lineHeight.toDp() + type.labelMedium.lineHeight.toDp() * 2 + 4.dp).coerceAtLeast(48.dp).roundToPx()
            }
            val minutePx = with(density) { 2.dp.toPx() }
            val scale = remember(plan.day, plan.blocks, minutePx, minCard) { DayAxisScale(plan, minutePx, minCard) }
            val initial = remember {
                val now = System.currentTimeMillis()
                val anchor = when {
                    now in plan.day.startMillis until plan.day.endMillis -> now - 60 * MINUTE_MS
                    plan.blocks.isNotEmpty() -> plan.blocks.minOf { it.startMillis } - 30 * MINUTE_MS
                    else -> plan.day.date.atTime(8, 0).atZone(plan.day.zone).toInstant().toEpochMilli()
                }
                scale.yAt(anchor)
            }
            val scroll = rememberScrollState(initial)
            LaunchedEffect(revealStart, scale) {
                val start = revealStart ?: return@LaunchedEffect
                if (plan.blocks.any { it.startMillis == start }) {
                    snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
                    scroll.scrollTo((scale.yAt(start) - with(density) { 24.dp.roundToPx() }).coerceAtLeast(0))
                    onRevealed()
                }
            }
            val drag = remember(scale, scroll) { AxisDrag(scale, scroll) }
            val send: (PlannerCommand.SlotSet) -> Unit = { submission.send(it) }
            DragEffects(drag, checkSlot)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val sideBySide = maxWidth >= 480.dp && maxWidth > maxHeight && trayOpen
                val trayLimit = maxHeight * 0.46f
                val axis: @Composable (Modifier) -> Unit = {
                    DayAxis(drag, plan.revision, !submission.busy, onEdit, send, it)
                }
                val tray: @Composable (Modifier) -> Unit = {
                    QueueTray(plan.tray, plan.revision, drag, trayOpen, { trayOpen = !trayOpen },
                        !submission.busy, onEdit, send, it)
                }
                if (sideBySide) {
                    Row(Modifier.fillMaxSize()) {
                        axis(Modifier.weight(1.6f).fillMaxHeight())
                        tray(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        axis(Modifier.fillMaxWidth().weight(1f))
                        tray(Modifier.fillMaxWidth().heightIn(max = trayLimit))
                    }
                }
                drag.dropNotice?.let { message ->
                    Snackbar(Modifier.align(Alignment.BottomCenter).padding(4.dp),
                        dismissAction = { TextButton(onClick = { drag.dropNotice = null }) { Text("知道了") } }) { Text(message) }
                }
            }
        }
        navigationBar()
    }
}

@Composable
private fun DayHeader(
    date: LocalDate, onPick: () -> Unit, onDate: (LocalDate) -> Unit, onToday: () -> Unit, onSettings: () -> Unit,
) {
    val dates: @Composable () -> Unit = {
        TextButton(onClick = { onDate(date.minusDays(1)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("前一天") }
        FilledTonalButton(onClick = onPick, modifier = Modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "选择日期，${date.year}年${date.format(dateHeading)}"
        }) { Text(date.format(dateHeading)) }
        TextButton(onClick = { onDate(date.plusDays(1)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("后一天") }
    }
    val tools: @Composable () -> Unit = {
        TextButton(onClick = onToday, modifier = Modifier.heightIn(min = 48.dp)) { Text("回到今天") }
        TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("设置") }
    }
    val shortWindow = LocalConfiguration.current.screenHeightDp < 480
    val scrollingRow = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(if (shortWindow) scrollingRow else Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("今天", (if (shortWindow) Modifier else Modifier.weight(1f)).padding(horizontal = 6.dp).semantics { heading() },
                style = MaterialTheme.typography.headlineMedium)
            if (shortWindow) dates()
            tools()
        }
        if (!shortWindow) Row(scrollingRow, verticalAlignment = Alignment.CenterVertically) { dates() }
    }
}

@Composable
private fun QueueTray(
    tasks: List<QueueTask>, revision: Long, drag: AxisDrag, expanded: Boolean, toggle: () -> Unit,
    enabled: Boolean, onEdit: (String) -> Unit, send: (PlannerCommand.SlotSet) -> Unit, modifier: Modifier,
) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "收起待排队列" else "展开待排队列",
                role = Role.Button, onClick = toggle).semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("待排队列 · ${tasks.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                LazyColumn(Modifier.fillMaxWidth().testTag("day-tray"), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (tasks.isEmpty()) item {
                        Text("队列中没有待排任务", Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    items(tasks, key = { it.id }) { task ->
                        Row(Modifier.fillMaxWidth().testTag("tray:${task.id}").clip(MaterialTheme.shapes.medium)
                            .slotDrag(SlotSource(task.id, task.minutes), drag, revision, enabled, send)
                            .clickable(enabled, onClickLabel = "安排时间", role = Role.Button) { onEdit(task.id) }
                            .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${task.minutes} 分钟 · 长按拖动", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("安排时间", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
