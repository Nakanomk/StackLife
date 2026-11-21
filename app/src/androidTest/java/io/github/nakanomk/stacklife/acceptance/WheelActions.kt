package io.github.nakanomk.stacklife.acceptance

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import java.time.LocalDate

/** Use the accessible range on the actual wheel, not text injection into the framework's internal EditText. */
internal fun SemanticsNodeInteractionsProvider.adjustWheel(tag: String, value: Int) {
    val wheel = onNodeWithTag(tag)
    if (wheel.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current != value.toFloat()) {
        wheel.performSemanticsAction(SemanticsActions.SetProgress) { it(value.toFloat()) }
    }
    check(wheel.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current == value.toFloat())
}

internal fun SemanticsNodeInteractionsProvider.chooseNumber(tag: String, value: Int) {
    onNodeWithTag(tag).performScrollTo().performClick()
    adjustWheel("wheel-number", value)
    onNodeWithTag("wheel-confirm").performClick()
}

internal fun SemanticsNodeInteractionsProvider.chooseDate(tag: String, date: LocalDate) {
    onNodeWithTag(tag).performScrollTo().performClick()
    adjustWheel("wheel-year", date.year)
    adjustWheel("wheel-month", date.monthValue)
    adjustWheel("wheel-day", date.dayOfMonth)
    onNodeWithTag("wheel-confirm").performClick()
}

internal fun SemanticsNodeInteractionsProvider.chooseClock(tag: String, hour: Int, minute: Int) {
    onNodeWithTag(tag).performScrollTo().performClick()
    val clock12 = onAllNodesWithTag("wheel-period").fetchSemanticsNodes().isNotEmpty()
    if (clock12) adjustWheel("wheel-period", hour / 12)
    adjustWheel("wheel-hour", if (clock12) (hour + 11) % 12 + 1 else hour)
    adjustWheel("wheel-minute", minute)
    onNodeWithTag("wheel-confirm").performClick()
}

internal fun SemanticsNodeInteractionsProvider.chooseDuration(tag: String, minutes: Int) {
    onNodeWithTag(tag).performScrollTo().performClick()
    adjustWheel("wheel-hours", minutes / 60)
    adjustWheel("wheel-minute", minutes % 60)
    onNodeWithTag("wheel-confirm").performClick()
}
