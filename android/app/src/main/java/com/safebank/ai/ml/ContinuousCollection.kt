package com.safebank.ai.ml

import android.hardware.*
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.sqrt

/** Foreground-only capture; no keyboard contents, screen coordinates or raw uploads. */
@Composable
fun continuousCollection(enabled: Boolean): Modifier {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = remember { BehaviourSessionManager.getInstance(context) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val active = enabled && foreground
    DisposableEffect(active) {
        manager.collectionConsented = active
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        var gx=0f; var gy=0f; var gz=0f
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(event: SensorEvent) {
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    gx=event.values[0]; gy=event.values[1]; gz=event.values[2]
                } else {
                    manager.addMotionSample(MotionSample(event.values[0],event.values[1],event.values[2],
                        gx,gy,gz,System.currentTimeMillis()))
                }
            }
        }
        if (active) {
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(listener,it,SensorManager.SENSOR_DELAY_UI) }
            sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(listener,it,SensorManager.SENSOR_DELAY_UI) }
        }
        onDispose {
            sensors.unregisterListener(listener)
            manager.collectionConsented=false
            manager.resetSession()
        }
    }
    return if (!active) Modifier else Modifier.pointerInput(active) {
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            var last=down.position
            var elapsed=1L
            do {
                val event=awaitPointerEvent(PointerEventPass.Final)
                val change=event.changes.firstOrNull { it.id==down.id } ?: break
                last=change.position
                elapsed=(change.uptimeMillis-down.uptimeMillis).coerceAtLeast(1)
            } while (event.changes.any { it.pressed })
            val dx=last.x-down.position.x;val dy=last.y-down.position.y
            manager.addTouchEvent(TouchEvent(dx,dy,0f,0f,0f,System.currentTimeMillis(),elapsed,
                sqrt(dx*dx+dy*dy)/elapsed))
        }
    }
}
