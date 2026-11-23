package io.github.nakanomk.stacklife.planner

import io.github.nakanomk.stacklife.i18n.uiText
import kotlinx.serialization.json.*

private val appearanceFormat = Json { encodeDefaults = true; ignoreUnknownKeys = true }
internal fun encodeAppearance(value: Appearance): String = appearanceFormat.encodeToString(value)
internal fun decodeAppearance(text: String): Appearance = appearanceFormat.decodeFromJsonElement(StoredAppearance, appearanceFormat.parseToJsonElement(text))

/** Decode persisted navigation before constructing the strict current model (also for historical snapshots). */
internal object StoredAppearance : JsonTransformingSerializer<Appearance>(Appearance.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val fields = element.jsonObject
        val names = allDestinations.map { it.name }
        fun known(key: String) = (fields[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(names::contains) }?.distinct()
        val savedOrder = known("order").orEmpty()
        val order = savedOrder + names.filterNot(savedOrder::contains)
        val visible = known("visible")?.takeIf { it.isNotEmpty() } ?: order
        val start = (fields["start"] as? JsonPrimitive)?.content?.takeIf(visible::contains) ?: order.first(visible::contains)
        val pages = (fields["pageBackgrounds"] as? JsonObject)?.let { JsonObject(it.filterKeys(names::contains)) }
        val filtered = fields.filterKeys { name -> Appearance.serializer().descriptor.getElementIndex(name) >= 0 }.toMutableMap()
        if (pages != null) filtered["pageBackgrounds"] = pages
        return JsonObject(filtered + mapOf(
            "order" to JsonArray(order.map(::JsonPrimitive)), "visible" to JsonArray(visible.map(::JsonPrimitive)), "start" to JsonPrimitive(start)))
    }
}

internal enum class AppearanceGroup(val key: String, val fields: Map<String, () -> String>) {
    COLOR("color", mapOf("theme" to { uiText("Theme", "明暗") }, "palette" to { uiText("Color source", "色彩来源") }, "seed" to { uiText("Seed color", "种子色") })),
    LAYOUT("layout", mapOf("compact" to { uiText("Compact spacing", "紧凑留白") }, "navStyle" to { uiText("Navigation style", "导航样式") }, "order" to { uiText("Navigation order", "导航次序") }, "start" to { uiText("Start page", "启动页") },
        "visible" to { uiText("Shown destinations", "显示的功能") }, "dayScrollEffect" to { uiText("Day scroll effect", "日程滚动效果") },
        "floatingDayTray" to { uiText("Floating queue tray", "悬浮待排队列") }, "showDuration" to { uiText("Show duration", "显示时长") }, "showNotes" to { uiText("Show notes", "显示笔记") })),
    MATERIAL("material", mapOf("glass" to { uiText("Liquid glass", "液态玻璃") }, "navGlass" to { uiText("Navigation glass", "导航玻璃") }, "assistantGlass" to { uiText("Assistant glass", "助理玻璃") },
        "background" to { uiText("Global background", "全局背景") }, "pageBackgrounds" to { uiText("Page backgrounds", "逐页背景") },
        "showCourseGrid" to { uiText("Timetable grid lines", "课表辅助线") },
        "courseTextOutline" to { uiText("Outline class text", "课程文字描边") },
        "reduceMotion" to { uiText("Reduce motion", "减弱动效") }, "reduceTransparency" to { uiText("Reduce transparency", "减少透明度") }, "highContrast" to { uiText("High contrast", "增强对比") }, "haptics" to { uiText("System haptics", "随系统触感") }));

    fun project(appearance: Appearance) = JsonObject(full(appearance).filterKeys(fields::containsKey))
}

private fun full(value: Appearance) = appearanceFormat.encodeToJsonElement(value).jsonObject

sealed interface AppearancePatch {
    fun applyTo(current: Appearance): Appearance

    data class Replace(val value: Appearance) : AppearancePatch {
        override fun applyTo(current: Appearance) = value
    }
}

internal data class AppearanceDifference(val key: String, val label: () -> String, val before: JsonElement, val after: JsonElement)

internal fun appearanceDifferences(before: Appearance, after: Appearance): List<AppearanceDifference> = buildList {
    val old = full(before); val next = full(after)
    for (group in AppearanceGroup.entries) for ((key, label) in group.fields) {
        val a = old.getValue(key); val b = next.getValue(key)
        if (a != b) add(AppearanceDifference(key, label, a, b))
    }
}

internal fun appearanceLabel(value: JsonElement): String = when (value) {
    is JsonObject -> if (value.isEmpty()) uiText("Inherit the global background", "继承全局背景")
        else if ("scale" in value || "imageId" in value) {
            val preset = value["preset"]?.jsonPrimitive?.contentOrNull
            val source = if (preset != null) WallpaperPreset.entries.firstOrNull { it.name == preset }?.label
                else value["imageId"]?.jsonPrimitive?.contentOrNull?.let { uiText("Photo", "图片") }
            if (source == null) uiText("Theme color", "主题纯色")
            else source + " · " + uiText("Scale", "缩放") + " " + value["scale"] + " · " + uiText("Offset", "偏移") + " (" + value["offsetX"] + ", " + value["offsetY"] + ") · " + uiText("Tint", "遮罩") + " " + value["tint"]
        } else value.entries.joinToString("\n") { (key, spec) -> (Destination.entries.firstOrNull { it.name == key }?.label ?: key) + ": " + appearanceLabel(spec) }
    is JsonArray -> value.joinToString(" → ") { appearanceLabel(it) }
    else -> when (val s = value.jsonPrimitive.content) {
        "true" -> uiText("On", "开启"); "false" -> uiText("Off", "关闭"); "SYSTEM" -> uiText("System default", "跟随系统")
        "LIGHT" -> uiText("Light", "浅色"); "DARK" -> uiText("Dark", "深色"); "DYNAMIC" -> uiText("Dynamic color", "动态色")
        "FLAT" -> uiText("Flat", "平面"); "WHEEL" -> uiText("Wheel", "滚轮")
        "SEED" -> uiText("Seed color", "种子色"); "FLOATING" -> uiText("Floating pill", "浮动胶囊"); "DOCKED" -> uiText("Bottom bar", "贴底导航")
        else -> Destination.entries.find { it.name == s }?.label ?: s
    }
}

internal class AppearanceFields(private val group: AppearanceGroup, values: JsonObject?) : AppearancePatch {
    private val entries = values?.toMap() ?: group.project(Appearance())
    init {
        require(entries.isNotEmpty() && entries.keys.all(group.fields::containsKey)) { uiText("Select at least one setting declared in this group.", "只能修改本组已声明的非空设置。") }
        // Cross-field invariants are checked against the actual transaction baseline in applyTo.
    }
    fun matchesBaseline(expected: Appearance, current: Appearance) = group.project(expected) == group.project(current)
    override fun applyTo(current: Appearance): Appearance = appearanceFormat.decodeFromJsonElement(JsonObject(full(current) + entries))
}
