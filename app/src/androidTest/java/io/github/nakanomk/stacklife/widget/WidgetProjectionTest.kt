package io.github.nakanomk.stacklife.widget

import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.MainActivity
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.acceptance.WorkspaceFixture
import io.github.nakanomk.stacklife.acceptance.exact
import io.github.nakanomk.stacklife.courses.BellSchedule
import io.github.nakanomk.stacklife.courses.TermSpec
import io.github.nakanomk.stacklife.courses.campusZone
import io.github.nakanomk.stacklife.courses.meeting
import io.github.nakanomk.stacklife.planner.OccurrenceKey
import io.github.nakanomk.stacklife.planner.PlannerCommand.CourseImport
import io.github.nakanomk.stacklife.planner.PlannerCommand.TaskAdd
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.planner.TaskInput
import io.github.nakanomk.stacklife.planner.Terms
import io.github.nakanomk.stacklife.reminders.reminderTarget
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetProjectionTest {
    @get:Rule val store = WorkspaceFixture()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val monday = LocalDate.of(2026, 9, 7)
    private val term = TermSpec("Widget sample", monday, 3, BellSchedule.SUMMER)

    @Test fun sameTitleRowsOpenTheirOwnOccurrenceAndChangeAtRealBoundaries() = runBlocking {
        val a = term.meeting(1, 1, "Review", "A", 1, 2)
        val b = term.meeting(1, 1, "Review", "B", 1, 2)
        val start = Instant.ofEpochMilli(a.startMillis)
        store.apply(CourseImport(store.api.stageCourseImport(term, listOf(a, b))), TaskAdd(TaskInput("Review", ref = "t")),
            exact("@t", start.atZone(campusZone).toOffsetDateTime(), 10))
        val tasksAndClasses = store.api.widgetDay(start.minusSeconds(5), campusZone)
        assertEquals(start.toEpochMilli(), tasksAndClasses.nextRefresh)
        assertEquals(3, tasksAndClasses.events.size)
        val targetKeys = tasksAndClasses.events.map { row ->
            val intent = eventIntent(context, row.target)
            assertEquals(MainActivity::class.java.name, intent.component!!.className)
            assertEquals(row.target, intent.reminderTarget())
            row.target.key
        }.toSet()
        assertEquals(2, targetKeys.count { it is OccurrenceKey.Course })
        assertEquals(1, targetKeys.count { it is OccurrenceKey.Schedule })
        val inside = store.api.widgetDay(start, campusZone)
        assertTrue(inside.events.all { it.ongoing(start.toEpochMilli()) })
        assertEquals(start.plusSeconds(600).toEpochMilli(), inside.nextRefresh)
        val terms = store.api.observe(PlannerQuery.Terms).first() as Terms
        val week = store.api.widgetWeek(terms, null, start, campusZone)
        assertEquals(monday.plusWeeks(1), week.week!!.next)
        val lanes = weeklyBands(week.events)
        assertEquals(2, lanes.size)
        assertEquals(targetKeys.filterIsInstance<OccurrenceKey.Course>().toSet(), lanes.map { it.first()!!.target.key }.toSet())
        inst.runOnMainSync {
            fun hostRows(fontScale: Float): LinearLayout {
                val scaled = context.createConfigurationContext(Configuration().apply { this.fontScale = fontScale })
                val opts = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 520)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 430)
                }
                val content = widgetViews(scaled, 123, opts, week, start.toEpochMilli()).apply(scaled, FrameLayout(scaled))
                return content.findViewById(R.id.widget_rows)
            }
            val grid = hostRows(1f)
            assertEquals(3, grid.childCount) // weekday strip + two overlapping lanes in the Monday column
            for (i in 1..2) {
                val cell = (grid.getChildAt(i) as LinearLayout).getChildAt(0)
                assertTrue(cell.hasOnClickListeners())
                assertTrue(cell.contentDescription.contains("Review"))
            }
            val list = hostRows(2f)
            assertEquals(2, list.childCount)
            assertTrue((0..1).all { list.getChildAt(it).findViewById<View>(R.id.widget_event_time) != null })
        }
        assertTrue(store.api.widgetWeek(terms, monday.plusWeeks(1), start, campusZone).events.isEmpty())
        val outside = store.api.widgetWeek(terms, null, start.minusSeconds(7 * 86400), campusZone).week!!
        assertNull(outside.number)
        assertEquals(monday, outside.next)
    }

    @Test fun emptyDayRefreshUsesNextLocalMidnightEvenOnShortDay() = runBlocking {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 3, 8)
        val start = date.atStartOfDay(zone).toInstant()
        assertEquals(start.plusSeconds(23 * 3600L).toEpochMilli(), store.api.widgetDay(start, zone).nextRefresh)
    }

    @Test fun resizingExposesMoreRealRowsAndEmptyUpdateRemovesOldClickTargets() = runBlocking {
        val start = monday.atTime(10, 0).atZone(campusZone)
        repeat(6) { n -> store.apply(TaskAdd(TaskInput("Reading $n", ref = "t")), exact("@t", start.plusMinutes(n.toLong()).toOffsetDateTime(), 5)) }
        val data = store.api.widgetDay(start.toInstant(), campusZone)
        fun render(height: Int, snapshot: WidgetSnapshot) = widgetViews(context, 123, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
        }, snapshot, start.toInstant().toEpochMilli())
        inst.runOnMainSync {
            val host = FrameLayout(context)
            val content = render(138, data).apply(context, host)
            val rows = content.findViewById<LinearLayout>(R.id.widget_rows)
            val small = rows.childCount
            assertTrue(small in 1..5)
            assertTrue(rows.getChildAt(0).contentDescription.contains("Reading"))
            render(720, data).reapply(context, content)
            assertEquals(6, rows.childCount)
            assertEquals(View.GONE, content.findViewById<View>(R.id.widget_page).visibility)
            render(720, data.copy(events = emptyList())).reapply(context, content)
            assertEquals(0, rows.childCount)
            assertEquals(View.VISIBLE, content.findViewById<View>(R.id.widget_message).visibility)
        }
    }
}
