package io.github.nakanomk.stacklife.courses

import java.time.format.FormatStyle
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateTextStyle
import io.github.nakanomk.stacklife.i18n.AppLanguage
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.ReadState
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.materialkolor.hct.Hct
import com.materialkolor.ktx.toColor
import io.github.nakanomk.stacklife.appearance.CapsuleScene
import io.github.nakanomk.stacklife.appearance.GlassBacking
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.StackLifeSurface
import io.github.nakanomk.stacklife.appearance.wallpaperBackground
import io.github.nakanomk.stacklife.ScrollEdge
import io.github.nakanomk.stacklife.appearance.plannerBackdrop
import io.github.nakanomk.stacklife.appearance.rememberPlannerBackdrop
import io.github.nakanomk.stacklife.planner.CourseWeek
import java.time.LocalDate

private val courseShape = RoundedCornerShape(8.dp)

// WakeUp's timetable swatches measure H≈7° for rose, C≈30–47, and T≈65–86.
// Step around the hue wheel rather than blending RGB swatches through gray.
internal fun courseColor(term: String, index: Int): Color {
    val startHue = 7.0 + Math.floorMod(term.hashCode(), 8) * 45.0
    val hue = (startHue + index * 137.507764) % 360.0 // Golden angle: no palette wrap at eight courses.
    val tone = 71.0 + (index % 4) * 4.0
    return Hct.from(hue, 39.0, tone).toColor()
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

private data class PeriodRail(val count: Int, val times: List<PeriodTime>, val changeover: LocalDate?)

/** Mixed calendars share period numbers, not necessarily bell times. Don't print a misleading clock rail. */
private fun periodRail(term: TermSpec, monday: LocalDate, calendars: Set<CourseCalendar?>, lastMeetingPeriod: Int): PeriodRail {
    val first = calendars.singleOrNull()?.periods(term, monday).orEmpty()
    var count = lastMeetingPeriod
    var changeover: LocalDate? = null
    for (calendar in calendars.filterNotNull()) for (n in 0L..6L) {
        val date = monday.plusDays(n)
        val times = calendar.periods(term, date)
        count = maxOf(count, times.size)
        if (calendars.size == 1 && times != first && changeover == null) changeover = date
    }
    return PeriodRail(count, first, changeover)
}

@Composable
internal fun WeekGrid(term: TermSpec, week: CourseWeek, top: Dp, bottom: Dp, open: (ScheduledCourse) -> Unit) {
    WeekGrid(term, week.week, remember(week) { ReadState.Loaded(week) }, top, bottom, emptyList(), null, open)
}

@Composable
internal fun WeekGrid(term: TermSpec, number: Int, state: ReadState<CourseWeek>, top: Dp, bottom: Dp, sources: List<String>,
    openDay: ((Int) -> Unit)?, open: (ScheduledCourse) -> Unit) {
    val monday = remember(term.firstMonday, number) { term.firstMonday.plusWeeks(number - 1L) }
    val ready = (state as? ReadState.Loaded)?.value
    val calendars = remember(sources, ready?.classes) {
        buildSet {
            sources.forEach { add(calendarFor(it)) }
            ready?.classes?.forEach { add(calendarFor(it.source)) }
            if (isEmpty()) add(SchoolImports.default.calendar) // Empty manual timetable.
        }
    }
    val today = LocalDate.now(calendars.singleOrNull()?.zone ?: java.time.ZoneId.systemDefault())
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val periodHeight = with(density) { (typography.labelMedium.lineHeight.toDp() * 3 + 8.dp).coerceAtLeast(56.dp) }
    val measurer = rememberTextMeasurer()
    val rail = remember(density, measurer, typography.labelSmall) {
        with(density) { measurer.measure("00:00", style = typography.labelSmall).size.width.toDp() + 8.dp }
    }
    val colors = MaterialTheme.colorScheme
    val locale = AppLanguage.locale
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
    var selectedDay by rememberSaveable { mutableIntStateOf(0) }
    if (openDay == null && selectedDay != 0 && ready != null) {
        val day = monday.plusDays(selectedDay - 1L)
        val courses = remember(ready.classes, selectedDay) { ready.classes.filter { it.meeting.day == selectedDay } }
        CourseReadPanel(null, day.format(dateFormat), courses, open = { selectedDay = 0; open(it) }, close = { selectedDay = 0 })
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("week-grid")) {
        val dayWidth = ((maxWidth - rail) / 7).coerceAtLeast(0.dp)
        val scrollingPlane = rememberPlannerBackdrop()
        Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0), topBar = {
            Box(Modifier.fillMaxWidth().then(if (scrollingPlane == null) Modifier.wallpaperBackground(colors.surface) else Modifier)) {
                if (scrollingPlane != null) ScrollEdge(scrollingPlane, Modifier.matchParentSize(), fadeAbove = 24.dp)
                Row(Modifier.padding(top = top).fillMaxWidth().testTag("week-dates"), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(rail), contentAlignment = Alignment.Center) {
                            Text(monday.month.getDisplayName(DateTextStyle.SHORT, locale), style = typography.labelSmall, maxLines = 1, color = colors.onSurfaceVariant)
                        }
                        for (i in 0..6) {
                            val date = monday.plusDays(i.toLong())
                            val dateDescription = date.format(dateFormat)
                            Column(Modifier.width(dayWidth).heightIn(min = 48.dp)
                                .clickable(enabled = ready != null, onClickLabel = stringResource(R.string.courses_open_day), role = Role.Button) {
                                    if (openDay != null) openDay(i + 1) else selectedDay = i + 1
                                }
                                .semantics { contentDescription = dateDescription },
                                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                val ink = if (date == today) colors.primary else colors.onSurface
                                Text(date.dayOfWeek.getDisplayName(if (density.fontScale >= 1.35f) DateTextStyle.NARROW else DateTextStyle.SHORT, locale),
                                    color = ink, style = typography.labelMedium, maxLines = 1)
                                Text(if (density.fontScale >= 1.35f) "${date.dayOfMonth}" else "${date.monthValue}/${date.dayOfMonth}",
                                    color = if (date == today) ink else colors.onSurfaceVariant, style = typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
            }
        }) { insets ->
            Box(Modifier.fillMaxSize().plannerBackdrop(scrollingPlane).wallpaperBackground(colors.surface)) {
                if (ready == null) Box(Modifier.fillMaxSize().padding(insets)) { ReadPanel(state) {} }
                else {
                    val railLayout = remember(term, monday, calendars, ready.classes) {
                        periodRail(term, monday, calendars, ready.classes.maxOfOrNull { it.meeting.lastPeriod } ?: 0)
                    }
                    val changeover = railLayout.changeover
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = insets.calculateTopPadding())) {
                        if (ready.classes.isEmpty()) Text(stringResource(R.string.courses_no_classes_week), Modifier.padding(start = rail, top = 4.dp, bottom = 4.dp),
                            style = typography.bodySmall, color = colors.onSurfaceVariant)
                        if (changeover != null) Text(stringResource(R.string.courses_changeover, changeover.format(dateFormat)),
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = typography.bodySmall, color = colors.onSurfaceVariant)
                        PeriodGrid(term.id, ready, railLayout.times, showTimes = changeover == null, dayCount = 7,
                            highlightedDay = today.toEpochDay() - monday.toEpochDay(), rail, dayWidth, periodHeight, open, railLayout.count)
                        Spacer(Modifier.height(bottom + 4.dp))
                    }
                }
            }
        }
    }
}

