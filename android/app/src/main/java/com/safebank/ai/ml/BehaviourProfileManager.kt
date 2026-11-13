package com.safebank.ai.ml

import android.content.Context
import com.safebank.ai.data.security.SecureSessionManager
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
 * per user using Jetpack DataStore. The backend owns enrolment progress and the initial
 * derived baseline. Raw sensor traces are never persisted or uploaded.
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
        val owner = SecureSessionManager.getInstance(context).getUserId() ?: return false
        return context.behaviourDataStore.data
            .map { prefs -> prefs[booleanPreferencesKey(owner + ":v2:" + KEY_ENROLMENT_COMPLETE.name)] ?: false }
            .first()
    }

    suspend fun getSessionCount(): Int {
        val owner = SecureSessionManager.getInstance(context).getUserId() ?: return 0
        return context.behaviourDataStore.data
            .map { prefs -> prefs[intPreferencesKey(owner + ":v2:" + KEY_SESSION_COUNT.name)] ?: 0 }
            .first()
    }

    suspend fun markEnrolmentComplete(baselineFeatures: FloatArray? = null) {
        val owner = SecureSessionManager.getInstance(context).getUserId() ?: return
        context.behaviourDataStore.edit { prefs ->
            prefs[booleanPreferencesKey(owner + ":v2:" + KEY_ENROLMENT_COMPLETE.name)] = true
            prefs[intPreferencesKey(owner + ":v2:" + KEY_SESSION_COUNT.name)] = (prefs[intPreferencesKey(owner + ":v2:" + KEY_SESSION_COUNT.name)] ?: 0).coerceAtLeast(1)
            if (baselineFeatures != null && baselineFeatures.size == 48 &&
                prefs[stringPreferencesKey(owner + ":v2:" + KEY_BASELINE_VECTOR.name)] == null) {
                prefs[stringPreferencesKey(owner + ":v2:" + KEY_BASELINE_VECTOR.name)] = baselineFeatures.joinToString(",")
            }
        }
    }

    /**
     * Updates the personalised baseline ONLY after a strongly authenticated, low-risk session.
     * Prevents profile contamination from suspicious sessions.
     */
    suspend fun updateBaselineIfTrusted(newFeatures: FloatArray, riskScore: Int) {
        val owner = SecureSessionManager.getInstance(context).getUserId() ?: return
        if (riskScore >= 30 || newFeatures.size != 48) return
        context.behaviourDataStore.edit { prefs ->
            val currentCount = prefs[intPreferencesKey(owner + ":v2:" + KEY_SESSION_COUNT.name)] ?: 0
            val existing = prefs[stringPreferencesKey(owner + ":v2:" + KEY_BASELINE_VECTOR.name)]
                ?.split(",")
                ?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 48 }

            val updated = if (existing == null || currentCount == 0) {
                newFeatures
            } else {
                // Exponential moving average (alpha = 0.2) to adapt gradually without drift
                FloatArray(48) { i -> existing[i] * 0.8f + newFeatures[i] * 0.2f }
            }
            prefs[stringPreferencesKey(owner + ":v2:" + KEY_BASELINE_VECTOR.name)] = updated.joinToString(",")
            prefs[intPreferencesKey(owner + ":v2:" + KEY_SESSION_COUNT.name)] = currentCount + 1
        }
    }

    suspend fun getBaselineVector(): FloatArray? {
        val owner = SecureSessionManager.getInstance(context).getUserId() ?: return null
        val raw = context.behaviourDataStore.data
            .map { prefs -> prefs[stringPreferencesKey(owner + ":v2:" + KEY_BASELINE_VECTOR.name)] }
            .first() ?: return null
        val parsed = raw.split(",").mapNotNull { it.toFloatOrNull() }
        return if (parsed.size == 48) parsed.toFloatArray() else null
    }

    suspend fun saveLastRiskScore(score: Int) {
        val owner = SecureSessionManager.getInstance(context).getUserId() ?: return
        context.behaviourDataStore.edit { prefs ->
            prefs[intPreferencesKey(owner + ":v2:" + KEY_LAST_RISK_SCORE.name)] = score.coerceIn(0, 100)
        }
    }

    suspend fun getLastRiskScore(): Int {
        val owner = SecureSessionManager.getInstance(context).getUserId() ?: return 20
        return context.behaviourDataStore.data
            .map { prefs -> prefs[intPreferencesKey(owner + ":v2:" + KEY_LAST_RISK_SCORE.name)] ?: 20 }
            .first()
    }

    suspend fun resetEnrolment() {
        val owner = SecureSessionManager.getInstance(context).getUserId() ?: return
        context.behaviourDataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(owner + ":v2:") }.forEach { prefs.remove(it) }
        }
    }
}

