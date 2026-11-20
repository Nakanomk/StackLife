package io.github.nakanomk.stacklife.today

import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.ZoneId

internal data class DeviceDay(val date: LocalDate, val zone: ZoneId)

private fun currentDay(): DeviceDay {
    val zone = ZoneId.systemDefault()
    return DeviceDay(LocalDate.now(zone), zone)
}

@Composable
internal fun deviceDay(): DeviceDay {
    val context = LocalContext.current
    var day by remember { mutableStateOf(currentDay()) }
    DisposableEffect(context) {
        val clock = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { day = currentDay() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIME_TICK)
        }
        context.registerReceiver(clock, filter)
        onDispose { context.unregisterReceiver(clock) }
    }
    return day
}

@Composable
internal fun DateChooser(initial: LocalDate, onSelect: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val select by rememberUpdatedState(onSelect)
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(context) {
        val picker = DatePickerDialog(context, { _, year, month, day -> select(LocalDate.of(year, month + 1, day)) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth)
        picker.setOnDismissListener { dismiss() }
        picker.show()
        onDispose { picker.setOnDismissListener(null); picker.dismiss() }
    }
}
