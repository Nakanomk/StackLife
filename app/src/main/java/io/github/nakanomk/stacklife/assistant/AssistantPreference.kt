package io.github.nakanomk.stacklife.assistant

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.SwitchRow
import io.github.nakanomk.stacklife.workspace.Hint

// BiliPai SettingsTextCopyPreference / AppSwitchPreference (c7f835b, GPL-3.0):
// explicit user toggle, device-scoped value and an explanation in the same row.
internal class AssistantPreference(context: Context) {
    private val store = context.getSharedPreferences("assistant-preferences", Context.MODE_PRIVATE)
    private val current = mutableStateOf(store.getBoolean("enabled", true))
    val enabled: Boolean by current

    fun setEnabled(value: Boolean) = io.github.nakanomk.stacklife.archive.DevicePreferenceGate.change {
        if (enabled != value) {
            store.edit().putBoolean("enabled", value).apply()
            current.value = value
        }
    }

    internal fun restore(value: Boolean) {
        check(store.edit().putBoolean("enabled", value).commit())
        current.value = value
    }
}

@Composable
internal fun AssistantEnabledPreference(preference: AssistantPreference, assistant: AssistantModel) {
    SwitchRow(stringResource(R.string.assistant_enabled_label), preference.enabled) {
        val enabled = !preference.enabled
        assistant.setEnabled(enabled)
        preference.setEnabled(enabled)
    }
    Hint(stringResource(R.string.assistant_enabled_help))
}
