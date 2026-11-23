package io.github.nakanomk.stacklife.courses

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.workspace.dockListPadding
import io.github.nakanomk.stacklife.workspace.ActionPill

internal enum class ImportActions(val first: Int, val shortFirst: Int, val second: Int, val shortSecond: Int) {
    ENTRIES(R.string.courses_preview_import, R.string.import_short_review, R.string.import_choose_timetable, R.string.import_short_timetable),
    DIFF(R.string.courses_cancel_import, R.string.courses_cancel, R.string.courses_confirm_import, R.string.import_short_confirm),
}

/** Preserve the normal list inset unless it would truncate even the agreed short labels. */
@Composable
internal fun importActionInset(width: Dp, actions: ImportActions): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val labelWidth = maxOf(measurer.measure(stringResource(actions.shortFirst), style, softWrap = false).size.width,
        measurer.measure(stringResource(actions.shortSecond), style, softWrap = false).size.width)
    return with(LocalDensity.current) {
        val free = width.roundToPx() - 8.dp.roundToPx() - 2 * (labelWidth + 2 * 16.dp.roundToPx())
        (free / 2).toDp().coerceIn(0.dp, 16.dp)
    }
}

@Composable
internal fun importListPadding(top: Dp, bottom: Dp, inset: Dp): PaddingValues {
    val original = dockListPadding(top, bottom)
    return PaddingValues(start = inset, end = inset, top = original.calculateTopPadding(), bottom = original.calculateBottomPadding())
}

@Composable
internal fun ImportActionRow(actions: ImportActions, firstEnabled: Boolean, secondEnabled: Boolean,
    firstModifier: Modifier, secondModifier: Modifier, onFirst: () -> Unit, onSecond: () -> Unit) {
    val first = stringResource(actions.first)
    val second = stringResource(actions.second)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Each ActionPill uses 16dp on both sides of its label; keep both slots equal even when only one is long.
        val available = with(LocalDensity.current) { (constraints.maxWidth - 8.dp.roundToPx()) / 2 - 2 * 16.dp.roundToPx() }
        val compact = measurer.measure(first, style, softWrap = false).size.width > available ||
            measurer.measure(second, style, softWrap = false).size.width > available
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fun Modifier.slot(full: String) = weight(1f).heightIn(min = 48.dp).then(if (compact) Modifier.semantics { contentDescription = full } else Modifier)
            ActionPill(if (compact) stringResource(actions.shortFirst) else first, onFirst, firstModifier.slot(first),
                enabled = firstEnabled, prominent = actions == ImportActions.ENTRIES, maxLines = 1)
            ActionPill(if (compact) stringResource(actions.shortSecond) else second, onSecond, secondModifier.slot(second),
                enabled = secondEnabled, prominent = actions == ImportActions.DIFF, maxLines = 1)
        }
    }
}
