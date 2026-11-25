package io.github.nakanomk.stacklife.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import io.github.nakanomk.stacklife.MainActivity
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.i18n.AppLanguage
import io.github.nakanomk.stacklife.planner.OccurrenceKey
import io.github.nakanomk.stacklife.reminders.ReminderTarget
import io.github.nakanomk.stacklife.workspace.symbolResource
import io.github.nakanomk.stacklife.reminders.uri
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max

internal const val FOLLOW = Long.MIN_VALUE
internal const val PICKED_WEEK = "stacklife.week.monday"
internal const val PAGE_DATE = "stacklife.page.date"
internal const val PAGE_START = "stacklife.page.start"
internal const val CONTROL = "io.github.nakanomk.stacklife.widget.CONTROL"
internal const val REFRESH = "io.github.nakanomk.stacklife.widget.REFRESH"

internal data class WidgetSize(val width: Int, val height: Int) {
    fun grid(scale: Float) = width >= 7 * max(48f, 48f * scale) + 20 && height >= max(216f, 216f * scale)
    fun capacity(scale: Float, week: Boolean, grid: Boolean): Int {
        val header = 8 + max(48f, 25f * scale) + (if (week) max(48f, 20f * scale) else 0f) +
            (if (grid) max(48f, 6 + 34f * scale) else 0f)
        val row = if (grid) max(48f, 6 + 51f * scale) else max(48f, 6 + 40f * scale)
        return ((height - header) / row).toInt().coerceIn(1, if (grid) 8 else 20)
    }
}

internal fun widgetPage(anchor: Int, count: Int, capacity: Int): Int =
    anchor.coerceIn(0, max(0, count - 1)) / capacity

internal fun eventIntent(context: Context, target: ReminderTarget): Intent =
    Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(target.uri())
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

private fun control(context: Context, id: Int, verb: String, value: Long, date: LocalDate): PendingIntent {
    val uri = Uri.Builder().scheme("stacklife-widget").authority("control").appendPath(id.toString())
        .appendPath(verb).appendPath(value.toString()).appendPath(date.toEpochDay().toString()).build()
    return PendingIntent.getBroadcast(context, 0, Intent(context, WidgetRefreshReceiver::class.java).setAction(CONTROL).setData(uri),
        PendingIntent.FLAG_IMMUTABLE)
}

/** For S+ the host itself selects its real size. No guessing the orientation from the largest stretchable option. */
internal fun widgetViews(context: Context, id: Int, options: Bundle, snapshot: WidgetSnapshot, now: Long): RemoteViews {
    fun render(size: WidgetSize) = renderSize(context, id, options, snapshot, now, size)
    if (Build.VERSION.SDK_INT >= 31) {
        @Suppress("DEPRECATION")
        val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.distinct()
            ?.filter { it.width > 0 && it.height > 0 }
        // Some hosts advertise many fold/resize choices. Do not parcel a full grid for every candidate.
        if (sizes != null && sizes.size > 2) {
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val nearest = sizes.minBy { kotlin.math.abs(it.width - width) + kotlin.math.abs(it.height - height) }
            return render(WidgetSize(nearest.width.toInt(), nearest.height.toInt()))
        }
        if (!sizes.isNullOrEmpty()) return RemoteViews(sizes.associateWith {
            render(WidgetSize(it.width.toInt(), it.height.toInt()))
        })
    }
    // Legacy launchers provide only cell bounds. Use their conservative minimum rather than maximum resize size.
    return render(WidgetSize(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 230,
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 } ?: if (snapshot.week == null) 160 else 240))
}