/** Only the real grid and rail are recorded as the backdrop; tile labels never sample themselves. */
@Composable
private fun PeriodGrid(termId: String, week: CourseWeek, periods: List<PeriodTime>, showTimes: Boolean, dayCount: Int,
    highlightedDay: Long, rail: Dp, dayWidth: Dp, periodHeight: Dp, open: ((ScheduledCourse) -> Unit)? = null, periodCount: Int = periods.size) {
    val arrangement = remember(week.classes) { tiles(week.classes) }
    val pigments = remember(termId, week.colorIndices) {
        week.colorIndices.mapValues { (_, index) -> courseColor(termId, index) }
    }
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val outline = if (LocalAppearance.current.courseTextOutline) remember(density) {
        Stroke(with(density) { 1.05.dp.toPx().coerceAtLeast(2f) }, join = StrokeJoin.Round)
    } else null
    val ruleBlend = if (colors.surface.luminance() < .5f) BlendMode.Screen else BlendMode.Multiply
    val gridLines = LocalAppearance.current.showCourseGrid
    GlassBacking(Modifier.fillMaxWidth().height(periodHeight * periodCount), background = {
        Box(Modifier.fillMaxSize().wallpaperBackground(colors.surface).drawBehind {
            val x0 = rail.toPx(); val cell = dayWidth.toPx(); val row = periodHeight.toPx()
            if (highlightedDay >= 0 && highlightedDay < dayCount) drawRect(colors.primaryContainer.copy(alpha = .28f),
                Offset(x0 + cell * highlightedDay.toInt(), 0f), Size(cell, size.height))
            if (!gridLines) return@drawBehind
            val rule = 1.dp.toPx()
            for (i in 0..dayCount) {
                val x = x0 + cell * i
                drawLine(colors.outlineVariant, Offset(x, 0f), Offset(x, size.height), rule, blendMode = ruleBlend)
            }
            // A stroke centred on the texture boundary loses half its coverage before sampling.
            for (i in 0..periodCount) {
                val y = when (i) { 0 -> rule / 2; periodCount -> size.height - rule / 2; else -> row * i }
                drawLine(colors.outlineVariant, Offset(0f, y), Offset(size.width, y), rule, blendMode = ruleBlend)
            }
        }) {
            Column(Modifier.width(rail)) {
                for (i in 0 until periodCount) Column(Modifier.fillMaxWidth().height(periodHeight),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${i + 1}", style = typography.labelMedium, color = colors.onSurface)
                    val period = periods.getOrNull(i)
                    if (showTimes && period != null) {
                        Text(period.start.toString(), style = typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                        Text(period.end.toString(), style = typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }, foreground = {
        for (tile in arrangement) {
            val m = tile.course.meeting
            val w = dayWidth / tile.lanes
            CourseTile(tile, pigments.getValue(m.title), outline, open,
                Modifier.offset(x = rail + dayWidth * (m.day - 1) + w * tile.lane, y = periodHeight * (m.firstPeriod - 1))
                    .size(w, periodHeight * (m.lastPeriod - m.firstPeriod + 1)).padding(1.dp))
        }
    })
}

@Composable
private fun CourseTile(tile: Tile, swatch: Color, outline: Stroke?, open: ((ScheduledCourse) -> Unit)?, modifier: Modifier) {
    val m = tile.course.meeting
    val typography = MaterialTheme.typography
    val description = stringResource(R.string.courses_tile_description, m.title, weekday(m.day), m.periodLabel(), m.room.ifBlank { stringResource(R.string.courses_no_room) }) +
        if (tile.course.conflicts.isEmpty()) "" else stringResource(R.string.courses_tile_conflict, tile.course.conflicts.joinToString(stringResource(R.string.courses_list_separator)))
    val interaction = if (open == null) null else remember { MutableInteractionSource() }
    StackLifeSurface(CapsuleScene.COURSE, modifier, solid = swatch, shape = courseShape, tint = swatch, interactionSource = interaction) {
        val action = if (open == null) Modifier else Modifier.clickable(interactionSource = interaction, role = Role.Button, onClickLabel = stringResource(R.string.courses_view_fixed_class)) {
            open(tile.course)
        }
        Column(Modifier.fillMaxSize().clipToBounds().testTag("grid-course:${tile.index}").then(action)
            .semantics { contentDescription = description }.padding(horizontal = 3.dp, vertical = 4.dp)) {
            if (tile.course.conflicts.isNotEmpty()) CourseLabel(stringResource(R.string.courses_conflict), typography.labelSmall, 1, outline)
            CourseLabel(m.title, typography.labelMedium, 5, outline)
            if (m.room.isNotEmpty()) CourseLabel("@${m.room}", typography.labelSmall, 3, outline)
        }
    }
}

/** A read-only three-day excerpt; its examples belong to this preview, not to a user's timetable. */
@Composable
internal fun CourseTimetablePreview() {
    val term = remember { TermSpec("appearance-preview", LocalDate.of(2026, 9, 7), 1, BellSchedule.SUMMER) }
    val periods = remember(term) { SchoolImports.default.calendar.periods(term, term.firstMonday).take(3) }
    val physicsLabel = stringResource(R.string.courses_sample_physics)
    val designLabel = stringResource(R.string.courses_sample_design)
    val week = remember(term, physicsLabel, designLabel) {
        val physics = term.meeting(1, 1, physicsLabel, "A101", 1, 2)
        val design = term.meeting(1, 2, designLabel, "B202", 2, 3)
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
                    Text(weekday(i + 1), style = typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PeriodGrid(term.id, week, periods, showTimes = false, dayCount = 3, highlightedDay = 0L, rail, dayWidth, periodHeight)
        }
    }
}
