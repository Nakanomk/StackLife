package io.github.nakanomk.stacklife.countdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
internal data class CountdownSeed(val title: String, val day: Long)

internal val LocalNewCountdown = staticCompositionLocalOf<((CountdownSeed) -> Unit)?> { null }

@Composable
internal fun AddOccurrenceCountdown(title: String, startMillis: Long, displayZone: ZoneId, enabled: Boolean = true) {
    val add = LocalNewCountdown.current ?: return
    CountdownLaunchButton("countdown:new", stringResource(R.string.countdown_add_occurrence), "occurrence-add-countdown", enabled) {
        add(CountdownSeed(title, Instant.ofEpochMilli(startMillis).atZone(displayZone).toLocalDate().toEpochDay()))
    }
}
