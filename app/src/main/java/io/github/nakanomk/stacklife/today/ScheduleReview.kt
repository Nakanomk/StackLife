package io.github.nakanomk.stacklife.today

import android.database.sqlite.SQLiteException
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.reminders.ReminderRuleLabel

/** Preview the actual batch (including in-batch identities), not a rule detached from its new schedule. */
@Composable
internal fun ScheduleReview(model: WorkspaceViewModel, commands: List<PlannerCommand>, revision: Long): Boolean =
    if (commands.isEmpty()) false else key(commands, revision) {
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var changes by remember { mutableStateOf(emptyList<PlannerChange>()) }
    val stale = stringResource(R.string.slot_stale)
    val failed = stringResource(R.string.ui_read_failed)
    LaunchedEffect(model) {
        try {
            when (val result = model.planner.preview(commands)) {
                is PreviewResult.Rejected -> error = result.message
                is PreviewResult.Ready -> try {
                    if (result.baseRevision == revision) { changes = result.changes; ready = true } else error = stale
                } finally { model.discardProposal(result.proposalId) }
            }
        } catch (_: SQLiteException) { error = failed }
    }
    if (!ready) Text(error ?: stringResource(R.string.today_checking_slot),
        Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
    for (change in changes) when (val after = change.after) {
        is ReminderSnapshot -> for (rule in after.effective) if (rule.id in after.expiredRuleIds) {
            Text(ReminderRuleLabel(rule.time), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.reminder_expired), style = MaterialTheme.typography.bodySmall)
        }
        is SeriesSnapshot -> if (after.explicitExceptions > 0) Text(stringResource(R.string.slot_kept_exceptions, after.explicitExceptions))
        is ScheduleSnapshot -> if (after.overlaps.isNotEmpty()) Text(stringResource(R.string.slot_overlap_notice, after.overlaps.size))
        else -> Unit
    }
    ready
}
