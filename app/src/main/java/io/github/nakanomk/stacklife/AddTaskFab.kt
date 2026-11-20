package io.github.nakanomk.stacklife

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val fabSize = 56.dp
private val fabMargin = 16.dp
internal val taskFabClearance = fabSize + fabMargin

@Composable
internal fun BoxScope.AddTaskFab(onAdd: () -> Unit, bottomInset: Dp = 0.dp) {
    FloatingActionButton(onClick = onAdd, shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = fabMargin, bottom = bottomInset + fabMargin)
            .size(fabSize).testTag("add-task").semantics { contentDescription = "新增任务" }) {
        val ink = LocalContentColor.current
        Canvas(Modifier.size(24.dp)) {
            drawLine(ink, Offset(3.dp.toPx(), center.y), Offset(size.width - 3.dp.toPx(), center.y), 2.5.dp.toPx(), StrokeCap.Round)
            drawLine(ink, Offset(center.x, 3.dp.toPx()), Offset(center.x, size.height - 3.dp.toPx()), 2.5.dp.toPx(), StrokeCap.Round)
        }
    }
}
