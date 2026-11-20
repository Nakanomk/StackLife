package io.github.nakanomk.stacklife.planner

import kotlinx.serialization.Serializable

@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK }
@Serializable enum class PaletteMode { DYNAMIC, SEED }
@Serializable enum class NavStyle { FLOATING, DOCKED }
@Serializable enum class Destination(val key: String, val label: String) {
    TODAY("today", "今天"), WORKSPACE("workspace", "工作台"), GRAPHS("graphs", "流程图"), COURSES("courses", "课表")
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
    val glass: Boolean = false,
    val navGlass: Boolean = true,
    val assistantGlass: Boolean = true,
    val reduceMotion: Boolean = false,
    val reduceTransparency: Boolean = false,
    val highContrast: Boolean = false,
    val haptics: Boolean = true,
) {
    init {
        require(seed.length == 7 && seed.first() == '#' && seed.drop(1).all { it in '0'..'9' || it in 'A'..'F' }) { "种子色须为 #RRGGBB。" }
        require(order.size == Destination.entries.size && Destination.entries.all(order::contains)) { "导航必须恰好包含全部四个目的地，且不能重复。" }
    }
}
