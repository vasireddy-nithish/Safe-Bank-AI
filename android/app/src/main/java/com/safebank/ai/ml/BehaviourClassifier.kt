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
    val isFromLiveModel: Boolean,   // false if model not loaded
    val inferenceStatus: String = if (isFromLiveModel) "live" else "unavailable"
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
            loadScalerParams(context)
            loadModel(context)
        } catch (e: Throwable) {
            Log.w(TAG, "TFLite model not available: ${e.message} — classifier will return uncertain results")
        }

    }

    private fun loadModel(context: Context) {
        val modelBytes = context.assets.open("v2/behaviour_model.tflite").readBytes()
        val buffer = ByteBuffer.allocateDirect(modelBytes.size).apply {
            order(ByteOrder.nativeOrder())
            put(modelBytes)
            rewind()
        }
        interpreter = Interpreter(buffer)
        check(interpreter!!.getInputTensor(0).shape().contentEquals(intArrayOf(1,48)))
        check(interpreter!!.getOutputTensor(0).shape().contentEquals(intArrayOf(1,3)))
        modelLoaded = true
        Log.i(TAG, "TFLite model loaded successfully (${modelBytes.size} bytes)")
    }

    private fun loadScalerParams(context: Context) {
        val contract = org.json.JSONObject(context.assets.open("v2/feature_contract.json").bufferedReader().use { it.readText() })
        require(contract.getString("version") == "safebank.behaviour.v2")
        require(contract.getInt("feature_count") == 48 && contract.getJSONArray("feature_names").length() == 48)
        val json = org.json.JSONObject(context.assets.open("v2/scaler_params.json").bufferedReader().use { it.readText() })
        require(json.getString("contract_version") == contract.getString("version"))
        val means = json.getJSONArray("means")
        val stds = json.getJSONArray("stds")
        require(means.length() == 48 && stds.length() == 48)
        scalerMeans = FloatArray(48) { means.getDouble(it).toFloat() }
        scalerStds = FloatArray(48) { stds.getDouble(it).toFloat() }
        require(scalerMeans.all { it.isFinite() } && scalerStds.all { it.isFinite() && it > 0f })
    }

    /**
     * Classify a raw 48-float feature vector.
     * Returns [BehaviourClassification] with isFromLiveModel=false when model unavailable.
     */
    fun classify(rawFeatures: FloatArray): BehaviourClassification {
        require(rawFeatures.size == 48 && rawFeatures.all { it.isFinite() }) { "Feature vector must have exactly 48 elements" }

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
        if (probs.any { !it.isFinite() || it < 0f || it > 1f } || kotlin.math.abs(probs.sum()-1f)>0.01f)
            return BehaviourClassification(0.4f,0.4f,0.2f,1,30,false,"invalid_output")
        val predicted = probs.indices.maxByOrNull { probs[it] } ?: 1

        // Risk score 0-100: genuine→low, uncertain→moderate, anomaly→high
        val riskScore = ((probs[1] * 35f) + (probs[2] * 100f)).toInt().coerceIn(0, 100)

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
