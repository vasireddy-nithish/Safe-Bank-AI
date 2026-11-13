package com.safebank.ai.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeInferenceTest {
    @Test fun nativeInterpreterMatchesSklearnGoldenVectors() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val golden=JSONObject(context.assets.open("v2/golden_predictions.json").bufferedReader().readText())
        val classifier=BehaviourClassifier(context)
        val rows=golden.getJSONArray("rows")
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i)
            val raw=row.getJSONArray("raw")
            val expected=row.getJSONArray("probabilities")
            val result=classifier.classify(FloatArray(48) { raw.getDouble(it).toFloat() })
            assertTrue("Native TFLite must execute; fallback is not a passing test",result.isFromLiveModel)
            val actual=floatArrayOf(result.genuineProbability,result.uncertainProbability,result.anomalyProbability)
            for(j in 0..2) assertEquals(expected.getDouble(j),actual[j].toDouble(),0.00001)
        }
        classifier.close()
    }
}
