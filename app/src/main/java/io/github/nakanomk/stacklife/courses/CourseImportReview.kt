package io.github.nakanomk.stacklife.courses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.GlassBacking
import io.github.nakanomk.stacklife.assistant.AssistantComposer
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.assistant.LocalAssistant
import io.github.nakanomk.stacklife.assistant.ChangePreview
import io.github.nakanomk.stacklife.planner.PreviewResult
import io.github.nakanomk.stacklife.workspace.*
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun ReviewedImport(model: WorkspaceViewModel, draft: ReviewedTimetable, proposal: PreviewResult.Ready,
    busy: Boolean, top: Dp, inset: Dp, onImported: (String) -> Unit, onEdit: () -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val assistant = LocalAssistant.current
    var staged by remember(proposal.proposalId, assistant) { mutableStateOf<String?>(null) }
    if (assistant != null) {
        DisposableEffect(proposal.proposalId, assistant) {
            onDispose {
                staged?.let { id ->
                    model.planner.discardCourseImport(id)
                    if ((assistant.state.value.page as? AssistantPage.CourseDraft)?.draftId == id) assistant.close()
                }
            }
        }
        val chat by assistant.state.collectAsState()
        var imported by remember { mutableStateOf(false) }
        LaunchedEffect(chat.importedDraft, chat.page) {
            if (staged != null && chat.importedDraft == staged) imported = true
            if (imported && chat.page == null) onImported(draft.term.id)
        }
    }
    ImportDiff(draft, proposal, busy, top, inset, ask = {
        val id = staged ?: model.planner.stageCourseImport(draft.term, draft.meetings, draft.source).also { staged = it }
        AssistantPage.CourseDraft(id, draft.term.id)
    }, onEdit, onCancel, onConfirm)
}
internal data class ReviewedGroup(val pattern: CoursePattern, val time: PeriodTime, val weeks: List<Int>, val firstDate: LocalDate)

private data class MeetingSlot(val pattern: CoursePattern, val time: PeriodTime)

private fun Map<MeetingSlot, List<Int>>.ordered(term: TermSpec): List<ReviewedGroup> = map { (slot, weeks) ->
    val orderedWeeks = weeks.sorted()
    ReviewedGroup(slot.pattern, slot.time, orderedWeeks, term.firstMonday.plusWeeks(orderedWeeks.first() - 1L).plusDays(slot.pattern.day - 1L))
}.sortedWith(compareBy({ it.firstDate }, { it.pattern.firstPeriod }, { it.pattern.lastPeriod }, { it.pattern.title }, { it.pattern.room }))

internal fun ImportDraft.groups(): List<ReviewedGroup> = valid.groupBy({ entry ->
    val date = term.firstMonday.plusWeeks(entry.week - 1L).plusDays(entry.day - 1L)
    val bells = provider.calendar.periods(term, date)
    MeetingSlot(CoursePattern(entry.title, entry.room, entry.day, entry.first, entry.last),
        PeriodTime(bells[entry.first - 1].start, bells[entry.last - 1].end))
}, { it.week }).ordered(term)

@Composable
internal fun ReviewCourseCard(group: ReviewedGroup) {
    val course = group.pattern
    FormGroup {
        Text(course.title, style = MaterialTheme.typography.bodyLarge)
        if (course.room.isNotBlank()) Hint(stringResource(R.string.courses_room, course.room))
        Text(weekday(course.day), style = MaterialTheme.typography.bodyMedium)
        Hint(stringResource(R.string.import_period_range, course.firstPeriod, course.lastPeriod))
        Hint("${group.time.start}–${group.time.end}")
        Hint(stringResource(R.string.courses_weeks_list, group.weeks.joinToString(stringResource(R.string.courses_list_separator))))
    }
}

internal fun androidx.compose.foundation.lazy.LazyListScope.reviewGroups(groups: List<ReviewedGroup>) {
    for ((date, entries) in groups.groupBy { it.firstDate }) {
        item { SectionTitle(stringResource(R.string.import_starts_on, date.toString())) }
        items(entries) { ReviewCourseCard(it) }
    }
}

@Composable
private fun ImportDiff(draft: ReviewedTimetable, proposal: PreviewResult.Ready, busy: Boolean, top: Dp, inset: Dp,
    ask: () -> AssistantPage.CourseDraft, onEdit: () -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val meetings = draft.meetings
    val groups = remember(draft) {
        val zone = calendarFor(draft.source)?.zone ?: ZoneId.systemDefault()
        meetings.groupBy({ meeting ->
            fun clock(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
            MeetingSlot(meeting.pattern(), PeriodTime(clock(meeting.startMillis), clock(meeting.endMillis)))
        }, { it.week }).ordered(draft.term)
    }
    ImportPane(showComposer = !busy, composer = { AssistantComposer(ask) }) { bottom ->
        // The header and composer receive input above this plane. Keep them outside the
        // scroll viewport, so bringing either footer action into view makes it tappable.
        LazyColumn(Modifier.fillMaxSize().padding(top = top, bottom = bottom).testTag("courseImportDiff"),
            contentPadding = importListPadding(0.dp, 0.dp, inset), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SectionTitle(stringResource(R.string.courses_confirm_import_diff))
                Text(draft.term.displayLabel(), style = MaterialTheme.typography.bodyLarge)
                if (!draft.term.hasLegacyId()) Hint(draft.term.dateRangeLabel())
                Hint(stringResource(R.string.import_source_scope, sourceLabel(draft.source)))
                Hint(stringResource(R.string.import_meeting_count, meetings.size))
            }
            item { Text(stringResource(R.string.courses_database_diff), style = MaterialTheme.typography.titleMedium)
                if (proposal.changes.isEmpty()) Text(stringResource(R.string.courses_unchanged)) }
            items(proposal.changes, key = { it.entityId }) { ChangePreview(it) }
            reviewGroups(groups)
            item { Secondary(stringResource(R.string.courses_back_to_review), !busy, onEdit) }
            item {
                ImportActionRow(ImportActions.DIFF, !busy, !busy, Modifier.testTag("cancelCourseImportBottom"),
                    Modifier.testTag("confirmCourseImport"), onCancel, onConfirm)
            }
        }
    }
}

@Composable
internal fun ImportPane(showComposer: Boolean, composer: @Composable () -> Unit, content: @Composable (bottom: Dp) -> Unit) {
    val hasComposer = showComposer && LocalAssistant.current != null
    val density = LocalDensity.current
    var height by remember(hasComposer) { mutableIntStateOf(0) }
    GlassBacking(Modifier.fillMaxSize(), background = { content(with(density) { height.toDp() }) }) {
        if (hasComposer) Box(Modifier.align(Alignment.BottomCenter).onSizeChanged { height = it.height }.padding(horizontal = 16.dp, vertical = 4.dp)) {
            composer()
        }
    }
}
