package io.github.nakanomk.stacklife.graphs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.CapsuleScene
import io.github.nakanomk.stacklife.appearance.StackLifeSurface

// Keep the launch/return anchor on the visible pill, not its larger touch target.
@Composable
internal fun GoalReviewAction(label: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean,
    prominent: Boolean = false, anchor: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val fill = if (prominent && enabled) colors.primaryContainer else colors.surfaceContainerHigh
    val ink = if (!enabled) colors.onSurface.copy(alpha = .38f) else if (prominent) colors.onPrimaryContainer else colors.onSurface
    val active = pressed || focused
    val color = if (active) ink.copy(alpha = .12f).compositeOver(fill) else fill
    Box(modifier.clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .sizeIn(minWidth = 48.dp, minHeight = 48.dp).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        StackLifeSurface(CapsuleScene.ACTION, anchor, color, tint = if (prominent || active) color else Color.Unspecified,
            interactionSource = source.takeIf { enabled }) {
            Box(Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                Text(label, color = ink, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private val goalChoiceShape = RoundedCornerShape(16.dp)

@Composable
internal fun GoalChoice(id: String, title: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val active = pressed || focused
    Box(Modifier.fillMaxWidth().testTag("goal-choice:$id").selectable(selected, interactionSource = source,
        indication = null, enabled = enabled, role = Role.RadioButton, onClick = onSelect), contentAlignment = Alignment.Center) {
        if (selected || active) {
            val base = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh
            val fill = if (active) colors.onSurface.copy(alpha = .12f).compositeOver(base) else base
            StackLifeSurface(CapsuleScene.INSET, Modifier.matchParentSize().padding(vertical = 4.dp), fill,
                shape = goalChoiceShape, tint = fill.copy(alpha = if (active) .72f else .48f)) {}
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            if (selected) Icon(painterResource(R.drawable.ic_goal_check), null, Modifier.size(20.dp).testTag("goal-check:$id"), tint = colors.primary)
            else Spacer(Modifier.size(20.dp))
        }
    }
}
