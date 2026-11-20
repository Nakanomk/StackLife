package io.github.nakanomk.stacklife.courses

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.appearance.CapsuleScene
import io.github.nakanomk.stacklife.appearance.GlassBacking
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.LocalPlannerGlassAllowed
import io.github.nakanomk.stacklife.appearance.glassUnavailableReason
import io.github.nakanomk.stacklife.appearance.StackLifeSurface
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.planner.CourseWeek
import java.time.LocalDate

private const val weekdays = "一二三四五六日"
private val courseShape = RoundedCornerShape(8.dp)

private val coursePalette = longArrayOf(
    0xFFFA8EA5, 0xFFF79B84, 0xFFF3C383, 0xFFE5DB79,
    0xFF8AE8D8, 0xFF74A3D1, 0xFF8DB5FA, 0xFFCFBAFD,
)

internal fun courseColor(term: String, index: Int): Color {
    val size = coursePalette.size
    val start = Math.floorMod(term.hashCode(), 360) * size / 360
    val slot = (start + index % size * 3) % size
    val color = Color(coursePalette[slot])
    val cycle = index / size
    if (cycle == 0) return color
    // Bisect each gap at .5, then .25 and .75, for terms with more courses than swatches.
    val level = Integer.highestOneBit(cycle)
    return lerp(color, Color(coursePalette[(slot + 1) % size]), (cycle - level + .5f) / level)
}

@Composable
internal fun rememberCourseColor(term: String, index: Int) = remember(term, index) { courseColor(term, index) }

private val labelEdge = Color(0xB4384050)

@Composable
private fun CourseLabel(text: String, style: TextStyle, maxLines: Int, outline: Stroke?) {
    if (outline == null) {
        Text(text, color = Color.White, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        return
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(text, Modifier.drawBehind { layout?.let { drawText(it, color = labelEdge, drawStyle = outline) } },
        color = Color.White, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it })
}

private data class Tile(val index: Int, val course: ScheduledCourse, val lane: Int, val lanes: Int)

/** Each overlap group gets its own lanes. Unlike a single stack, it keeps the true period
 * span of every meeting and doesn't conceal shorter simultaneous classes. */
private fun tiles(classes: List<ScheduledCourse>): List<Tile> = buildList {
    for (day in classes.withIndex().groupBy { it.value.meeting.day }.values) {
        val sorted = day.sortedWith(compareBy({ it.value.meeting.firstPeriod }, { it.value.meeting.lastPeriod }, { it.value.meeting.title }))
        var i = 0
        while (i < sorted.size) {
            val start = i
            var last = sorted[i].value.meeting.lastPeriod
            val ends = mutableListOf<Int>()
            val lanes = mutableListOf<Int>()
            do {
                val m = sorted[i++].value.meeting
                val free = ends.indexOfFirst { it < m.firstPeriod }
                lanes.add(if (free < 0) ends.size else free)
                if (free < 0) ends.add(m.lastPeriod) else ends[free] = m.lastPeriod
                last = maxOf(last, m.lastPeriod)
            } while (i < sorted.size && sorted[i].value.meeting.firstPeriod <= last)
            for (j in start until i) add(Tile(sorted[j].index, sorted[j].value, lanes[j - start], ends.size))
        }
    }
}

@Composable
internal fun WeekGrid(term: TermSpec, week: CourseWeek, top: Dp, bottom: Dp, open: (ScheduledCourse) -> Unit) {
    val monday = remember(term.firstMonday, week.week) { term.firstMonday.plusWeeks(week.week - 1L) }
    val periods = remember(term, monday) { term.periodsOn(monday) }
    val changeover = term.switchDate?.takeIf { it > monday && it < monday.plusWeeks(1) }
    val today = LocalDate.now(campusZone)
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val periodHeight = with(density) { (typography.labelMedium.lineHeight.toDp() * 3 + 8.dp).coerceAtLeast(56.dp) }
    val measurer = rememberTextMeasurer()
    val rail = remember(density, measurer, typography.labelSmall) {
        with(density) { measurer.measure("00:00", style = typography.labelSmall).size.width.toDp() + 8.dp }
    }
    val colors = MaterialTheme.colorScheme
    var selectedDay by rememberSaveable { mutableIntStateOf(0) }
    if (selectedDay != 0) {
        val day = monday.plusDays(selectedDay - 1L)
        val courses = remember(week.classes, selectedDay) { week.classes.filter { it.meeting.day == selectedDay } }
        PlannerAlertDialog(onDismissRequest = { selectedDay = 0 }, title = { Text("${day.monthValue}月${day.dayOfMonth}日 · 周${weekdays[selectedDay - 1]}") },
            text = {
                if (courses.isEmpty()) Text("当天没有课程") else LazyColumn {
                    items(courses) { course -> ListItem(headlineContent = { Text(course.meeting.title) },
                        supportingContent = { Text("${course.meeting.periodLabel()} · ${course.meeting.room.ifBlank { "教室未提供" }}") },
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { selectedDay = 0; open(course) }) }
                }
            }, confirmButton = { TextButton(onClick = { selectedDay = 0 }) { Text("关闭") } })
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("week-grid")) {
        val dayWidth = ((maxWidth - rail) / 7).coerceAtLeast(0.dp)
        Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0), topBar = {
            Row(Modifier.fillMaxWidth().padding(top = top).background(colors.surface), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(rail), contentAlignment = Alignment.Center) {
                    Text("${monday.monthValue}月", style = typography.labelSmall, maxLines = 1, color = colors.onSurfaceVariant)
                }
                for (i in 0..6) {
                    val date = monday.plusDays(i.toLong())
                    Column(Modifier.width(dayWidth).heightIn(min = 48.dp).clickable(onClickLabel = "查看当天课程", role = Role.Button) { selectedDay = i + 1 }
                        .semantics { contentDescription = "${date.year}年${date.monthValue}月${date.dayOfMonth}日，周${weekdays[i]}" },
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        val ink = if (date == today) colors.primary else colors.onSurface
                        Text(if (density.fontScale >= 1.35f) weekdays[i].toString() else "周${weekdays[i]}",
                            color = ink, style = typography.labelMedium, maxLines = 1)
                        Text(if (density.fontScale >= 1.35f) "${date.dayOfMonth}" else "${date.monthValue}/${date.dayOfMonth}",
                            color = if (date == today) ink else colors.onSurfaceVariant, style = typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }) { insets ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = insets.calculateTopPadding())) {
                if (week.classes.isEmpty()) Text("本周没有课程", Modifier.padding(start = rail, top = 4.dp, bottom = 4.dp),
                    style = typography.bodySmall, color = colors.onSurfaceVariant)
                if (changeover != null) Text("${changeover.monthValue}月${changeover.dayOfMonth}日起切换作息，点课程查看实际时间。",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = typography.bodySmall, color = colors.onSurfaceVariant)
                PeriodGrid(term.id, week, periods, showTimes = changeover == null, dayCount = 7,
                    highlightedDay = today.toEpochDay() - monday.toEpochDay(), rail, dayWidth, periodHeight, open)
                Spacer(Modifier.height(bottom + 4.dp))
            }
        }
    }
}

