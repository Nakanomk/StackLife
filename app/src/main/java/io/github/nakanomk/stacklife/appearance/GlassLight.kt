package io.github.nakanomk.stacklife.appearance

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.atan2
import kotlin.math.roundToInt

private val restAngle = mutableIntStateOf(45)
internal val LocalGlassLight = staticCompositionLocalOf<IntState> { restAngle }

@Composable
internal fun rememberGlassLight(): IntState {
    val appearance = LocalAppearance.current
    if (!appearance.glass || !LocalMotionEnabled.current || glassUnavailableReason(appearance) != null) return restAngle
    val view = LocalView.current
    val angle = remember { mutableIntStateOf(restAngle.intValue) }
    LifecycleResumeEffect(view) {
        val manager = view.context.getSystemService(SensorManager::class.java)
        val gravity = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]; val y = event.values[1]
                if (x*x + y*y < 1f) return
                val degrees = Math.toDegrees(atan2(y, -x).toDouble()) - 90 * (view.display?.rotation ?: 0)
                angle.intValue = (degrees / 3).roundToInt() * 3
            }
        }
        if (gravity != null) manager.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_UI)
        onPauseOrDispose { manager.unregisterListener(listener) }
    }
    return angle
}
