package io.github.nakanomk.stacklife.planner

import io.github.nakanomk.stacklife.i18n.uiText
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlinx.serialization.Serializable

/** TimeTrace DateEvent + TimeUtils.getDayCount (MIT). Store the day directly rather than a UTC midnight. */
@Serializable enum class CountdownMode { COUNT_DOWN, ACCUMULATE }
@Serializable data class Countdown(val id: String, val title: String, val targetDay: Long, val mode: CountdownMode,
    val pinned: Boolean, val position: Int, val backgroundId: String? = null, val maskOpacity: Float = .3f) {
    fun amount(today: LocalDate): DayAmount {
        val difference = ChronoUnit.DAYS.between(today, LocalDate.ofEpochDay(targetDay))
        return DayAmount(abs(difference), when {
            difference == 0L -> DayDirection.TODAY
            difference > 0L -> DayDirection.UNTIL
            mode == CountdownMode.ACCUMULATE -> DayDirection.SINCE
            else -> DayDirection.PAST
        })
    }
}

enum class DayDirection { TODAY, UNTIL, SINCE, PAST }
data class DayAmount(val days: Long, val direction: DayDirection)

data class CountdownsView(val revision: Long, val items: List<Countdown>) : QueryResult
data class CountdownView(val revision: Long, val value: Countdown?) : QueryResult
/** Null patch preserves the image; a present change with a null id explicitly removes it. */
data class BackgroundChange(val id: String?)

internal fun Countdown.problem(): String? = when {
    title.isBlank() || title.length > 256 -> uiText("Use a title of 1–256 characters.", "名称须为 1–256 个字符。")
    targetDay !in countdownDates -> uiText("Choose a date between years 1 and 9999.", "请选择 1–9999 年内的日期。")
    !maskOpacity.isFinite() || maskOpacity !in 0f..1f -> uiText("Invalid background dimming.", "背景遮罩透明度无效。")
    else -> null
}
private val countdownDates = LocalDate.of(1, 1, 1).toEpochDay()..LocalDate.of(9999, 12, 31).toEpochDay()
