/* Copyright 2021 The Android Open Source Project. SPDX-License-Identifier: Apache-2.0.
 * Material3 catalog's ThemePicker: an independent sheet for the live example environment. */
package io.github.nakanomk.stacklife.gallery

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.ThemeMode
import io.github.nakanomk.stacklife.workspace.CheckRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class GalleryOptions(val theme: ThemeMode = ThemeMode.SYSTEM, val large: Boolean = false,
    val rtl: Boolean = false, val narrow: Boolean = false, val still: Boolean = false, val glass: Boolean = true,
    val contrast: Boolean = false, val noAcceleration: Boolean = false) {
    fun save(prefs: SharedPreferences) { prefs.edit().putString("preview", Json.encodeToString(this)).apply() }
    companion object {
        fun load(prefs: SharedPreferences): GalleryOptions = prefs.getString("preview", null)?.let {
            Json.decodeFromString<GalleryOptions>(it)
        } ?: GalleryOptions()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryThemePicker(value: GalleryOptions, update: (GalleryOptions) -> Unit, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(uiText("Gallery options", "预览设置"), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (theme in ThemeMode.entries) FilterChip(selected = value.theme == theme,
                    onClick = { update(value.copy(theme = theme)) }, label = { Text(when (theme) {
                        ThemeMode.SYSTEM -> uiText("System", "跟随系统")
                        ThemeMode.LIGHT -> uiText("Light", "浅色")
                        ThemeMode.DARK -> uiText("Dark", "深色")
                    }) })
            }
            CheckRow(uiText("200% type", "200% 字号"), value.large) { update(value.copy(large = !value.large)) }
            CheckRow(uiText("Right to left", "从右向左（RTL）"), value.rtl) { update(value.copy(rtl = !value.rtl)) }
            CheckRow(uiText("320 dp example width", "示例宽度 320 dp"), value.narrow) { update(value.copy(narrow = !value.narrow)) }
            CheckRow(uiText("Reduce motion", "减弱动效"), value.still) { update(value.copy(still = !value.still)) }
            CheckRow(uiText("Glass", "玻璃"), value.glass) { update(value.copy(glass = !value.glass)) }
            CheckRow(uiText("High contrast", "增强对比"), value.contrast) { update(value.copy(contrast = !value.contrast)) }
            CheckRow(uiText("Preview missing acceleration", "预览无硬件加速的降级"), value.noAcceleration) {
                update(value.copy(noAcceleration = !value.noAcceleration))
            }
            TextButton(onClick = close) { Text(uiText("Done", "完成")) }
            Spacer(Modifier.height(16.dp))
        }
    }
}
