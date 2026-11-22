package io.github.nakanomk.stacklife.courses

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.i18n.uiText
import java.time.LocalDate
import java.time.ZoneId

/** The selected school's bell times, applied on the actual date of the occurrence. */
interface CourseCalendar {
    val zone: ZoneId
    fun periods(term: TermSpec, date: LocalDate): List<PeriodTime>
    fun scheduleDescription(term: TermSpec): String
}

/** A provider owns its acquisition, raw format, correction UI and calendar. Only reviewed meetings leave it. */
internal interface SchoolImportProvider {
    val id: String
    @get:StringRes val label: Int
    @get:StringRes val help: Int
    val calendar: CourseCalendar
    @Composable fun Import(model: WorkspaceViewModel, onBack: () -> Unit, onImported: (String) -> Unit)
}

internal object SchoolImports {
    val default: SchoolImportProvider = HustCourseImporter
    val providers = listOf(default)
    val calendars = providers.associate { it.id to it.calendar }
    fun find(id: String) = providers.firstOrNull { it.id == id }
}

@Composable
internal fun sourceLabel(source: String): String = when (source) {
    MANUAL_SOURCE -> stringResource(R.string.courses_manual_source)
    else -> SchoolImports.find(source)?.let { stringResource(it.label) } ?: source
}

internal fun calendarFor(source: String): CourseCalendar? = if (source == MANUAL_SOURCE) SchoolImports.default.calendar else SchoolImports.calendars[source]

internal fun TermSpec.calendarSummary(calendar: CourseCalendar?): String =
    uiText({ "Week one: $firstMonday · $weeks weeks" }, { "首周 $firstMonday · $weeks 周" }) +
        (calendar?.let { " · ${it.scheduleDescription(this)}" } ?: "")
