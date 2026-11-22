package io.github.nakanomk.stacklife.today

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.ReadPanel
import io.github.nakanomk.stacklife.ReadState
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.notes.NoteField
import io.github.nakanomk.stacklife.notes.rememberNoteLink
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.reminders.ReminderRuleLabel
import io.github.nakanomk.stacklife.reminders.ReminderRuleFields
import io.github.nakanomk.stacklife.workspace.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class SlotEditorKey(val id: String, val title: String, val dateEpochDay: Long, val zoneId: String,
    val minutes: Int, val revision: Long, val occurrence: OccurrenceKey.Schedule? = null, val role: RoutineKind? = null) : NavKey {
    val origin get() = "slot:" + (occurrence?.takeIf { it.originalDay != null }?.storageKey() ?: id)
}

private val endFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm XXX", java.util.Locale.ROOT)

@Composable
internal fun SlotEditor(entry: SlotEditorKey, model: WorkspaceViewModel, onBack: () -> Unit, onSaved: (OccurrenceKey.Schedule?, Instant?) -> Unit) {
    val schedules by remember(entry.id, model) { model.read<ScheduleView>(PlannerQuery.Schedule(entry.id)) }
        .collectAsStateWithLifecycle(initialValue = ReadState.Loading)
    ReadPanel(schedules) { current ->
        val summary = remember(entry) { current.summary }
        val key = entry.occurrence
        if (key != null) {
            val items by remember(entry, model) { model.read<OccurrenceView>(PlannerQuery.Occurrence(key, ZoneId.of(entry.zoneId))) }
                .collectAsStateWithLifecycle(initialValue = ReadState.Loading)
            ReadPanel(items) { view ->
                val item = remember(entry) { view.occurrence }
                if (item != null) ScheduleForm(entry, summary, item, item.notes, model, onBack, onSaved)
                else MissingDetail(entry.title, stringResource(R.string.slot_missing), onBack)
            }
        } else if (entry.role != null) ScheduleForm(entry, summary, null, "", model, onBack, onSaved)
        else {
            val state by model.workspace.collectAsStateWithLifecycle()
            ReadPanel(state) { workspace ->
                val task = remember(entry) { workspace.queue.find { it.id == entry.id } }
                if (task != null) ScheduleForm(entry, summary, null, task.notes, model, onBack, onSaved)
                else MissingDetail(entry.title, stringResource(R.string.slot_missing), onBack)
            }
        }
    }
}

