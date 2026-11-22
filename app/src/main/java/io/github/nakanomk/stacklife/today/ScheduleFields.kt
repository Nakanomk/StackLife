package io.github.nakanomk.stacklife.today

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog
import io.github.nakanomk.stacklife.workspace.PreferenceRow
import java.time.ZoneId

internal val scheduleZoneIds: Set<String> = ZoneId.getAvailableZoneIds()

@Composable
internal fun ScheduleZoneField(zoneId: String?, allowDevice: Boolean, enabled: Boolean, set: (String?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.slot_zone)
    PreferenceRow(label, zoneId ?: stringResource(if (allowDevice) R.string.slot_follow_zone else R.string.slot_pick_zone), Modifier.testTag("slot:zone"), enabled) { open = true }
    if (open) {
        var search by rememberSaveable { mutableStateOf("") }
        val zones = remember { scheduleZoneIds.sorted() }
        val filtered = remember(search) { if (search.isBlank()) zones else zones.filter { it.contains(search.trim(), ignoreCase = true) } }
        PlannerAlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
            Column {
                OutlinedTextField(search, { search = it }, singleLine = true, label = { Text(stringResource(R.string.slot_search_zone)) })
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    if (allowDevice) item { PreferenceRow(stringResource(R.string.slot_follow_zone)) { set(null); open = false } }
                    items(filtered, key = { it }) { id -> PreferenceRow(id) { set(id); open = false } }
                }
            }
        }, confirmButton = { TextButton({ open = false }) { Text(stringResource(R.string.today_cancel)) } })
    }
}
