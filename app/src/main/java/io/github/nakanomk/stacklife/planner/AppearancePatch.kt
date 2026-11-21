package io.github.nakanomk.stacklife.planner

import io.github.nakanomk.stacklife.i18n.uiText
import kotlinx.serialization.json.*

private val appearanceFormat = Json { encodeDefaults = true }
internal fun encodeAppearance(value: Appearance): String = appearanceFormat.encodeToString(value)
internal fun decodeAppearance(text: String): Appearance = appearanceFormat.decodeFromString(text)

internal enum class AppearanceGroup(val key: String, val fields: Map<String, () -> String>) {
    COLOR("color", mapOf("theme" to { uiText("Theme", "明暗") }, "palette" to { uiText("Color source", "色彩来源") }, "seed" to { uiText("Seed color", "种子色") })),
    LAYOUT("layout", mapOf("compact" to { uiText("Compact spacing", "紧凑留白") }, "navStyle" to { uiText("Navigation style", "导航样式") }, "order" to { uiText("Navigation order", "导航次序") }, "start" to { uiText("Start page", "启动页") },
        "floatingDayTray" to { uiText("Floating queue tray", "悬浮待排队列") }, "showDuration" to { uiText("Show duration", "显示时长") }, "showNotes" to { uiText("Show notes", "显示备注") })),
    MATERIAL("material", mapOf("glass" to { uiText("Liquid glass", "液态玻璃") }, "navGlass" to { uiText("Navigation glass", "导航玻璃") }, "assistantGlass" to { uiText("Assistant glass", "助理玻璃") },
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

    companion object {
        internal fun changes(before: Appearance, after: Appearance, into: MutableList<PlannerChange>) {
            val old = full(before); val new = full(after)
            for (group in AppearanceGroup.entries) for ((field, label) in group.fields) {
                val a = old.getValue(field); val b = new.getValue(field)
                if (a != b) into.add(PlannerChange("外观", label(), display(a), display(b), uiText("Appearance", "外观")))
            }
        }
        private fun display(value: JsonElement): String = when (value) {
            is JsonArray -> value.joinToString(" → ") { display(it) }
            else -> when (val s = value.jsonPrimitive.content) {
                "true" -> uiText("On", "开启"); "false" -> uiText("Off", "关闭"); "SYSTEM" -> uiText("System default", "跟随系统"); "LIGHT" -> uiText("Light", "浅色"); "DARK" -> uiText("Dark", "深色")
                "DYNAMIC" -> uiText("Dynamic color", "动态色"); "SEED" -> uiText("Seed color", "种子色"); "FLOATING" -> uiText("Floating pill", "浮动胶囊"); "DOCKED" -> uiText("Bottom bar", "贴底导航")
                else -> Destination.entries.find { it.name == s }?.label ?: s
            }
        }
    }
}

internal class AppearanceFields(private val group: AppearanceGroup, values: JsonObject?) : AppearancePatch {
    private val entries = values?.toMap() ?: group.project(Appearance())
    init {
        require(entries.isNotEmpty() && entries.keys.all(group.fields::containsKey)) { uiText("Select at least one setting declared in this group.", "只能修改本组已声明的非空设置。") }
        applyTo(Appearance())
    }
    fun matchesBaseline(expected: Appearance, current: Appearance) = group.project(expected) == group.project(current)
    override fun applyTo(current: Appearance): Appearance = appearanceFormat.decodeFromJsonElement(JsonObject(full(current) + entries))
}
