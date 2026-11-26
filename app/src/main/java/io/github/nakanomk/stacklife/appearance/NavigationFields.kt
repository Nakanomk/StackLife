package io.github.nakanomk.stacklife.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.planner.Appearance
import io.github.nakanomk.stacklife.planner.Destination
import sh.calvin.reorderable.ReorderableListItemScope

// BiliPai 5893c2c, BottomBarSettingsScreen.kt: move stable identities in the full order;
// switching visibility does not discard their positions. A drag applies only when it settles.
internal fun Appearance.moveTab(from: Int, to: Int): Appearance = copy(order = order.toMutableList().apply { add(to, removeAt(from)) })

@Composable
internal fun ReorderableListItemScope.NavigationField(dest: Destination, index: Int, draft: Appearance, enabled: Boolean,
    dragging: Boolean, update: (Appearance) -> Unit) {
    val shown = dest in draft.visible
    val canToggle = enabled && (!shown || draft.visible.size > 1)
    var menu by rememberSaveable { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val handle = Modifier.longPressDraggableHandle(enabled = enabled, interactionSource = interactions)
    val lift = if (dragging) Modifier.shadow(4.dp, MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).testTag("dragging:${dest.key}") else Modifier
    Row(Modifier.fillMaxWidth().then(lift), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).testTag("visibility:${dest.key}").toggleable(shown, enabled = canToggle, role = Role.Switch) {
            val visible = if (it) draft.visible + dest else draft.visible - dest
            update(draft.withVisible(visible))
        }.heightIn(min = 52.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(dest.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = shown, onCheckedChange = null, enabled = canToggle)
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled, interactionSource = interactions,
                modifier = handle.size(48.dp).testTag("move:${dest.key}")) {
                Icon(painterResource(R.drawable.ic_drag_handle), stringResource(R.string.appearance_move_tab, dest.label))
            }
            PlannerDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.ui_move_tab_up, dest.label)) }, enabled = index > 0,
                    leadingIcon = { Icon(painterResource(R.drawable.ic_expand_less), null) },
                    onClick = { menu = false; update(draft.moveTab(index, index - 1)) })
                DropdownMenuItem(text = { Text(stringResource(R.string.ui_move_tab_down, dest.label)) }, enabled = index < draft.order.lastIndex,
                    leadingIcon = { Icon(painterResource(R.drawable.ic_expand_more), null) },
                    onClick = { menu = false; update(draft.moveTab(index, index + 1)) })
            }
        }
    }
}