private fun renderSize(context: Context, id: Int, options: Bundle, snapshot: WidgetSnapshot, now: Long, size: WidgetSize): RemoteViews {
    fun views(layout: Int) = RemoteViews(context.packageName, layout)
    val root = views(R.layout.widget_schedule)
    // Hosts may reapply a full update to their old hierarchy, including when the last item is removed.
    root.removeAllViews(R.id.widget_rows)
    root.setViewVisibility(R.id.widget_page, View.GONE)
    val week = snapshot.week
    val rows = snapshot.events
    val locale = AppLanguage.locale
    val scale = context.resources.configuration.fontScale
    val grid = week != null && rows.isNotEmpty() && size.grid(scale)
    val bands = if (grid) weeklyBands(rows) else emptyList()
    val clock = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", locale).withZone(snapshot.zone)
    val date = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
    val day = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEMd"), locale)
    val header = if (week == null) "${context.getString(R.string.widget_today)} · ${day.format(snapshot.date)}" else {
        val range = "${date.format(snapshot.date)}–${date.format(snapshot.date.plusDays(6))}"
        week.number?.let { context.getString(R.string.widget_week_label, it, range) } ?: range
    }
    root.setTextViewText(R.id.widget_title, header)
    root.setViewVisibility(R.id.widget_week_bar, if (week != null) View.VISIBLE else View.GONE)
    if (week != null) {
        fun arrow(view: Int, monday: LocalDate?) {
            root.setBoolean(view, "setEnabled", monday != null)
            root.setInt(view, "setImageAlpha", if (monday == null) 72 else 255)
            if (monday != null) root.setOnClickPendingIntent(view, control(context, id, "week", monday.toEpochDay(), snapshot.date))
        }
        arrow(R.id.widget_back, week.previous)
        arrow(R.id.widget_forward, week.next)
        root.setContentDescription(R.id.widget_back, context.getString(R.string.widget_previous_week))
        root.setContentDescription(R.id.widget_forward, context.getString(R.string.widget_next_week))
        root.setTextViewText(R.id.widget_current, context.getString(R.string.widget_this_week))
        root.setOnClickPendingIntent(R.id.widget_current, control(context, id, "week", FOLLOW, snapshot.date))
    }
    val count = if (grid) bands.size else rows.size
    root.setViewVisibility(R.id.widget_message, if (count == 0) View.VISIBLE else View.GONE)
    if (count == 0) {
        val message = when {
            week == null -> R.string.widget_day_empty
            !week.hasTerms -> R.string.widget_no_term
            week.number == null -> R.string.widget_outside_term
            else -> R.string.widget_week_empty
        }
        root.setTextViewText(R.id.widget_message, context.getString(message))
        return root
    }
    val capacity = size.capacity(scale, week != null, grid)
    val manual = options.getInt(PAGE_START, -1).takeIf { it >= 0 && options.getLong(PAGE_DATE, FOLLOW) == snapshot.date.toEpochDay() }
    val anchor = manual ?: if (week == null) rows.indexOfFirst { !it.done && (it.end > now || it.target.startMillis > now) }
        .takeIf { it >= 0 } ?: rows.lastIndex else 0
    val page = widgetPage(anchor, count, capacity)
    val pages = 1 + (count - 1) / capacity
    if (pages > 1) {
        root.setViewVisibility(R.id.widget_page, View.VISIBLE)
        root.setTextViewText(R.id.widget_page, context.getString(R.string.widget_page, page + 1, pages))
        root.setContentDescription(R.id.widget_page, context.getString(R.string.widget_next_page, page + 1, pages))
        root.setOnClickPendingIntent(R.id.widget_page, control(context, id, "page", (((page + 1) % pages) * capacity).toLong(), snapshot.date))
    }
    fun event(view: RemoteViews, viewId: Int, item: WidgetEvent, compact: Boolean) {
        val start = clock.format(Instant.ofEpochMilli(item.target.startMillis))
        val end = clock.format(Instant.ofEpochMilli(item.end))
        val time = if (item.target.startMillis == item.end) start else "$start–$end"
        val whenText = if (week == null) time else "${day.format(snapshot.date.plusDays(item.weekday - 1L))} $time"
        val state = when {
            item.done -> context.getString(R.string.widget_done)
            week == null && item.ongoing(now) -> context.getString(R.string.widget_now)
            else -> null
        }
        // Only the launcher excerpt crosses Binder; the detail route retains the full task title.
        val excerpt = if (item.title.length <= 256) item.title else item.title.take(255) + "…"
        val title = if (item.room.isBlank()) excerpt else "$excerpt · ${item.room}"
        val course = item.target.key is OccurrenceKey.Course
        val kind = context.getString(if (course) R.string.widget_class else R.string.widget_event)
        if (compact) {
            view.setTextViewText(viewId, "$start\n$title")
        } else {
            view.setTextViewText(R.id.widget_event_title, title)
            view.setTextViewText(R.id.widget_event_time, if (state == null) whenText else "$whenText · $state")
            view.setImageViewResource(R.id.widget_event_icon, item.taskIcon?.let(::symbolResource)
                ?: if (course) R.drawable.ic_calendar_view_week else R.drawable.ic_schedule)
        }
        view.setContentDescription(viewId, listOfNotNull(kind, title, whenText, state).joinToString(", "))
        view.setOnClickPendingIntent(viewId, PendingIntent.getActivity(context, 0, eventIntent(context, item.target), PendingIntent.FLAG_IMMUTABLE))
    }
    val range = page * capacity until minOf((page + 1) * capacity, count)
    if (grid) {
        fun band(make: (Int, RemoteViews) -> Unit) {
            val band = views(R.layout.widget_band)
            repeat(7) { i ->
                val cell = views(R.layout.widget_cell)
                make(i, cell)
                band.addView(R.id.widget_cells, cell)
            }
            root.addView(R.id.widget_rows, band)
        }
        band { i, cell ->
            val d = snapshot.date.plusDays(i.toLong())
            cell.setTextViewText(R.id.widget_cell, "${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)}\n${d.dayOfMonth}")
            cell.setContentDescription(R.id.widget_cell, day.format(d))
        }
        for (row in range) band { i, cell -> bands[row][i]?.let { event(cell, R.id.widget_cell, it, true) } }
    } else for (row in range) {
        val view = views(R.layout.widget_event)
        event(view, R.id.widget_item, rows[row], false)
        root.addView(R.id.widget_rows, view)
    }
    return root
}
