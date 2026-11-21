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

private val restAngle = mutableIntStateOf(-90)
internal val LocalGlassLight = staticCompositionLocalOf<IntState> { restAngle }

@Composable
internal fun rememberGlassLight(): IntState {
    val appearance = LocalAppearance.current
    if (!appearance.glass || !LocalMotionEnabled.current || glassUnavailableReason(appearance) != null) return restAngle
    val view = LocalView.current
    val angle = remember { mutableIntStateOf(restAngle.intValue) }
    LifecycleResumeEffect(view) {
        val manager = view.context.getSystemService(SensorManager::class.java)
        val rotation = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val matrix = FloatArray(9)
        var x = 0f
        var y = 0f
        var sampled = false
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                if (sampled) {
                    x += (-matrix[6] - x) * .15f
                    y += (-matrix[7] - y) * .15f
                } else {
                    x = -matrix[6]
                    y = -matrix[7]
                    sampled = true
                }
                angle.intValue = if (x*x + y*y <= .01f) restAngle.intValue else {
                    val degrees = Math.toDegrees(atan2(y, x).toDouble()) - 90 * (view.display?.rotation ?: 0)
                    (degrees / 3).roundToInt() * 3
                }
            }
        }
        if (rotation != null) manager.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
        onPauseOrDispose { manager.unregisterListener(listener) }
    }
    return angle
}
