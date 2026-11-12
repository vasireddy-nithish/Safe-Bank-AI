package com.safebank.ai.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "BehaviourClassifier"

data class BehaviourClassification(
    val genuineProbability: Float,
    val uncertainProbability: Float,
    val anomalyProbability: Float,
    val predictedClass: Int,       // 0=genuine, 1=uncertain, 2=anomaly
    val riskScore: Int,            // 0-100 mapped from probabilities
    val isFromLiveModel: Boolean   // false if model not loaded
)

/**
 * TFLite inference engine for behavioural biometrics.
 *
 * Loads `behaviour_model.tflite` and `scaler_params.json` from assets.
 * Falls back gracefully when the model file is not yet present.
 */
class BehaviourClassifier(context: Context) {

    private var interpreter: Interpreter? = null
    private var scalerMeans: FloatArray = FloatArray(48) { 0f }
    private var scalerStds: FloatArray = FloatArray(48) { 1f }
    private var modelLoaded = false

    init {
        try {
            loadModel(context)
        } catch (e: Throwable) {
            Log.w(TAG, "TFLite model not available: ${e.message} — classifier will return uncertain results")
        }
        try {
            loadScalerParams(context)
        } catch (e: Throwable) {
            Log.w(TAG, "Scaler params not available: ${e.message} — using identity transform")
        }
    }

    private fun loadModel(context: Context) {
        val modelBytes = context.assets.open("behaviour_model.tflite").readBytes()
        val buffer = ByteBuffer.allocateDirect(modelBytes.size).apply {
            order(ByteOrder.nativeOrder())
            put(modelBytes)
            rewind()
        }
        interpreter = Interpreter(buffer)
        modelLoaded = true
        Log.i(TAG, "TFLite model loaded successfully (${modelBytes.size} bytes)")
    }

    private fun loadScalerParams(context: Context) {
        val json = context.assets.open("scaler_params.json").bufferedReader().readText()
        // Manual JSON parse to avoid adding a Gson dependency call path risk
        // Expected format: {"means": [...], "stds": [...]}
        val meansMatch = Regex("\"means\"\\s*:\\s*\\[([^]]+)]").find(json)
        val stdsMatch = Regex("\"stds\"\\s*:\\s*\\[([^]]+)]").find(json)

        meansMatch?.groupValues?.get(1)?.let { raw ->
            val parsed = raw.split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (parsed.size == 48) {
                scalerMeans = parsed.toFloatArray()
                Log.i(TAG, "Scaler means loaded (48 values)")
            }
        }
        stdsMatch?.groupValues?.get(1)?.let { raw ->
            val parsed = raw.split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (parsed.size == 48) {
                scalerStds = parsed.toFloatArray()
                Log.i(TAG, "Scaler stds loaded (48 values)")
            }
        }
    }

    /**
     * Classify a raw 48-float feature vector.
     * Returns [BehaviourClassification] with isFromLiveModel=false when model unavailable.
     */
    fun classify(rawFeatures: FloatArray): BehaviourClassification {
        require(rawFeatures.size == 48) { "Feature vector must have exactly 48 elements" }

        if (!modelLoaded || interpreter == null) {
            Log.d(TAG, "Model not loaded — returning uncertain fallback")
            return BehaviourClassification(
                genuineProbability = 0.4f,
                uncertainProbability = 0.4f,
                anomalyProbability = 0.2f,
                predictedClass = 1,
                riskScore = 30,
                isFromLiveModel = false
            )
        }

        // Apply StandardScaler: (x - mean) / std
        val scaled = FloatArray(48) { i ->
            val std = scalerStds[i].let { if (it == 0f) 1f else it }
            (rawFeatures[i] - scalerMeans[i]) / std
        }

        val input = Array(1) { scaled }
        val output = Array(1) { FloatArray(3) }

        try {
            interpreter!!.run(input, output)
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference failed: ${e.message}")
            return BehaviourClassification(0.4f, 0.4f, 0.2f, 1, 30, false)
        }

        val probs = output[0]
        val predicted = probs.indices.maxByOrNull { probs[it] } ?: 1

        // Risk score 0-100: genuine→low, uncertain→moderate, anomaly→high
        val riskScore = ((probs[1] * 30f) + (probs[2] * 100f)).toInt().coerceIn(0, 100)

        return BehaviourClassification(
            genuineProbability = probs[0],
            uncertainProbability = probs[1],
            anomalyProbability = probs[2],
            predictedClass = predicted,
            riskScore = riskScore,
            isFromLiveModel = true
        )
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
