package io.github.nakanomk.stacklife

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import io.github.nakanomk.stacklife.appearance.CapsuleScene
import io.github.nakanomk.stacklife.appearance.StackLifeSurface
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val fabSize = 56.dp
private val fabMargin = 16.dp
internal val taskFabClearance = fabSize + fabMargin

@Composable
internal fun BoxScope.AddTaskFab(onAdd: () -> Unit, bottomInset: Dp = 0.dp) {
    val colors = MaterialTheme.colorScheme
    StackLifeSurface(CapsuleScene.CHROME,
        Modifier.align(Alignment.BottomEnd).padding(end = fabMargin, bottom = bottomInset + fabMargin).size(fabSize),
        solid = colors.primaryContainer, elevation = 3.dp, tint = colors.primaryContainer) {
        IconButton(onClick = onAdd, modifier = Modifier.size(fabSize).testTag("add-task")) {
            Icon(painterResource(R.drawable.ic_add), "新增任务", tint = colors.onPrimaryContainer)
        }
    }
}
