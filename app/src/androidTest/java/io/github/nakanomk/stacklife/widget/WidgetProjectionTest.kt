package io.github.nakanomk.stacklife.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcel
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
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
import kotlin.math.roundToInt
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

    @Test @SdkSuppress(minSdkVersion = 31)
    fun oneWeeklyUpdateResizesBetweenPagesAndGridWithTheirOwnOccurrenceClicks() = runBlocking {
        val meetings = (1..5).map { day -> term.meeting(1, day, "Lab", "R$day", 1, 2) }
        store.apply(CourseImport(store.api.stageCourseImport(term, meetings)))
        val start = Instant.ofEpochMilli(meetings.first().startMillis)
        val week = store.api.widgetWeek(store.api.observe(PlannerQuery.Terms).first() as Terms, monday, start, campusZone)
        val scaled = context.createConfigurationContext(Configuration().apply { fontScale = 1f })
        val small = SizeF(240f, 180f)
        val tall = SizeF(270f, 271f)
        val wide = SizeF(560f, 305f)
        inst.runOnMainSync {
            var clicked: IntentSender? = null
            // Observe the IntentSender actually dispatched by Android's row/cell click listener
            // without starting MainActivity against a different database than this fixture.
            val launcher = object : ContextWrapper(scaled) {
                override fun startIntentSender(intent: IntentSender, fillInIntent: Intent?, flagsMask: Int,
                    flagsValues: Int, extraFlags: Int, options: Bundle?) { clicked = intent }
            }
            fun click(view: View, day: Int) {
                val expected = eventIntent(context, week.events.single { it.weekday == day }.target)
                val pending = requireNotNull(PendingIntent.getActivity(context, 0, expected,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
                clicked = null
                assertTrue(view.performClick())
                assertEquals("Wrong occurrence for day $day", pending.intentSender, clicked)
            }
            // Exercise exact host sizes and a many-size host whose equivalent capacities must coalesce.
            for (sizes in listOf(listOf(small, tall, wide), listOf(small, tall, wide) + (1..20).map { SizeF(tall.width + it, tall.height) })) {
                val options = Bundle().apply {
                    putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, ArrayList(sizes))
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
                    putLong(PAGE_DATE, monday.toEpochDay())
                    putInt(PAGE_START, 2)
                }
                val host = AppWidgetHostView(launcher)
                val parcel = Parcel.obtain()
                try {
                    widgetViews(scaled, 123, options, week, start.toEpochMilli()).writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    host.updateAppWidget(RemoteViews.CREATOR.createFromParcel(parcel))
                } finally { parcel.recycle() }
                // One delivery above. Only real host measure/layout below: no new provider update on resize.
                fun resize(size: SizeF): LinearLayout {
                    val density = host.resources.displayMetrics.density
                    val w = (size.width * density).roundToInt()
                    val h = (size.height * density).roundToInt()
                    host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    host.layout(0, 0, w, h)
                    val rows = host.findViewById<LinearLayout>(R.id.widget_rows)
                    for (i in 0 until rows.childCount) {
                        val row = rows.getChildAt(i)
                        assertTrue("Clipped row $i at $size", row.visibility == View.VISIBLE && row.width > 0 &&
                            row.height > 0 && row.top >= 0 && row.bottom <= rows.height)
                    }
                    return rows
                }
                fun titles(rows: LinearLayout) = (0 until rows.childCount).map {
                    rows.getChildAt(it).findViewById<TextView>(R.id.widget_event_title).text.toString()
                }
                val shortPage = resize(small)
                assertEquals(listOf("Lab · R3"), titles(shortPage))
                click(shortPage.getChildAt(0), 3)
                val tallPage = resize(tall)
                assertEquals(listOf("Lab · R1", "Lab · R2", "Lab · R3"), titles(tallPage))
                click(tallPage.getChildAt(1), 2)
                val grid = resize(wide)
                assertEquals(2, grid.childCount) // weekday names + one occupied band
                val thursday = (grid.getChildAt(1) as LinearLayout).getChildAt(3) as TextView
                assertTrue(thursday.text.contains("Lab · R4"))
                assertTrue(thursday.contentDescription.contains("Lab · R4"))
                assertEquals(View.GONE, host.findViewById<View>(R.id.widget_page).visibility)
                click(thursday, 4)
                assertEquals(listOf("Lab · R3"), titles(resize(small)))
            }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun manyDistinctPageCapacitiesStayWithinTwoFullGridsOnTheWire() = runBlocking {
        // Full-length provider fields and every cell occupied; empty cells understate Binder cost.
        val meetings = (1..7).flatMap { day -> (1..8).map { period ->
            term.meeting(1, day, "$day-$period".padEnd(256, 'T'), "Room".padEnd(256, 'R'), period, period)
        } }
        store.apply(CourseImport(store.api.stageCourseImport(term, meetings)))
        val start = Instant.ofEpochMilli(meetings.first().startMillis)
        val week = store.api.widgetWeek(store.api.observe(PlannerQuery.Terms).first() as Terms, monday, start, campusZone)
        assertEquals(56, week.events.size)
        val scaled = context.createConfigurationContext(Configuration().apply { fontScale = 1f })
        fun bytes(sizes: List<SizeF>): Int {
            val options = Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, ArrayList(sizes)) }
            val parcel = Parcel.obtain()
            return try {
                widgetViews(scaled, 123, options, week, start.toEpochMilli()).writeToParcel(parcel, 0)
                parcel.dataSize()
            } finally { parcel.recycle() }
        }
        val previousBudget = bytes(listOf(SizeF(560f, 700f), SizeF(700f, 700f)))
        val many = bytes((1..20).map { SizeF(270f, 108f + 48 * it) } +
            (1..8).map { SizeF(560f, 164f + 57 * it) })
        assertTrue("Many-size update: $many bytes; two dense grids: $previousBudget", many <= previousBudget)
    }
}
