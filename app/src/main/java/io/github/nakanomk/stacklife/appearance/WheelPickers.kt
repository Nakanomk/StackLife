package io.github.nakanomk.stacklife.appearance

import android.os.Build
import android.text.format.DateFormat
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.workspace.PreferenceRow
import java.text.DateFormatSymbols
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** One labelled adjustable accessibility target per native wheel; never expose its internal EditText. */
@Composable
private fun Wheel(label: String, tag: String, width: Dp, value: Int, range: IntRange, wrap: Boolean = false,
    names: Array<String>? = null, onChange: (Int) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val ink = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val size = with(LocalDensity.current) { 18.sp.toPx() }
    // Keep the native view's MeasureSpec exact even when wide digits wrap this column onto its own row.
    val height = with(LocalDensity.current) { (24.sp.toDp() + 8.dp).coerceAtLeast(48.dp) * 3 }
    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth().testTag(tag).clearAndSetSemantics {
            contentDescription = label
            stateDescription = names?.get(value - range.first) ?: value.toString()
            progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat(),
                (range.last - range.first - 1).coerceAtLeast(0))
            setProgress { target ->
                val next = target.roundToInt().coerceIn(range)
                if (next != value) change(next)
                next != value
            }
        }) { key(dark, names) {
            AndroidView(factory = { context ->
                val theme = if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar
                NumberPicker(ContextThemeWrapper(context, theme)).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    minValue = range.first
                    maxValue = range.last
                    displayedValues = names
                    setOnValueChangedListener { _, _, next -> change(next) }
                }
            }, update = { picker ->
                if (picker.minValue != range.first) picker.minValue = range.first
                if (picker.maxValue != range.last) picker.maxValue = range.last
                if (picker.wrapSelectorWheel != wrap) picker.wrapSelectorWheel = wrap
                if (picker.value != value) picker.value = value
                if (Build.VERSION.SDK_INT >= 29) {
                    if (picker.textColor != ink.toArgb()) picker.textColor = ink.toArgb()
                    if (picker.textSize != size) picker.textSize = size
                }
            }, modifier = Modifier.fillMaxWidth().height(height))
        } }
    }
}

@Composable
private fun WheelDialog(title: String, columns: Int, close: () -> Unit, confirm: () -> Unit,
    valid: Boolean = true, clear: (() -> Unit)? = null, clearLabel: Int = R.string.wheel_clear_date, wheels: @Composable (Dp) -> Unit) {
    PlannerAlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Wrap columns onto a scrollable second line when scaled digits would not fit side by side.
            val minimum = with(LocalDensity.current) { 62.sp.toDp() }.coerceAtLeast(64.dp)
            val width = (maxWidth / columns - 1.dp).coerceIn(minOf(minimum, maxWidth), maxWidth)
            FlowRow(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) { wheels(width) }
        }
    }, dismissButton = {
        if (clear != null) TextButton(onClick = clear, modifier = Modifier.testTag("wheel-clear")) { Text(stringResource(clearLabel)) }
        TextButton(onClick = close, modifier = Modifier.testTag("wheel-cancel")) { Text(stringResource(R.string.ui_cancel)) }
    }, confirmButton = {
        TextButton(onClick = confirm, enabled = valid, modifier = Modifier.testTag("wheel-confirm")) { Text(stringResource(R.string.ui_ok)) }
    })
}

