package io.github.nakanomk.stacklife.planner

import io.github.nakanomk.stacklife.i18n.uiText
import kotlinx.serialization.Serializable

@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK }
@Serializable enum class PaletteMode { DYNAMIC, SEED }
@Serializable enum class NavStyle { FLOATING, DOCKED }
@Serializable enum class Destination(val key: String, private val en: String, private val zh: String) {
    TODAY("today", "Today", "今天"), WORKSPACE("workspace", "Workspace", "工作台"), GRAPHS("graphs", "Graphs", "流程图"), COURSES("courses", "Timetable", "课表");

    val label: String get() = uiText(en, zh)
}

@Serializable
data class Appearance(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val palette: PaletteMode = PaletteMode.DYNAMIC,
    val seed: String = "#435D92",
    val compact: Boolean = false,
    val navStyle: NavStyle = NavStyle.FLOATING,
    val order: List<Destination> = Destination.entries,
    val start: Destination = Destination.TODAY,
    val showDuration: Boolean = true,
    val showNotes: Boolean = true,
    val glass: Boolean = true,
    val navGlass: Boolean = true,
    val assistantGlass: Boolean = true,
    val reduceMotion: Boolean = false,
    val reduceTransparency: Boolean = false,
    val highContrast: Boolean = false,
    val haptics: Boolean = true,
    val courseTextOutline: Boolean = false,
    val floatingDayTray: Boolean = true,
) {
    init {
        require(seed.length == 7 && seed.first() == '#' && seed.drop(1).all { it in '0'..'9' || it in 'A'..'F' }) { uiText("Use #RRGGBB for the seed color.", "种子色须为 #RRGGBB。") }
        require(order.size == Destination.entries.size && Destination.entries.all(order::contains)) { uiText("Navigation must include all four destinations exactly once.", "导航必须恰好包含全部四个目的地，且不能重复。") }
    }
}
