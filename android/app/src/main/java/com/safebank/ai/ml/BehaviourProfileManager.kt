package com.safebank.ai.ml

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.behaviourDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "safebank_behaviour_profile"
)

/**
 * Stores local behavioural biometric enrolment metadata and personalised baseline summary
 * strictly on-device using Jetpack DataStore. Raw sensor traces are never persisted or uploaded.
 */
class BehaviourProfileManager private constructor(private val context: Context) {

    companion object {
        private val KEY_ENROLMENT_COMPLETE = booleanPreferencesKey("enrolment_complete")
        private val KEY_SESSION_COUNT = intPreferencesKey("enrolment_session_count")
        private val KEY_LAST_RISK_SCORE = intPreferencesKey("last_risk_score")
        private val KEY_BASELINE_VECTOR = stringPreferencesKey("baseline_feature_vector")

        @Volatile
        private var instance: BehaviourProfileManager? = null

        fun getInstance(context: Context): BehaviourProfileManager {
            return instance ?: synchronized(this) {
                instance ?: BehaviourProfileManager(context.applicationContext).also { instance = it }
            }
        }
    }

    suspend fun isEnrolmentComplete(): Boolean {
        return context.behaviourDataStore.data
            .map { prefs -> prefs[KEY_ENROLMENT_COMPLETE] ?: false }
            .first()
    }

    suspend fun getSessionCount(): Int {
        return context.behaviourDataStore.data
            .map { prefs -> prefs[KEY_SESSION_COUNT] ?: 0 }
            .first()
    }

    suspend fun markEnrolmentComplete(baselineFeatures: FloatArray? = null) {
        context.behaviourDataStore.edit { prefs ->
            prefs[KEY_ENROLMENT_COMPLETE] = true
            prefs[KEY_SESSION_COUNT] = (prefs[KEY_SESSION_COUNT] ?: 0) + 1
            if (baselineFeatures != null && baselineFeatures.size == 48) {
                prefs[KEY_BASELINE_VECTOR] = baselineFeatures.joinToString(",")
            }
        }
    }

    /**
     * Updates the personalised baseline ONLY after a strongly authenticated, low-risk session.
     * Prevents profile contamination from suspicious sessions.
     */
    suspend fun updateBaselineIfTrusted(newFeatures: FloatArray, riskScore: Int) {
        if (riskScore >= 30 || newFeatures.size != 48) return
        context.behaviourDataStore.edit { prefs ->
            val currentCount = prefs[KEY_SESSION_COUNT] ?: 0
            val existing = prefs[KEY_BASELINE_VECTOR]
                ?.split(",")
                ?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 48 }

            val updated = if (existing == null || currentCount == 0) {
                newFeatures
            } else {
                // Exponential moving average (alpha = 0.2) to adapt gradually without drift
                FloatArray(48) { i -> existing[i] * 0.8f + newFeatures[i] * 0.2f }
            }
            prefs[KEY_BASELINE_VECTOR] = updated.joinToString(",")
            prefs[KEY_SESSION_COUNT] = currentCount + 1
        }
    }

    suspend fun getBaselineVector(): FloatArray? {
        val raw = context.behaviourDataStore.data
            .map { prefs -> prefs[KEY_BASELINE_VECTOR] }
            .first() ?: return null
        val parsed = raw.split(",").mapNotNull { it.toFloatOrNull() }
        return if (parsed.size == 48) parsed.toFloatArray() else null
    }

    suspend fun saveLastRiskScore(score: Int) {
        context.behaviourDataStore.edit { prefs ->
            prefs[KEY_LAST_RISK_SCORE] = score.coerceIn(0, 100)
        }
    }

    suspend fun getLastRiskScore(): Int {
        return context.behaviourDataStore.data
            .map { prefs -> prefs[KEY_LAST_RISK_SCORE] ?: 20 }
            .first()
    }

    suspend fun resetEnrolment() {
        context.behaviourDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}

