package io.github.nakanomk.stacklife.countdown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.BackgroundPhoto
import io.github.nakanomk.stacklife.appearance.CapsuleScene
import io.github.nakanomk.stacklife.appearance.StackLifeSurface
import io.github.nakanomk.stacklife.appearance.wallpaperUsesSolidSurface
import io.github.nakanomk.stacklife.planner.Countdown
import io.github.nakanomk.stacklife.planner.DayDirection
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** TimeTrace NormalEventCard / PinnedEventCard (MIT). The image ratio and 100dp row are minima, never text bounds. */
@Composable
internal fun CountdownCard(value: Countdown, today: LocalDate, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    CountdownFace(value, today, modifier, large = value.pinned, onClick = onClick)
}

@Composable
internal fun CountdownFace(value: Countdown, today: LocalDate, modifier: Modifier, large: Boolean, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(24.dp)
    val colors = MaterialTheme.colorScheme
    val photo = value.backgroundId?.takeUnless { wallpaperUsesSolidSurface() }
    val amount = remember(value.targetDay, value.mode, today) { value.amount(today) }
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(value.targetDay, locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        .format(LocalDate.ofEpochDay(value.targetDay)) }
    val ink = if (photo != null) Color.White else colors.onSurface
    val support = if (photo != null) Color.White else colors.onSurfaceVariant
    StackLifeSurface(CapsuleScene.FROST, modifier.fillMaxWidth().clip(shape).then(if (onClick == null)
        Modifier.semantics(mergeDescendants = true) {} else Modifier.clickable(role = Role.Button, onClick = onClick)),
        colors.surfaceContainerLow, shape = shape) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val minHeight = if (large) maxWidth * (9f / 16f) else 100.dp
            val counterWidth = maxWidth * .43f
            val stacked = maxWidth / LocalDensity.current.fontScale < 280.dp || (!large && value.title.length > 15)
            if (photo != null) BackgroundPhoto(photo, Modifier.matchParentSize(), value.maskOpacity)
            val label: @Composable (Modifier) -> Unit = { labelModifier ->
                Column(labelModifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (value.pinned) Text(stringResource(R.string.countdown_pinned), color = support, style = MaterialTheme.typography.labelMedium)
                    Text(value.title, color = ink, style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                    Text(date, color = support, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val counter: @Composable () -> Unit = {
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(when (amount.direction) {
                        DayDirection.TODAY -> R.string.countdown_today
                        DayDirection.UNTIL -> R.string.countdown_until
                        DayDirection.PAST -> R.string.countdown_past
                        DayDirection.SINCE -> R.string.countdown_since
                    }), color = support, style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), itemVerticalAlignment = Alignment.Bottom) {
                        Text(amount.days.toString(), Modifier.alignByBaseline(), color = if (photo != null) ink else colors.primary,
                            style = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLarge)
                        Text(pluralStringResource(R.plurals.countdown_day_unit, amount.days.toInt()), Modifier.alignByBaseline(),
                            color = support, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            val contents = Modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = 24.dp, vertical = 16.dp)
            if (stacked) Column(contents, verticalArrangement = Arrangement.spacedBy(8.dp, if (large) Alignment.Bottom else Alignment.CenterVertically)) {
                label(Modifier.fillMaxWidth()); Box(Modifier.align(Alignment.End)) { counter() }
            } else Row(contents, horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = if (large) Alignment.Bottom else Alignment.CenterVertically) {
                label(Modifier.weight(1f)); Box(Modifier.widthIn(max = counterWidth)) { counter() }
            }
        }
    }
}