@Composable
internal fun DateWheelDialog(initial: LocalDate, onSelect: (LocalDate) -> Unit, onDismiss: () -> Unit,
    title: String = stringResource(R.string.wheel_choose_date), onClear: (() -> Unit)? = null) {
    var epochDay by rememberSaveable { mutableLongStateOf(initial.toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val order = remember(context, locale) { DateFormat.getDateFormatOrder(context) }
    WheelDialog(title, 3, onDismiss, { onSelect(date) }, clear = onClear) { width ->
        for (unit in order) key(unit) {
            when (unit) {
                'y' -> Wheel(stringResource(R.string.wheel_year), "wheel-year", width, date.year, 1..9999) { epochDay = date.withYear(it).toEpochDay() }
                'M' -> Wheel(stringResource(R.string.wheel_month), "wheel-month", width, date.monthValue, 1..12, true) { epochDay = date.withMonth(it).toEpochDay() }
                'd' -> Wheel(stringResource(R.string.wheel_day), "wheel-day", width, date.dayOfMonth, 1..date.lengthOfMonth(), true) { epochDay = date.withDayOfMonth(it).toEpochDay() }
                else -> Unit
            }
        }
    }
}

/** Hide any neighbouring text field's keyboard before opening a wheel on a form. */
@Composable
private fun PickerField(label: String, display: String, enabled: Boolean, modifier: Modifier,
    dialog: @Composable (close: () -> Unit) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val ime = LocalSoftwareKeyboardController.current
    PreferenceRow(label, display, modifier, enabled) { focus.clearFocus(); ime?.hide(); open = true }
    if (open) dialog { open = false }
}

@Composable
internal fun NumberWheelField(label: String, value: Int?, range: IntRange, enabled: Boolean, modifier: Modifier,
    initial: Int = range.first, names: Array<String>? = null, onChange: (Int) -> Unit) {
    val display = value?.let { names?.get(it - range.first) ?: it.toString() } ?: stringResource(R.string.wheel_unset)
    PickerField(label, display, enabled, modifier) { close ->
        var draft by rememberSaveable { mutableIntStateOf(value ?: initial) }
        WheelDialog(label, 1, close, { onChange(draft); close() }) { width ->
            Wheel(label, "wheel-number", width, draft, range, names = names) { draft = it }
        }
    }
}

@Composable
internal fun PeriodWheelField(value: IntRange?, modifier: Modifier, onChange: (IntRange) -> Unit) {
    val label = stringResource(R.string.courses_periods)
    val display = when {
        value == null -> stringResource(R.string.wheel_unset)
        value.first == value.last -> value.first.toString()
        else -> "${value.first}–${value.last}"
    }
    PickerField(label, display, true, modifier) { close ->
        var start by rememberSaveable { mutableIntStateOf(value?.first ?: 1) }
        var end by rememberSaveable { mutableIntStateOf(value?.last ?: 1) }
        WheelDialog(label, 2, close, { onChange(start..end); close() }) { width ->
            Wheel(stringResource(R.string.courses_first_period), "wheel-first", width, start, 1..12) {
                start = it
                end = maxOf(end, it)
            }
            Wheel(stringResource(R.string.courses_last_period), "wheel-last", width, end, start..12) { end = it }
        }
    }
}

@Composable
internal fun DateWheelField(label: String, date: LocalDate?, initial: LocalDate, enabled: Boolean, modifier: Modifier,
    onClear: (() -> Unit)? = null, onChange: (LocalDate) -> Unit) {
    PickerField(label, date?.toString() ?: stringResource(R.string.wheel_unset), enabled, modifier) { close ->
        DateWheelDialog(date ?: initial, { onChange(it); close() }, close, label,
            onClear = onClear?.takeIf { date != null }?.let { clear -> { clear(); close() } })
    }
}

@Composable
internal fun ClockWheelField(time: LocalTime?, enabled: Boolean, modifier: Modifier, label: String = stringResource(R.string.today_start_clock),
    initial: LocalTime = LocalTime.MIDNIGHT, onClear: (() -> Unit)? = null, onChange: (LocalTime) -> Unit) {
    val use24 = DateFormat.is24HourFormat(LocalContext.current)
    val locale = LocalConfiguration.current.locales[0]
    val pattern = remember(locale, use24) { DateFormat.getBestDateTimePattern(locale, if (use24) "Hm" else "hm") }
    val format = remember(pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale) }
    PickerField(label, time?.format(format) ?: stringResource(R.string.wheel_unset), enabled, modifier) { close ->
        val current = time ?: initial
        var hour by rememberSaveable { mutableIntStateOf(current.hour) }
        var minute by rememberSaveable { mutableIntStateOf(current.minute) }
        val periods = remember(locale) { DateFormatSymbols.getInstance(locale).amPmStrings }
        val period: @Composable (Dp) -> Unit = { width ->
            Wheel(stringResource(R.string.wheel_period), "wheel-period", width, hour / 12, 0..1, names = periods) { hour = hour % 12 + it * 12 }
        }
        WheelDialog(label, if (use24) 2 else 3, close, { onChange(LocalTime.of(hour, minute)); close() },
            clear = onClear?.takeIf { time != null }?.let { { it(); close() } }, clearLabel = R.string.agenda_clear_time) { width ->
            val periodFirst = !use24 && pattern.trimStart().startsWith('a')
            if (periodFirst) period(width)
            if (use24) Wheel(stringResource(R.string.wheel_hour), "wheel-hour", width, hour, 0..23, true) { hour = it }
            else Wheel(stringResource(R.string.wheel_hour), "wheel-hour", width, (hour + 11) % 12 + 1, 1..12, true) { hour = hour / 12 * 12 + it % 12 }
            Wheel(stringResource(R.string.ui_minutes), "wheel-minute", width, minute, 0..59, true) { minute = it }
            if (!use24 && !periodFirst) period(width)
        }
    }
}

@Composable
internal fun DurationWheelField(label: String, minutes: Int, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    PickerField(label, stringResource(R.string.ui_minutes_value, minutes), enabled, modifier) { close ->
        var hours by rememberSaveable { mutableIntStateOf(minutes / 60) }
        var remainder by rememberSaveable { mutableIntStateOf(minutes % 60) }
        // Preserve the full nonnegative Int domain, including point and multi-day estimates.
        val limit = if (hours == Int.MAX_VALUE / 60) Int.MAX_VALUE % 60 else 59
        WheelDialog(label, 2, close, { onChange(hours * 60 + remainder); close() }) { width ->
            Wheel(stringResource(R.string.wheel_hours), "wheel-hours", width, hours, 0..Int.MAX_VALUE / 60) {
                hours = it
                if (it == Int.MAX_VALUE / 60) remainder = minOf(remainder, Int.MAX_VALUE % 60)
            }
            Wheel(stringResource(R.string.ui_minutes), "wheel-minute", width, remainder, 0..limit, true) { remainder = it }
        }
    }
}
