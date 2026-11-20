package io.github.nakanomk.stacklife.planner

import kotlinx.serialization.json.*

private val appearanceFormat = Json { encodeDefaults = true }
internal fun encodeAppearance(value: Appearance): String = appearanceFormat.encodeToString(value)
internal fun decodeAppearance(text: String): Appearance {
    val fields = appearanceFormat.parseToJsonElement(text).jsonObject
    val value = appearanceFormat.decodeFromJsonElement<Appearance>(fields)
    // Before 0.7 customized profiles omitted the old default `glass=false`; {} is the untouched profile.
    return if (fields.isNotEmpty() && "glass" !in fields) value.copy(glass = false) else value
}

internal enum class AppearanceGroup(val key: String, val fields: Map<String, String>) {
    COLOR("color", mapOf("theme" to "明暗", "palette" to "色彩来源", "seed" to "种子色")),
    LAYOUT("layout", mapOf("compact" to "紧凑留白", "navStyle" to "导航样式", "order" to "导航次序", "start" to "启动页",
        "showDuration" to "显示时长", "showNotes" to "显示备注")),
    MATERIAL("material", mapOf("glass" to "液态玻璃", "navGlass" to "导航玻璃", "assistantGlass" to "助理玻璃",
        "reduceMotion" to "减弱动效", "reduceTransparency" to "减少透明度", "highContrast" to "增强对比", "haptics" to "随系统触感"));

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
                if (a != b) into.add(PlannerChange("外观", label, display(a), display(b)))
            }
        }
        private fun display(value: JsonElement): String = when (value) {
            is JsonArray -> value.joinToString(" → ") { display(it) }
            else -> when (val s = value.jsonPrimitive.content) {
                "true" -> "开启"; "false" -> "关闭"; "SYSTEM" -> "跟随系统"; "LIGHT" -> "浅色"; "DARK" -> "深色"
                "DYNAMIC" -> "动态色"; "SEED" -> "种子色"; "FLOATING" -> "浮动胶囊"; "DOCKED" -> "贴底导航"
                else -> Destination.entries.find { it.name == s }?.label ?: s
            }
        }
    }
}

internal class AppearanceFields(private val group: AppearanceGroup, values: JsonObject?) : AppearancePatch {
    private val entries = values?.toMap() ?: group.project(Appearance())
    init {
        require(entries.isNotEmpty() && entries.keys.all(group.fields::containsKey)) { "只能修改本组已声明的非空设置。" }
        applyTo(Appearance())
    }
    fun matchesBaseline(expected: Appearance, current: Appearance) = group.project(expected) == group.project(current)
    override fun applyTo(current: Appearance): Appearance = appearanceFormat.decodeFromJsonElement(JsonObject(full(current) + entries))
}
