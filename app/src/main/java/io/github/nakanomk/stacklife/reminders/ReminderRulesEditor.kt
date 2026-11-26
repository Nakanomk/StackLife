package io.github.nakanomk.stacklife.reminders

import android.database.sqlite.SQLiteException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.*
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

private val unitMillis = longArrayOf(60_000, 3_600_000, 86_400_000)
private val presets = longArrayOf(0, 5, 15, 30, 60, 1440).map { ReminderWhen.BeforeStart(it * 60_000L) }

@Composable private fun heading(subject: ReminderSubject) = stringResource(when (subject) {
    ReminderSubject.CourseDefault -> R.string.reminder_course_defaults
    is ReminderSubject.Occurrence -> R.string.reminder_occurrence_rules
    is ReminderSubject.Schedule -> R.string.reminder_rules
})

@Composable internal fun ReminderRuleLabel(time: ReminderWhen): String = when (time) {
    is ReminderWhen.AtInstant -> savedTime(time.instantMillis)
    is ReminderWhen.BeforeStart -> when {
        time.leadMillis == 0L -> stringResource(R.string.reminder_start)
        time.leadMillis % unitMillis[2] == 0L -> {
            val days = time.leadMillis / unitMillis[2]
            pluralStringResource(R.plurals.reminder_days_before, days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), days)
        }
        time.leadMillis % unitMillis[1] == 0L -> stringResource(R.string.reminder_hours_before, time.leadMillis / unitMillis[1])
        time.leadMillis % unitMillis[0] == 0L -> stringResource(R.string.reminder_minutes_before, time.leadMillis / unitMillis[0])
        else -> stringResource(R.string.reminder_millis_before, time.leadMillis)
    }
}

