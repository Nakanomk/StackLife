package io.github.nakanomk.stacklife

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import io.github.nakanomk.stacklife.appearance.CapsuleScene
import io.github.nakanomk.stacklife.appearance.LocalCapsuleBackdrop
import io.github.nakanomk.stacklife.appearance.StackLifeSurface
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.transitions.routeOrigin

internal val taskFabSize = 56.dp
internal val taskFabMargin = 16.dp
internal val taskFabClearance = taskFabSize + taskFabMargin

@Composable
internal fun BoxScope.AddTaskFab(onAdd: () -> Unit, bottomInset: Dp = 0.dp) {
    TaskAddButton(onAdd, Modifier.align(Alignment.BottomEnd).padding(end = taskFabMargin, bottom = bottomInset + taskFabMargin))
}

@Composable
internal fun TaskAddButton(onAdd: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val ink = if (LocalCapsuleBackdrop.current == null) colors.onPrimaryContainer else colors.primary
    StackLifeSurface(CapsuleScene.ACTION, modifier.size(taskFabSize).routeOrigin("add-task", CircleShape),
        solid = colors.primaryContainer, elevation = 3.dp, interactionSource = interaction) {
        IconButton(onClick = onAdd, modifier = Modifier.size(taskFabSize).testTag("add-task"), interactionSource = interaction) {
            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.ui_add_task), tint = ink)
        }
    }
}
