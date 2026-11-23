package io.github.nakanomk.stacklife.courses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.SegmentTrack
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.i18n.AppLanguage
import io.github.nakanomk.stacklife.appearance.DateWheelField
import io.github.nakanomk.stacklife.appearance.NumberWheelField
import io.github.nakanomk.stacklife.workspace.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal data class TermForm(val id: String = "", val monday: LocalDate? = null, val weeks: Int? = 20,
    val initial: BellSchedule = BellSchedule.SUMMER, val switchDate: LocalDate? = null, val expanded: Boolean = false) {
    fun term(): TermSpec {
        val start = monday ?: throw IllegalArgumentException(uiText("Choose the first Monday of the term.", "请选择学期首周周一。"))
        val term = TermSpec(id, start, weeks ?: throw IllegalArgumentException(uiText("Choose the number of weeks in the term.", "请选择学期总周数。")),
            initial, switchDate)
        term.problem()?.let { throw IllegalArgumentException(it) }
        return term
    }
}

internal fun TermSpec.dateRangeLabel(): String {
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(AppLanguage.locale)
    return "${firstMonday.format(format)} – ${firstMonday.plusWeeks(weeks.toLong()).minusDays(1).format(format)}"
}

private val uuidId = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
internal fun TermSpec.hasLegacyId(): Boolean = id.length == 36 && uuidId.matches(id)
internal fun TermSpec.displayLabel(): String = if (hasLegacyId()) dateRangeLabel() else id

@Composable
internal fun TermChoice(label: String, selected: Boolean, tag: String, enabled: Boolean = true,
    source: String? = null, dates: String? = null, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose)
        .heightIn(min = 48.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            dates?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
            source?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
internal fun TermFields(form: TermForm, enabled: Boolean, frosted: Boolean = false, change: (TermForm) -> Unit) =
    // Keep unconfirmed dates and calendar choices in the editor, not an Activity Bundle.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
    val campusToday = remember { LocalDate.now(campusZone) }
    val thisMonday = remember(campusToday) { campusToday.minusDays((campusToday.dayOfWeek.value - 1).toLong()) }
    FormGroup(frosted = frosted) {
        DateWheelField(stringResource(R.string.courses_first_monday), form.monday, thisMonday, enabled, Modifier.testTag("termMonday")) {
            change(form.copy(monday = it))
        }
        NumberWheelField(stringResource(R.string.courses_week_count), form.weeks, 1..53, enabled, Modifier.testTag("termWeeks"),
            initial = 20) { change(form.copy(weeks = it)) }
        TextButton({ change(form.copy(expanded = !form.expanded)) }, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).testTag("termAdvanced")) {
            Text(stringResource(if (form.expanded) R.string.manual_hide_bell else R.string.manual_show_bell))
        }
    }
    if (!form.expanded) return@CompositionLocalProvider
    Text(stringResource(R.string.courses_initial_bell))
    val initial = rememberUpdatedState(form.initial.ordinal.toFloat())
    ControlGroup(frosted = frosted) {
        SegmentTrack(initial, BellSchedule.entries.size, MaterialTheme.colorScheme.secondaryContainer,
            { change(form.copy(initial = BellSchedule.entries[it])) }, enabled = enabled) { highlighted ->
            Row(Modifier.selectableGroup()) {
                BellSchedule.entries.forEach { bell ->
                    Box(Modifier.clip(CircleShape).selectable(form.initial == bell, enabled = enabled, role = Role.RadioButton) {
                        change(form.copy(initial = bell))
                    }.heightIn(min = 48.dp).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center) { Text(if (bell == BellSchedule.SUMMER) stringResource(R.string.courses_summer) else stringResource(R.string.courses_winter),
                            color = if (enabled && highlighted == bell.ordinal) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                }
            }
        }
    }
    val switchFields: @Composable () -> Unit = {
        DateWheelField(stringResource(R.string.courses_change_date), form.switchDate, form.monday ?: campusToday, enabled,
            Modifier.testTag("termSwitchDate"), onClear = { change(form.copy(switchDate = null)) }) { change(form.copy(switchDate = it)) }
        Text(stringResource(R.string.courses_change_date_hint))
    }
    if (frosted) FormGroup(frosted = true) { switchFields() } else switchFields()
}

@Composable
internal fun ImportField(label: String, value: String, tag: String, enabled: Boolean = true, change: (String) -> Unit) {
    // Don't let framework text-editing state save even a course name to an Activity Bundle.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        TextField(value = value, onValueChange = change, enabled = enabled, colors = lineFieldColors(), label = { Text(label) },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag(tag))
    }
}

