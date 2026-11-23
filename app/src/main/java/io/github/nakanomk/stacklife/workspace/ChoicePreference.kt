package io.github.nakanomk.stacklife.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.PlannerAlertDialog

@Composable
internal fun <T> Choice(title: String, options: List<T>, selected: T, label: @Composable (T) -> String,
    modifier: Modifier = Modifier, leadingIcon: Int? = null, help: String? = null, change: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    PreferenceRow(title, label(selected), modifier, leadingIcon = leadingIcon, help = help) { open = true }
    if (open) PlannerAlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, text = {
        Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
            for (option in options) Row(Modifier.fillMaxWidth()
                .selectable(option == selected, role = Role.RadioButton) { open = false; change(option) }
                .heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RadioButton(option == selected, onClick = null)
                Text(label(option), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.ui_cancel)) } })
}
