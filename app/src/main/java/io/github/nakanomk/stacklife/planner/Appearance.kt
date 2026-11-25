package io.github.nakanomk.stacklife.planner

import io.github.nakanomk.stacklife.i18n.uiText
import kotlinx.serialization.Serializable

@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK }
@Serializable enum class PaletteMode { DYNAMIC, SEED }
@Serializable enum class NavStyle { FLOATING, DOCKED }
@Serializable enum class Destination(val key: String, private val en: String, private val zh: String) {
    TODAY("today", "Today", "今天"), WORKSPACE("workspace", "Workspace", "工作台"), GRAPHS("graphs", "Graphs", "流程图"),
    COURSES("courses", "Timetable", "课表"), COUNTDOWN("countdown", "Countdown", "倒数日");

    val label: String get() = uiText(en, zh)
}

internal val allDestinations = Destination.entries.toSet()
@Serializable enum class DayScrollEffect { FLAT, WHEEL }
@Serializable enum class NowLineStyle { WAVE, STRAIGHT, OFF }
internal val privatePhotoId = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
@Serializable enum class WallpaperPreset(private val en: String, private val zh: String) {
    BROWN("Cocoa plaid", "可可棕格"), PINK("Rose plaid", "玫瑰粉格"),
    BLUE("Mist blue plaid", "雾蓝格"), ORANGE("Maple plaid", "枫叶橙格");

    val label: String get() = uiText(en, zh)
}

@Serializable data class WallpaperSpec(val imageId: String? = null, val scale: Float = 1f,
    val offsetX: Float = 0f, val offsetY: Float = 0f, val tint: Float = .28f, val preset: WallpaperPreset? = null,
    val blur: Float = 0f) {
    val hasImage: Boolean get() = preset != null || imageId != null
    init {
        require(imageId == null || privatePhotoId.matches(imageId)) { uiText("Invalid private image ID.", "背景图片 ID 无效。") }
        require(imageId == null || preset == null) { uiText("Choose a built-in wallpaper or a private photo, not both.", "内置背景和自选图片不能同时使用。") }
        require(scale in 1f..3f && offsetX in -1f..1f && offsetY in -1f..1f && tint in 0f..1f && blur in 0f..1f) {
            uiText("Wallpaper crop, tint or blur is out of range.", "壁纸裁切、罩色或模糊程度超出范围。")
        }
    }
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
    val visible: Set<Destination> = allDestinations,
    val background: WallpaperSpec = WallpaperSpec(preset = WallpaperPreset.BLUE),
    val pageBackgrounds: Map<Destination, WallpaperSpec> = emptyMap(),
    val showCourseGrid: Boolean = false,
    val dayScrollEffect: DayScrollEffect = DayScrollEffect.WHEEL,
    val nowLine: NowLineStyle = NowLineStyle.WAVE,
    val nowArrow: Boolean = true,
    val nowTrail: Boolean = true,
) {
    init {
        require(seed.length == 7 && seed.first() == '#' && seed.drop(1).all { it in '0'..'9' || it in 'A'..'F' }) { uiText("Use #RRGGBB for the seed color.", "种子色须为 #RRGGBB。") }
        require(order.size == allDestinations.size && order.toSet() == allDestinations) { uiText("Include every destination exactly once.", "导航须恰好包含全部功能，不能重复。") }
        require(start in visible) { uiText("The start page must be visible.", "启动页必须是可见功能。") }
    }
    @kotlinx.serialization.Transient val visibleOrder = if (visible.size == order.size) order else order.filter(visible::contains)
    fun nearestVisible(destination: Destination, choices: Set<Destination> = visible): Destination {
        val i = order.indexOf(destination)
        return (i..order.lastIndex).firstOrNull { order[it] in choices }?.let(order::get)
            ?: order.last(choices::contains)
    }
    fun withVisible(choices: Set<Destination>) = copy(visible = choices, start = nearestVisible(start, choices))
    fun wallpaper(destination: Destination?) = pageBackgrounds[destination] ?: background
    internal fun backgroundIds() = buildSet { background.imageId?.let(::add); pageBackgrounds.values.forEach { it.imageId?.let(::add) } }
}
