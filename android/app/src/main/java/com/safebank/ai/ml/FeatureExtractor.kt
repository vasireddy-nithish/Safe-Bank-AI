package com.safebank.ai.ml

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Extracts exactly 48 behavioural features from a [BehaviourSession].
 * Feature order must match the Python training pipeline exactly.
 *
 * Features 0-19:  Touch Dynamics
 * Features 20-31: Typing Dynamics
 * Features 32-47: Motion Dynamics
 */
object FeatureExtractor {

    fun extract(session: BehaviourSession): FloatArray {
        val features = FloatArray(48)

        // ── Features 0-19: Touch Dynamics ──────────────────────────────────
        val touches = session.touches
        if (touches.isNotEmpty()) {
            val durations = touches.map { it.durationMs.toFloat() }
            val strokeLengths = touches.map { it.velocity * it.durationMs }
            val velocities = touches.map { it.velocity }
            val pressures = touches.map { it.pressure }
            val majorAxes = touches.map { it.majorAxis }
            val minorAxes = touches.map { it.minorAxis }

            features[0] = durations.safeMean()
            features[1] = durations.safeStd()
            features[2] = strokeLengths.safeMean()
            features[3] = strokeLengths.safeStd()
            features[4] = velocities.safeMean()
            features[5] = velocities.maxOrNull() ?: 0f
            // Acceleration proxy: variance of velocities
            features[6] = velocities.safeVariance()
            features[7] = pressures.safeMean()
            features[8] = pressures.maxOrNull() ?: 0f
            features[9] = pressures.safeStd()
            features[10] = majorAxes.safeMean()
            features[11] = minorAxes.safeMean()
            // Trajectory curvature: single-point touches → 1.0
            features[12] = if (touches.size < 2) 1.0f else {
                val xs = touches.map { it.x }
                val ys = touches.map { it.y }
                val dxTotal = abs(xs.last() - xs.first())
                val dyTotal = abs(ys.last() - ys.first())
                val directDist = sqrt(dxTotal * dxTotal + dyTotal * dyTotal)
                val pathLen = strokeLengths.sum()
                if (pathLen > 0f) (directDist / pathLen).coerceIn(0f, 1f) else 1.0f
            }
            features[13] = atan2(touches.first().y, touches.first().x)
            features[14] = atan2(touches.last().y, touches.last().x)

            // Inter-event times
            if (touches.size >= 2) {
                val interTimes = (1 until touches.size).map {
                    (touches[it].timestampMs - touches[it - 1].timestampMs).toFloat()
                }
                features[15] = interTimes.safeMean()
                features[16] = interTimes.safeStd()
            } else {
                features[15] = 0f
                features[16] = 0.001f
            }

            // Swipe horizontal ratio: proportion where |x-velocity| dominates
            val horizontalCount = touches.count { touch ->
                val dx = abs(touch.x)
                val dy = abs(touch.y)
                dx > dy
            }
            features[17] = horizontalCount.toFloat() / touches.size.toFloat()
            features[18] = pressures.safeVariance()
            features[19] = (touches.size / 100f).coerceAtMost(1.0f)
        } else {
            // Empty touch data defaults
            features[0] = 0f; features[1] = 0.001f; features[2] = 0f; features[3] = 0.001f
            features[4] = 0f; features[5] = 0f; features[6] = 0f
            features[7] = 0f; features[8] = 0f; features[9] = 0.001f
            features[10] = 0f; features[11] = 0f; features[12] = 1.0f
            features[13] = 0f; features[14] = 0f
            features[15] = 0f; features[16] = 0.001f
            features[17] = 0f; features[18] = 0f; features[19] = 0f
        }

        // ── Features 20-31: Typing Dynamics ───────────────────────────────
        val typing = session.typingEvents
        if (typing.isNotEmpty()) {
            val holdTimes = typing.map { it.holdTimeMs.toFloat() }
            val flightTimes = typing.filter { it.flightTimeMs >= 0L }.map { it.flightTimeMs.toFloat() }
            val pressures = typing.map { it.pressure }
            val backspaceCount = typing.count { it.isBackspace }

            features[20] = holdTimes.safeMean()
            features[21] = holdTimes.safeStd()
            features[22] = holdTimes.safeMedian()
            features[23] = flightTimes.safeMean()
            features[24] = flightTimes.safeStd()
            features[25] = features[23]  // digraph latency = flight time mean for prototype

            features[26] = if (typing.isNotEmpty()) {
                (backspaceCount.toFloat() / typing.size.toFloat()) * 100f
            } else 0f

            // Typing speed (chars/min): total chars / total hold time (ms) * 60000
            val totalHoldMs = holdTimes.sum()
            features[27] = if (totalHoldMs > 0f) {
                (typing.size.toFloat() / totalHoldMs) * 60000f
            } else 0f

            features[28] = pressures.safeMean()
            features[29] = pressures.safeStd()
            features[30] = 0f  // contact_size not available from text input API

            // Rhythm entropy: coefficient of variation (std / mean)
            val mean = holdTimes.safeMean()
            val std = holdTimes.safeStd()
            features[31] = if (mean > 0f) (std / mean) else 0f
        } else {
            features[20] = 0f; features[21] = 0.001f; features[22] = 0f
            features[23] = 0f; features[24] = 0.001f; features[25] = 0f
            features[26] = 0f; features[27] = 0f
            features[28] = 0f; features[29] = 0.001f
            features[30] = 0f; features[31] = 0f
        }

        // ── Features 32-47: Motion Dynamics ───────────────────────────────
        val motion = session.motionSamples
        if (motion.isNotEmpty()) {
            val ax = motion.map { it.accelX }
            val ay = motion.map { it.accelY }
            val az = motion.map { it.accelZ }
            val gx = motion.map { it.gyroX }
            val gy = motion.map { it.gyroY }
            val gz = motion.map { it.gyroZ }

            val magnitudes = motion.map { s ->
                sqrt(s.accelX * s.accelX + s.accelY * s.accelY + s.accelZ * s.accelZ)
            }

            features[32] = ax.safeMean()
            features[33] = ax.safeStd()
            features[34] = ay.safeMean()
            features[35] = ay.safeStd()
            features[36] = az.safeMean()
            features[37] = az.safeStd()
            features[38] = magnitudes.safeMean()
            features[39] = magnitudes.safeVariance()
            features[40] = gx.safeMean()
            features[41] = gx.safeStd()
            features[42] = gy.safeMean()
            features[43] = gy.safeStd()
            features[44] = gz.safeMean()
            features[45] = gz.safeStd()

            // Tilt angle: atan2(sqrt(x²+y²), |z|) mean
            val tiltAngles = motion.map { s ->
                atan2(sqrt(s.accelX * s.accelX + s.accelY * s.accelY), abs(s.accelZ))
            }
            features[46] = tiltAngles.safeMean()
            // Micro-tremor energy: variance of accel magnitude
            features[47] = magnitudes.safeVariance()
        } else {
            for (i in 32..45) {
                features[i] = if (i % 2 == 1) 0.001f else 0f
            }
            features[46] = 0f; features[47] = 0f
        }

        return features
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun List<Float>.safeMean(): Float =
        if (isEmpty()) 0f else (sum() / size.toFloat())

    private fun List<Float>.safeVariance(): Float {
        if (size < 2) return 0f
        val mean = safeMean()
        return map { (it - mean) * (it - mean) }.sum() / size.toFloat()
    }

    private fun List<Float>.safeStd(): Float =
        if (size < 2) 0.001f else {
            val variance = safeVariance()
            if (variance > 0f) sqrt(variance) else 0.001f
        }

    private fun List<Float>.safeMedian(): Float {
        if (isEmpty()) return 0f
        val sorted = sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        } else {
            sorted[sorted.size / 2]
        }
    }
}
