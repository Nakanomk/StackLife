package io.github.nakanomk.stacklife.today

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId

internal data class DeviceDay(val date: LocalDate, val zone: ZoneId)

private fun currentDay(): DeviceDay {
    val zone = ZoneId.systemDefault()
    return DeviceDay(LocalDate.now(zone), zone)
}

@Composable
internal fun deviceDay(): DeviceDay {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(currentDay(), context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val changes = Channel<Unit>(Channel.CONFLATED)
            val clock = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { changes.trySend(Unit) }
            }
            context.registerReceiver(clock, IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED)
            })
            try {
                while (isActive) {
                    val now = Instant.now()
                    val zone = ZoneId.systemDefault()
                    val date = now.atZone(zone).toLocalDate()
                    value = DeviceDay(date, zone)
                    val midnight = date.plusDays(1).atStartOfDay(zone).toInstant()
                    withTimeoutOrNull(Duration.between(now, midnight).toMillis().coerceAtLeast(1L)) { changes.receive() }
                }
            } finally { context.unregisterReceiver(clock); changes.close() }
        }
    }.value
}