@Composable
private fun ScheduleForm(entry: SlotEditorKey, summary: ScheduleSummary?, item: AgendaOccurrence?, originalNotes: String,
    model: WorkspaceViewModel, onBack: () -> Unit, onSaved: (OccurrenceKey.Schedule?, Instant?) -> Unit) {
    val pattern = summary?.pattern
    val daily = pattern as? SchedulePattern.Daily
    val originalDay = entry.occurrence?.originalDay
    val segment = daily?.let { it.segmentFor(originalDay ?: entry.dateEpochDay) ?: it.segments.last() }
    var scope by rememberSaveable { mutableStateOf(if (originalDay == null) EditScope.ALL else EditScope.THIS) }
    var repeating by rememberSaveable { mutableStateOf(daily != null || entry.role != null) }
    val exact = !repeating || scope == EditScope.THIS
    val existing = item?.let { ExactInterval(it.startMillis, it.endMillis) } ?: (pattern as? SchedulePattern.Once)?.interval
    val deviceZone = remember(entry.zoneId) { ZoneId.of(entry.zoneId) }
    var zoneId by rememberSaveable(scope) { mutableStateOf(if (entry.role != null) segment?.zone else segment?.zone ?: deviceZone.id.takeIf { it in scheduleZoneIds }) }
    val zone = (if (exact) deviceZone else zoneId?.let(ZoneId::of) ?: deviceZone)
    val initial = remember(scope, exact) {
        when {
            exact && existing != null -> Instant.ofEpochMilli(existing.startMillis).atZone(zone).toLocalDateTime()
            !exact && segment != null -> LocalDate.ofEpochDay(when {
                scope == EditScope.FOLLOWING -> requireNotNull(originalDay)
                summary?.enabled == false -> entry.dateEpochDay
                else -> daily!!.segments.first().fromDay
            }).atTime(LocalTime.ofSecondOfDay(segment.minute * 60L))
            existing != null -> Instant.ofEpochMilli(existing.startMillis).atZone(zone).toLocalDateTime()
            else -> LocalDate.ofEpochDay(entry.dateEpochDay).atTime(if (entry.role == RoutineKind.WAKE) 7 else if (entry.role == RoutineKind.SLEEP) 23 else 9, 0)
        }
    }
    var epochDay by rememberSaveable(scope) { mutableLongStateOf(initial.toLocalDate().toEpochDay()) }
    var minute by rememberSaveable(scope) { mutableIntStateOf(initial.hour * 60 + initial.minute) }
    val initialMinutes = if (!exact && segment != null) segment.minutes else entry.minutes
    var minutes by rememberSaveable(scope) { mutableIntStateOf(initialMinutes) }
    var durationChanged by rememberSaveable(scope) { mutableStateOf(false) }
    var endDay by rememberSaveable(scope) { mutableStateOf(segment?.endDay) }
    var chooseStart by rememberSaveable { mutableStateOf(false) }; var chooseEnd by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.ofEpochDay(epochDay)
    val time = LocalTime.ofSecondOfDay(minute * 60L)
    val local = if (date == initial.toLocalDate() && minute == initial.hour * 60 + initial.minute) initial else date.atTime(time)
    var offsetId by rememberSaveable(scope) { mutableStateOf(existing?.let { Instant.ofEpochMilli(it.startMillis).atZone(zone).offset.id }) }
    val offsets = remember(local, zone) { zone.rules.getValidOffsets(local) }
    val offset = offsets.find { it.id == offsetId } ?: offsets.firstOrNull()
    val missingZone = !exact && entry.role == null && zoneId == null
    val outsideWheel = existing != null && existing.endMillis - existing.startMillis > Int.MAX_VALUE * MINUTE_MS && !durationChanged
    val segmentDraft = if (!exact && !missingZone && !outsideWheel && (endDay == null || endDay!! >= epochDay)) DailySegment(epochDay, minute, zoneId, minutes, endDay) else null
    val duration = if (exact && !durationChanged && existing != null) existing.endMillis - existing.startMillis else minutes * MINUTE_MS
    val seriesDate = if (scope == EditScope.THIS) originalDay else (originalDay ?: epochDay).coerceIn(epochDay, maxOf(epochDay, endDay ?: Long.MAX_VALUE))
    val actual = if (exact) offset?.let { local.toInstant(it).toEpochMilli() }?.let { ExactInterval(it, it + duration) }
        else segmentDraft?.intervalOn(seriesDate ?: epochDay, deviceZone)
    val key = OccurrenceKey.Schedule(entry.id, originalDay)
    val command = when {
        !exact && segmentDraft == null -> null
        exact && actual == null -> null
        scope == EditScope.THIS -> PlannerCommand.SetOccurrenceOverride(key, scope, interval = actual, expectedRevision = entry.revision)
        daily != null && originalDay != null && summary?.enabled == true && segmentDraft != null -> PlannerCommand.SetOccurrenceOverride(
            key, scope, segment = segmentDraft, expectedRevision = entry.revision)
        segmentDraft != null -> PlannerCommand.SetSchedule(entry.id, SchedulePattern.Daily(listOf(segmentDraft)), expectedRevision = entry.revision)
        actual != null -> PlannerCommand.SetSchedule(entry.id, SchedulePattern.Once(actual), expectedRevision = entry.revision)
        else -> null
    }
    val changed = when {
        summary?.enabled != true -> true
        exact -> (scope != EditScope.THIS && pattern !is SchedulePattern.Once) || actual != existing
        segmentDraft == null || segment == null -> true
        else -> segmentDraft.minute != segment.minute || segmentDraft.zone != segment.zone || segmentDraft.minutes != segment.minutes ||
            segmentDraft.endDay != segment.endDay || scope == EditScope.ALL && segmentDraft.fromDay != daily?.segments?.first()?.fromDay
    }
    val subject = if (exact) ReminderSubject.Occurrence(OccurrenceKey.Schedule(entry.id, if (scope == EditScope.THIS) originalDay else null))
        else ReminderSubject.Schedule(entry.id)
    val note = rememberNoteLink(originalNotes)
    var completed by rememberSaveable { mutableStateOf(item?.completed == true) }
    val completion = item?.takeIf { it.completed != completed && (!repeating || originalDay != null) }?.let {
        PlannerCommand.SetOccurrenceCompletion(it.key, completed, entry.revision)
    }
    val notesEnabled = entry.role == null && item?.owner != Owner.DONE
    val abandon = { note.release(); onBack() }
    var receiptDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var receiptStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val submission = rememberSubmission(model, onError = { error = it }) { result ->
        if (result == SaveOutcome.APPLIED) { note.release(); onSaved(receiptStart?.let { OccurrenceKey.Schedule(entry.id, receiptDay) }, receiptStart?.let(Instant::ofEpochMilli)) }
        else if (result == SaveOutcome.INTERRUPTED) abandon()
    }
    val editable = !submission.busy
    val leave = { if (editable) abandon() }
    BackHandler(enabled = submission.busy || note.ownsFile || note.opening, onBack = leave)
    EditorPage(stringResource(if (summary?.enabled == true) R.string.today_adjust_slot else R.string.today_schedule), leave,
        if (entry.role == null) AssistantPage.Task(entry.id) else AssistantPage.Settings, backEnabled = editable) { top, bottom ->
        Column(Modifier.fillMaxSize().padding(top = top, bottom = bottom).verticalScroll(rememberScrollState())
            .padding(dockListPadding(0.dp, 0.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            if (item != null && (!repeating || originalDay != null)) {
                val label = stringResource(if (item.daily) R.string.slot_this_completed else R.string.today_done)
                Row(Modifier.fillMaxWidth().toggleable(completed, editable, Role.Checkbox) { completed = it }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically) { Checkbox(completed, null); Text(label) }
            }
            if (originalDay != null) Choice(stringResource(R.string.slot_scope), EditScope.entries, scope, { stringResource(when (it) {
                EditScope.THIS -> R.string.slot_this; EditScope.FOLLOWING -> R.string.slot_following; EditScope.ALL -> R.string.slot_all
            }) }, Modifier.testTag("slot:scope")) { if (editable) scope = it }
            else if (daily == null && entry.role == null) Choice(stringResource(R.string.slot_repeat), listOf(false, true), repeating,
                { stringResource(if (it) R.string.slot_daily else R.string.slot_once) }, Modifier.testTag("slot:repeat")) { if (editable) repeating = it }
            if (exact) Text(stringResource(R.string.today_zone, zone.id), style = MaterialTheme.typography.bodySmall)
            else ScheduleZoneField(zoneId, entry.role != null, editable) { zoneId = it }
            PreferenceRow(stringResource(R.string.today_start_date, date), modifier = Modifier.testTag("slot:date"),
                enabled = editable && scope != EditScope.FOLLOWING) { chooseStart = true }
            ClockWheelField(time, editable, Modifier.testTag("slot:time")) { minute = it.hour * 60 + it.minute }
            if (exact && offsets.size > 1) {
                SectionTitle(stringResource(R.string.today_choose_repeated_offset))
                Column(Modifier.selectableGroup()) { for (o in offsets) Row(Modifier.fillMaxWidth()
                    .selectable(o == offset, editable, Role.RadioButton) { offsetId = o.id }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected = o == offset, onClick = null); Text("UTC${o.id}")
                } }
            }
            Choice(stringResource(R.string.slot_duration_type), listOf(false, true), minutes == 0,
                { stringResource(if (it) R.string.slot_point else R.string.slot_span) }, Modifier.testTag("slot:length")) {
                if (editable && it != (minutes == 0)) { minutes = if (it) 0 else entry.minutes.takeIf { m -> m > 0 } ?: 30; durationChanged = true }
            }
            if (minutes != 0) DurationWheelField(stringResource(R.string.today_duration_minutes), minutes, editable, Modifier.testTag("slot:duration")) {
                minutes = it; durationChanged = true
            }
            if (outsideWheel && existing != null) Hint(elapsedLabel(existing.endMillis - existing.startMillis))
            if (!exact) {
                PreferenceRow(stringResource(R.string.slot_end_date), endDay?.let { LocalDate.ofEpochDay(it).toString() } ?: stringResource(R.string.slot_unbounded),
                    Modifier.testTag("slot:until"), editable) { chooseEnd = true }
                if (endDay != null) TextButton(onClick = { endDay = null }, enabled = editable) { Text(stringResource(R.string.slot_unbounded)) }
            }
            if (segmentDraft != null && actual == null) Hint(stringResource(R.string.today_skipped_day))
            if (actual != null && !actual.point) Text(stringResource(R.string.today_end_time, endFormat.format(Instant.ofEpochMilli(actual.endMillis).atZone(zone))))
            val validation = when {
                missingZone -> stringResource(R.string.slot_pick_zone)
                !exact && outsideWheel -> stringResource(R.string.slot_choose_shorter)
                !exact && segmentDraft == null -> stringResource(R.string.slot_last_invalid)
                exact && offset == null -> stringResource(R.string.today_skipped_time)
                else -> null
            }
            if (entry.role == null) {
                SectionTitle(stringResource(R.string.ui_notes))
                if (repeating) Hint(stringResource(R.string.slot_series_notes))
                NoteField(note, editable && notesEnabled, "slot:notes", full = true)
            }
            (validation ?: error)?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
            key(subject) {
                val rules by remember(model, subject) { model.read<ReminderView>(PlannerQuery.Reminders(subject)) }
                    .collectAsStateWithLifecycle(initialValue = ReadState.Loading)
                ReadPanel(rules) { view ->
                    var policyJson by rememberSaveable { mutableStateOf(Json.encodeToString(view.policy)) }
                    val policy = remember(policyJson) { Json.decodeFromString<ReminderPolicy>(policyJson) }
                    if (!exact) Hint(stringResource(R.string.slot_shared_reminders))
                    ReminderRuleFields(subject, policy) { if (editable) policyJson = Json.encodeToString(it) }
                    if (policy.inherit && subject is ReminderSubject.Occurrence) {
                        val parent by remember(model, subject) { model.read<ReminderView>(PlannerQuery.Reminders(ReminderSubject.Schedule(entry.id))) }
                            .collectAsStateWithLifecycle(initialValue = ReadState.Loading)
                        ReadPanel(parent) { base -> for (rule in base.effective) Text(ReminderRuleLabel(rule.time), style = MaterialTheme.typography.bodyMedium) }
                    }
                    val notesChanged = notesEnabled && note.source != null && note.source != originalNotes
                    val ruleChanged = policy != view.policy
                    val changes = remember(command, changed, completion, notesChanged, note.source, ruleChanged, policy) {
                        buildList {
                            if (completion?.completed == false) add(completion)
                            if (command != null && changed) add(command)
                            if (notesChanged) add(PlannerCommand.TaskEdit(entry.id, notes = note.source))
                            if (ruleChanged) add(PlannerCommand.SetReminders(subject, policy, entry.revision))
                            if (completion?.completed == true) add(completion)
                        }
                    }
                    val valid = ScheduleReview(model, changes, entry.revision)
                    Actions {
                        Primary(stringResource(R.string.today_save_slot), editable && command != null && validation == null &&
                            valid && !note.failed && note.source != null) {
                            if (command != null) {
                                receiptDay = if (!repeating) null else seriesDate
                                receiptStart = actual?.startMillis?.takeUnless { entry.role != null && entry.occurrence == null }; error = null
                                submission.submit { model.startReviewed(changes, entry.revision) }
                            }
                        }
                        if (summary?.enabled == true) Secondary(stringResource(when {
                            daily == null -> R.string.today_unschedule; scope == EditScope.THIS -> R.string.slot_cancel_this
                            scope == EditScope.FOLLOWING -> R.string.slot_cancel_following; else -> R.string.slot_disable_series
                        }), editable) {
                            receiptStart = null; error = null
                            val clear = if (originalDay != null) PlannerCommand.SetOccurrenceOverride(key, scope, expectedRevision = entry.revision)
                                else PlannerCommand.SetSchedule(entry.id, null, expectedRevision = entry.revision)
                            submission.submit { model.startReviewed(listOf(clear), entry.revision) }
                        }
                    }
                }
            }
        }
    }
    if (chooseStart) DateWheelDialog(date, { epochDay = it.toEpochDay(); chooseStart = false }, { chooseStart = false })
    if (chooseEnd) DateWheelDialog(LocalDate.ofEpochDay(endDay ?: epochDay), { endDay = it.toEpochDay(); chooseEnd = false }, { chooseEnd = false })
}
