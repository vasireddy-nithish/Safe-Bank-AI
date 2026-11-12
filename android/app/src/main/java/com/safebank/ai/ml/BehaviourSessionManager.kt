package com.safebank.ai.ml

import android.content.Context
import kotlin.math.abs

/**
 * Holds volatile in-memory behavioural events for the active session, extracts the
 * 48-feature vector, runs on-device TFLite inference, and compares against the
 * enrolled personalised baseline (with cold-start protection).
 */
class BehaviourSessionManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val classifier = BehaviourClassifier(appContext)
    private val profileManager = BehaviourProfileManager.getInstance(appContext)

    private val touches = mutableListOf<TouchEvent>()
    private val typingEvents = mutableListOf<TypingEvent>()
    private val motionSamples = mutableListOf<MotionSample>()
    private var sessionStartMs: Long = System.currentTimeMillis()
    private var lastClassification: BehaviourClassification? = null

    companion object {
        @Volatile
        private var instance: BehaviourSessionManager? = null

        fun getInstance(context: Context): BehaviourSessionManager {
            return instance ?: synchronized(this) {
                instance ?: BehaviourSessionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    @Synchronized
    fun addTouchEvent(event: TouchEvent) {
        if (touches.size > 500) touches.removeAt(0)
        touches.add(event)
    }

    @Synchronized
    fun addTypingEvent(event: TypingEvent) {
        if (typingEvents.size > 500) typingEvents.removeAt(0)
        typingEvents.add(event)
    }

    @Synchronized
    fun addMotionSample(sample: MotionSample) {
        if (motionSamples.size > 500) motionSamples.removeAt(0)
        motionSamples.add(sample)
    }

    @Synchronized
    fun getDataPointCount(): Int = touches.size + typingEvents.size + motionSamples.size

    @Synchronized
    fun snapshotSession(): BehaviourSession {
        return BehaviourSession(
            touches = touches.toList(),
            typingEvents = typingEvents.toList(),
            motionSamples = motionSamples.toList(),
            sessionStartMs = sessionStartMs
        )
    }

    fun classify(): BehaviourClassification {
        val session = snapshotSession()
        val dataPoints = getDataPointCount()
        if (dataPoints < 3) {
            // Cold-start conservative classification: do not claim high genuine confidence
            val coldStart = BehaviourClassification(
                genuineProbability = 0.40f,
                uncertainProbability = 0.45f,
                anomalyProbability = 0.15f,
                predictedClass = 1,
                riskScore = 25,
                isFromLiveModel = false
            )
            lastClassification = coldStart
            return coldStart
        }

        val features = FeatureExtractor.extract(session)
        val result = classifier.classify(features)
        lastClassification = result
        return result
    }

    suspend fun classifyWithBaseline(): BehaviourClassification {
        val baseResult = classify()
        val baseline = profileManager.getBaselineVector()
        val session = snapshotSession()
        if (baseline == null || getDataPointCount() < 3) {
            return baseResult
        }

        val current = FeatureExtractor.extract(session)
        // Compute bounded normalized distance across active non-zero features
        var diffSum = 0f
        var count = 0
        for (i in 0 until 48) {
            val ref = baseline[i]
            if (abs(ref) > 0.01f) {
                diffSum += (abs(current[i] - ref) / (abs(ref) + 1f)).coerceAtMost(2f)
                count++
            }
        }
        val avgDrift = if (count > 0) diffSum / count else 0f
        val driftPenalty = (avgDrift * 25f).toInt().coerceIn(0, 35)
        val combinedScore = (baseResult.riskScore + driftPenalty).coerceIn(0, 100)
        val combined = baseResult.copy(riskScore = combinedScore)
        lastClassification = combined
        profileManager.saveLastRiskScore(combinedScore)
        return combined
    }

    @Synchronized
    fun resetSession() {
        touches.clear()
        typingEvents.clear()
        motionSamples.clear()
        sessionStartMs = System.currentTimeMillis()
        lastClassification = null
    }

    fun getCurrentRiskScore(): Int = lastClassification?.riskScore ?: 18

    fun getLastClassification(): BehaviourClassification? = lastClassification
}