/** Only the real grid and rail are recorded as the backdrop; tile labels never sample themselves. */
@Composable
private fun PeriodGrid(termId: String, week: CourseWeek, periods: List<PeriodTime>, showTimes: Boolean, dayCount: Int,
    highlightedDay: Long, rail: Dp, dayWidth: Dp, periodHeight: Dp, open: ((ScheduledCourse) -> Unit)? = null) {
    val arrangement = remember(week.classes) { tiles(week.classes) }
    val pigments = remember(arrangement, termId, week.colorIndices) {
        arrangement.map { courseColor(termId, week.colorIndices.getValue(it.course.meeting.title)) }
    }
    val appearance = LocalAppearance.current
    val lit = LocalPlannerGlassAllowed.current && appearance.glass && glassUnavailableReason(appearance) == null
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val outline = if (LocalAppearance.current.courseTextOutline) remember(density) {
        Stroke(with(density) { 1.05.dp.toPx().coerceAtLeast(2f) }, join = StrokeJoin.Round)
    } else null
    val ruleBlend = if (colors.surface.luminance() < .5f) BlendMode.Screen else BlendMode.Multiply
    GlassBacking(Modifier.fillMaxWidth().height(periodHeight * periods.size), background = {
        Box(Modifier.fillMaxSize().background(colors.surface).drawBehind {
            val x0 = rail.toPx(); val cell = dayWidth.toPx()
            drawRect(colors.surfaceContainerLow, size = Size(x0, size.height))
            if (highlightedDay >= 0 && highlightedDay < dayCount) drawRect(colors.primaryContainer.copy(alpha = .28f),
                Offset(x0 + cell * highlightedDay.toInt(), 0f), Size(cell, size.height))
        }) {
            if (lit) Spacer(Modifier.matchParentSize().blur(4.dp).drawBehind {
                val x0 = rail.toPx(); val cell = dayWidth.toPx(); val row = periodHeight.toPx()
                for (j in arrangement.indices) {
                    val tile = arrangement[j]
                    val meeting = tile.course.meeting
                    val w = cell / tile.lanes
                    val h = row * (meeting.lastPeriod - meeting.firstPeriod + 1)
                    val inset = minOf(8.dp.toPx(), w * .18f, h * .18f)
                    val x = x0 + cell * (meeting.day - 1) + w * tile.lane
                    val y = row * (meeting.firstPeriod - 1)
                    drawRoundRect(pigments[j].copy(alpha = .62f), Offset(x + inset, y + inset),
                        Size(w - 2 * inset, h - 2 * inset), CornerRadius(5.dp.toPx()))
                }
            })
            Canvas(Modifier.matchParentSize()) {
                val x0 = rail.toPx(); val cell = dayWidth.toPx(); val row = periodHeight.toPx()
                val rule = 1.dp.toPx()
                for (i in 0..dayCount) {
                    val x = x0 + cell * i
                    drawLine(colors.outlineVariant, Offset(x, 0f), Offset(x, size.height), rule, blendMode = ruleBlend)
                }
                for (i in 0..periods.size) drawLine(colors.outlineVariant, Offset(0f, row * i), Offset(size.width, row * i), rule, blendMode = ruleBlend)
            }
            Column(Modifier.width(rail)) {
                for ((i, period) in periods.withIndex()) Column(Modifier.fillMaxWidth().height(periodHeight),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${i + 1}", style = typography.labelMedium, color = colors.onSurface)
                    if (showTimes) {
                        Text(period.start.toString(), style = typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                        Text(period.end.toString(), style = typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }, foreground = {
        for (j in arrangement.indices) {
            val tile = arrangement[j]
            val m = tile.course.meeting
            val w = dayWidth / tile.lanes
            CourseTile(tile, pigments[j], outline, open,
                Modifier.offset(x = rail + dayWidth * (m.day - 1) + w * tile.lane, y = periodHeight * (m.firstPeriod - 1))
                    .size(w, periodHeight * (m.lastPeriod - m.firstPeriod + 1)).padding(1.dp))
        }
    })
}

@Composable
private fun CourseTile(tile: Tile, swatch: Color, outline: Stroke?, open: ((ScheduledCourse) -> Unit)?, modifier: Modifier) {
    val m = tile.course.meeting
    val typography = MaterialTheme.typography
    val description = remember(tile.course) {
        "${m.title}，周${weekdays[m.day - 1]}，${m.periodLabel()}，${m.room.ifBlank { "教室未提供" }}" +
            if (tile.course.conflicts.isEmpty()) "" else "，时段冲突：${tile.course.conflicts.joinToString("、")}"
    }
    StackLifeSurface(CapsuleScene.COURSE, modifier, solid = swatch, shape = courseShape, tint = swatch) {
        val action = if (open == null) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = "查看固定课程") {
            open(tile.course)
        }
        Column(Modifier.fillMaxSize().clipToBounds().testTag("grid-course:${tile.index}").then(action)
            .semantics { contentDescription = description }.padding(horizontal = 3.dp, vertical = 4.dp)) {
            if (tile.course.conflicts.isNotEmpty()) CourseLabel("冲突", typography.labelSmall, 1, outline)
            CourseLabel(m.title, typography.labelMedium, 5, outline)
            if (m.room.isNotEmpty()) CourseLabel("@${m.room}", typography.labelSmall, 3, outline)
        }
    }
}

/** A read-only three-day excerpt; its examples belong to this preview, not to a user's timetable. */
@Composable
internal fun CourseTimetablePreview() {
    val term = remember { TermSpec("外观预览", LocalDate.of(2026, 9, 7), 1, BellSchedule.SUMMER) }
    val periods = remember(term) { term.periodsOn(term.firstMonday).take(3) }
    val week = remember(term) {
        val physics = term.meeting(1, 1, "大学物理", "A101", 1, 2)
        val design = term.meeting(1, 2, "设计基础", "B202", 2, 3)
        CourseWeek(term, 1, listOf(ScheduledCourse(term.id, "preview", physics), ScheduledCourse(term.id, "preview", design)),
            mapOf(physics.title to 0, design.title to 1))
    }
    val typography = MaterialTheme.typography
    val periodHeight = with(LocalDensity.current) { (typography.labelMedium.lineHeight.toDp() * 2 + 4.dp).coerceAtLeast(36.dp) }
    val rail = 32.dp
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("course-timetable-preview")) {
        val dayWidth = ((maxWidth - rail) / 3).coerceAtLeast(0.dp)
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(rail))
                for (i in 0..2) Box(Modifier.width(dayWidth).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Text("周${weekdays[i]}", style = typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PeriodGrid(term.id, week, periods, showTimes = false, dayCount = 3, highlightedDay = 0L, rail, dayWidth, periodHeight)
        }
    }
}
