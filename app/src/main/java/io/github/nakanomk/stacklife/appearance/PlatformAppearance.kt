package io.github.nakanomk.stacklife.appearance

import androidx.compose.ui.res.stringResource
import io.github.nakanomk.stacklife.R

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import io.github.nakanomk.stacklife.planner.Appearance

internal val LocalAppearance = staticCompositionLocalOf { Appearance() }
internal val LocalMotionEnabled = compositionLocalOf { true }
internal val LocalPlatformAppearance = staticCompositionLocalOf { PlatformAppearance() }

internal data class PlatformAppearance(
    val accelerated: Boolean = false,
    val lowRam: Boolean = false,
    val powerSave: Boolean = false,
    val contrast: Float = 0f,
    val animators: Boolean = true,
    val haptics: Boolean = true,
)

@Composable
internal fun glassUnavailableReason(appearance: Appearance): String? {
    val platform = LocalPlatformAppearance.current
    return when {
        appearance.reduceTransparency -> stringResource(R.string.ui_transparency_reduced)
        appearance.highContrast -> stringResource(R.string.ui_contrast_increased)
        platform.contrast > 0f -> stringResource(R.string.ui_system_contrast)
        Build.VERSION.SDK_INT < 33 -> stringResource(R.string.ui_refraction_api)
        !platform.accelerated -> stringResource(R.string.ui_no_acceleration)
        platform.lowRam -> stringResource(R.string.ui_low_ram)
        platform.powerSave -> stringResource(R.string.ui_power_saving)
        else -> null
    }
}

// The pre-36 high-contrast-text switch has a Settings URI but no public manager listener.
private const val HIGH_CONTRAST_TEXT = "high_text_contrast_enabled"

@Composable
internal fun rememberPlatformAppearance(): PlatformAppearance {
    val view = LocalView.current
    val context = view.context.applicationContext
    val resolver = context.contentResolver
    val read = remember(view) {
        val power = context.getSystemService(PowerManager::class.java)
        val mode = context.getSystemService(UiModeManager::class.java)
        val access = context.getSystemService(AccessibilityManager::class.java)
        val lowRam = context.getSystemService(ActivityManager::class.java).isLowRamDevice
        return@remember {
            val highText = if (Build.VERSION.SDK_INT >= 36) access.isHighContrastTextEnabled
                else Settings.Secure.getInt(resolver, HIGH_CONTRAST_TEXT, 0) != 0
            PlatformAppearance(view.isHardwareAccelerated, lowRam, power.isPowerSaveMode,
                if (highText) 1f else if (Build.VERSION.SDK_INT >= 34) mode.contrast else 0f,
                Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f,
                Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0)
        }
    }
    var current by remember(read) { mutableStateOf(read()) }
    DisposableEffect(view) {
        fun refresh() { current = read() }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_ENABLED), false, observer)
        val removeTextListener: () -> Unit = if (Build.VERSION.SDK_INT >= 36) {
            val manager = context.getSystemService(AccessibilityManager::class.java)
            val listener = AccessibilityManager.HighContrastTextStateChangeListener { refresh() }
            manager.addHighContrastTextStateChangeListener(context.mainExecutor, listener)
            ({ manager.removeHighContrastTextStateChangeListener(listener) })
        } else {
            resolver.registerContentObserver(Settings.Secure.getUriFor(HIGH_CONTRAST_TEXT), false, observer)
            ({ })
        }
        val removeContrastListener: () -> Unit = if (Build.VERSION.SDK_INT >= 34) {
            val manager = context.getSystemService(UiModeManager::class.java)
            val listener = UiModeManager.ContrastChangeListener { refresh() }
            manager.addContrastChangeListener(context.mainExecutor, listener)
            ({ manager.removeContrastChangeListener(listener) })
        } else ({ })
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = refresh()
            override fun onViewDetachedFromWindow(v: View) { current = current.copy(accelerated = false) }
        }
        view.addOnAttachStateChangeListener(attach)
        refresh()
        onDispose {
            view.removeOnAttachStateChangeListener(attach)
            resolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
            removeTextListener()
            removeContrastListener()
        }
    }
    return current
}