/** Stored editor uses the same draft fields as a new schedule, but owns its own explicit save. */
@Composable
internal fun ReminderRulesEditor(model: WorkspaceViewModel, subject: ReminderSubject) = key(subject) {
    val read = if (subject == ReminderSubject.CourseDefault) model.courseReminders.collectAsStateWithLifecycle().value
        else remember(model, subject) { model.read<ReminderView>(PlannerQuery.Reminders(subject)) }
            .collectAsStateWithLifecycle(ReadState.Loading).value
    when (read) {
        ReadState.Loading -> LinearProgressIndicator()
        ReadState.ReadFailed -> Text(stringResource(R.string.reminder_read_failed))
        is ReadState.Loaded -> {
            val view = read.value
            var draft by rememberSaveable { mutableStateOf<String?>(null) }
            var revision by rememberSaveable { mutableLongStateOf(0L) }
            var error by rememberSaveable { mutableStateOf<String?>(null) }
            val submission = rememberSubmission(model, onError = { error = it }) {
                if (it == SaveOutcome.APPLIED) { draft = null; error = null }
            }
            val policy = remember(draft) { draft?.let { Json.decodeFromString<ReminderPolicy>(it) } }
            val courseDefault = subject == ReminderSubject.CourseDefault
            val start = {
                revision = view.revision; draft = Json.encodeToString(view.policy); error = null
            }
            val summary: @Composable () -> String = {
                buildList {
                    if (view.policy.inherit) add(stringResource(R.string.reminder_inherit))
                    if (view.effective.isEmpty()) add(stringResource(R.string.reminder_no_rules))
                    else for (rule in view.effective) add(ReminderRuleLabel(rule.time))
                }.joinToString(" · ")
            }
            val edit: @Composable (ReminderPolicy) -> Unit = { editing ->
                Column {
                    ReminderRuleFields(subject, editing, enabled = !submission.busy, showHeading = !courseDefault) { draft = Json.encodeToString(it) }
                    val command = remember(subject, editing, revision) { PlannerCommand.SetReminders(subject, editing, revision) }
                    var valid by remember(command, view.revision) { mutableStateOf(false) }
                    var issue by remember(command, view.revision) { mutableStateOf<String?>(null) }
                    var expired by remember(command, view.revision) { mutableStateOf(emptyList<ReminderRule>()) }
                    val readError = stringResource(R.string.reminder_read_failed)
                    LaunchedEffect(command, view.revision) {
                        if (editing != view.policy) try {
                            when (val preview = model.preview(command)) {
                                is PreviewResult.Rejected -> issue = preview.message
                                is PreviewResult.Ready -> try {
                                    val after = preview.changes.firstNotNullOfOrNull { it.after as? ReminderSnapshot }
                                    expired = after?.effective?.filter { it.id in after.expiredRuleIds }.orEmpty()
                                    valid = true
                                } finally { model.discardProposal(preview.proposalId) }
                            }
                        } catch (_: SQLiteException) { issue = readError }
                    }
                    issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    for (rule in expired) {
                        Text(ReminderRuleLabel(rule.time), style = MaterialTheme.typography.labelMedium)
                        Text(stringResource(R.string.reminder_expired), style = MaterialTheme.typography.bodySmall)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Box {
                        Row {
                            TextButton(onClick = { draft = null; error = null }, enabled = !submission.busy) { Text(stringResource(R.string.ui_cancel)) }
                            TextButton(onClick = { submission.send(command) }, enabled = valid && !submission.busy) {
                                Text(stringResource(R.string.ui_save_changes))
                            }
                        }
                        if (submission.busy) LinearProgressIndicator(Modifier.align(Alignment.BottomStart))
                    }
                }
            }
            if (courseDefault) {
                ExpandablePreferenceHeader(expanded = policy != null, onExpandedChange = { open ->
                    if (open) start() else { draft = null; error = null }
                }, enabled = !submission.busy, leading = {
                    Icon(painterResource(R.drawable.ic_calendar_view_week), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                }, title = { SectionTitle(heading(subject)) }, summary = {
                    Text(summary(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }) { if (policy != null) edit(policy) }
            } else if (policy == null) PreferenceRow(heading(subject), summary(), valueMaxLines = 2, onClick = start)
            else if (submission.busy) LinearProgressIndicator()
            else edit(policy)
        }
    }
}

/** Local-only fields: canceling the enclosing schedule editor never writes a rule. */
@Composable
internal fun ReminderRuleFields(subject: ReminderSubject, policy: ReminderPolicy, enabled: Boolean = true,
    showHeading: Boolean = true, onChange: (ReminderPolicy) -> Unit) = key(subject) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    Column {
        if (showHeading) Text(heading(subject), style = MaterialTheme.typography.titleMedium)
        if (subject is ReminderSubject.Occurrence) CheckRow(stringResource(R.string.reminder_inherit), policy.inherit, enabled = enabled) {
            editing = null; onChange(ReminderPolicy(!policy.inherit, emptyList()))
        }
        if (!policy.inherit) {
            if (policy.rules.isEmpty()) Text(stringResource(R.string.reminder_no_rules), style = MaterialTheme.typography.bodyMedium)
            for (rule in policy.rules) key(rule.id) { PreferenceRow(ReminderRuleLabel(rule.time), enabled = enabled) { editing = rule.id } }
            TextButton(onClick = { editing = UUID.randomUUID().toString() }, enabled = enabled) { Text(stringResource(R.string.reminder_add)) }
            editing?.let { id -> key(id) {
                val old = policy.rules.firstOrNull { it.id == id }
                RuleDialog(old, subject is ReminderSubject.Occurrence, policy.rules, { editing = null }, remove = {
                    onChange(policy.copy(rules = policy.rules.filterNot { it.id == id })); editing = null
                }) { time ->
                    val rule = ReminderRule(id, time)
                    onChange(policy.copy(rules = if (old == null) policy.rules + rule else policy.rules.map { if (it.id == id) rule else it }))
                    editing = null
                }
            } }
        }
    }
}

@Composable
private fun RuleDialog(old: ReminderRule?, allowAbsolute: Boolean, others: List<ReminderRule>, close: () -> Unit,
    remove: () -> Unit, save: (ReminderWhen) -> Unit) {
    val initial = old?.time ?: if (others.none { it.time == initialReminder.time }) initialReminder.time
        else presets.firstOrNull { t -> others.none { it.time == t } } ?: initialReminder.time
    val custom = presets.size; val absolute = custom + 1
    var mode by rememberSaveable { mutableIntStateOf(if (initial is ReminderWhen.AtInstant) absolute else presets.indexOfFirst { it == initial }.takeIf { it >= 0 } ?: custom) }
    val lead = (initial as? ReminderWhen.BeforeStart)?.leadMillis ?: unitMillis[0]
    var unit by rememberSaveable { mutableIntStateOf(unitMillis.indexOfLast { lead >= it && lead % it == 0L }.coerceAtLeast(0)) }
    var amount by rememberSaveable { mutableIntStateOf((lead / unitMillis[unit]).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()) }
    val zone = ZoneId.systemDefault()
    val start = remember { Instant.ofEpochMilli((initial as? ReminderWhen.AtInstant)?.instantMillis ?: System.currentTimeMillis()).atZone(zone) }
    var day by rememberSaveable { mutableLongStateOf(start.toLocalDate().coerceIn(LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31)).toEpochDay()) }
    var minute by rememberSaveable { mutableIntStateOf(start.hour * 60 + start.minute) }
    var changed by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.ofEpochDay(day); val clock = LocalTime.ofSecondOfDay(minute * 60L)
    val local = date.atTime(clock)
    val offsets = remember(local, zone) { zone.rules.getValidOffsets(local) }
    var offsetId by rememberSaveable { mutableStateOf(start.offset.id) }
    val offset = offsets.firstOrNull { it.id == offsetId } ?: offsets.firstOrNull()
    val time = if (!changed) initial else when (mode) {
        custom -> ReminderWhen.BeforeStart(amount * unitMillis[unit])
        absolute -> offset?.let { ReminderWhen.AtInstant(local.toInstant(it).toEpochMilli()) }
        else -> presets[mode]
    }
    val duplicate = others.any { it.id != old?.id && it.time == time }
    val names = arrayOf(stringResource(R.string.ui_minutes), stringResource(R.string.wheel_hours), stringResource(R.string.reminder_days))
    PlannerAlertDialog(onDismissRequest = close, title = { Text(stringResource(if (old == null) R.string.reminder_add else R.string.reminder_edit)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Choice(stringResource(R.string.reminder_when), (0..if (allowAbsolute) absolute else custom).toList(), mode, label = { index ->
                when (index) { custom -> stringResource(R.string.reminder_custom); absolute -> stringResource(R.string.reminder_absolute); else -> ReminderRuleLabel(presets[index]) }
            }) { mode = it; changed = true }
            if (mode == custom) {
                NumberWheelField(stringResource(R.string.reminder_amount), amount, 1..Int.MAX_VALUE, true, Modifier) { amount = it; changed = true }
                NumberWheelField(stringResource(R.string.reminder_unit), unit, unitMillis.indices, true, Modifier, names = names) { unit = it; changed = true }
            } else if (mode == absolute) {
                DateWheelField(stringResource(R.string.reminder_date), date, date, true, Modifier) { day = it.toEpochDay(); changed = true }
                ClockWheelField(clock, true, Modifier, label = stringResource(R.string.reminder_clock)) { minute = it.hour * 60 + it.minute; changed = true }
                if (offsets.isEmpty()) Text(stringResource(R.string.today_skipped_time), color = MaterialTheme.colorScheme.error)
                if (offsets.size > 1) Choice(stringResource(R.string.today_choose_repeated_offset), offsets, requireNotNull(offset),
                    label = { "UTC${it.id}" }) { offsetId = it.id; changed = true }
            }
            if (duplicate) Text(stringResource(R.string.reminder_duplicate), color = MaterialTheme.colorScheme.error)
            if (time is ReminderWhen.AtInstant && time.instantMillis < System.currentTimeMillis()) Text(stringResource(R.string.reminder_expired))
            if (old != null) TextButton(onClick = remove) { Text(stringResource(R.string.reminder_remove)) }
        }
    }, dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.ui_cancel)) } },
        confirmButton = { TextButton(enabled = !duplicate && time != null, onClick = { time?.let(save) }) { Text(stringResource(R.string.ui_ok)) } })
}
