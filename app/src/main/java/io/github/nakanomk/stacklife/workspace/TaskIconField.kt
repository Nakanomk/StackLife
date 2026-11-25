package io.github.nakanomk.stacklife.workspace

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.TaskIcon

@DrawableRes internal fun symbolResource(icon: TaskIcon): Int = when (icon) {
    TaskIcon.AUTO -> R.drawable.ic_schedule
    TaskIcon.TASK -> R.drawable.ic_symbol_task_alt
    TaskIcon.EVENT -> R.drawable.ic_symbol_event
    TaskIcon.PERSON -> R.drawable.ic_symbol_person
    TaskIcon.SCHOOL -> R.drawable.ic_symbol_school
}

private fun symbolLabel(icon: TaskIcon): String = when (icon) {
    TaskIcon.AUTO -> uiText("Automatic", "自动")
    TaskIcon.TASK -> uiText("Task", "任务")
    TaskIcon.EVENT -> uiText("Event", "日程")
    TaskIcon.PERSON -> uiText("Person", "人物")
    TaskIcon.SCHOOL -> uiText("Study", "学习")
}

@Composable internal fun TaskIconField(value: TaskIcon, enabled: Boolean, modifier: Modifier, change: (TaskIcon) -> Unit) {
    Choice(uiText("Event icon", "事件图标"), TaskIcon.entries, value, { symbolLabel(it) }, modifier,
        leadingIcon = symbolResource(value), enabled = enabled, optionIcon = ::symbolResource, change = change)
}

@Composable internal fun TaskIconTitle(icon: TaskIcon, title: @Composable () -> Unit) {
    if (icon == TaskIcon.AUTO) title() else Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(symbolResource(icon)), symbolLabel(icon), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
        title()
    }
}
