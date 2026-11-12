package com.safebank.ai.ml

data class TouchEvent(
    val x: Float,
    val y: Float,
    val pressure: Float,       // 0f if unsupported
    val majorAxis: Float,      // 0f if unsupported
    val minorAxis: Float,      // 0f if unsupported
    val timestampMs: Long,
    val durationMs: Long,
    val velocity: Float        // pixels/ms
)

data class TypingEvent(
    val holdTimeMs: Long,
    val flightTimeMs: Long,    // -1L if first key
    val isBackspace: Boolean,
    val timestampMs: Long,
    val pressure: Float        // 0f if unsupported
)

data class MotionSample(
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val timestampMs: Long
)

data class BehaviourSession(
    val touches: List<TouchEvent> = emptyList(),
    val typingEvents: List<TypingEvent> = emptyList(),
    val motionSamples: List<MotionSample> = emptyList(),
    val sessionStartMs: Long = System.currentTimeMillis()
)
