package com.safebank.ai.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android Keystore-backed secure storage for session tokens.
 *
 * Security Requirements:
 * - Uses EncryptedSharedPreferences backed by Android Keystore MasterKey (AES-256-GCM).
 * - Never stores tokens in plain unencrypted SharedPreferences.
 * - Never logs session tokens to Logcat or any persistent files.
 * - Supports listener callbacks on 401 session expiration.
 */
class SecureSessionManager private constructor(
    private val context: Context?,
    private val inMemoryOnly: Boolean = false
) : SessionTokenProvider {

    companion object {
        private const val TAG = "SecureSessionManager"
        private const val PREFS_FILE_NAME = "safebank_secure_session_prefs"
        private const val KEY_SESSION_TOKEN = "enc_session_token"
        private const val KEY_USER_ID = "enc_user_id"

        @Volatile
        private var instance: SecureSessionManager? = null

        fun getInstance(context: Context): SecureSessionManager {
            return instance ?: synchronized(this) {
                instance ?: SecureSessionManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Factory for testing / JVM environments without Android Keystore.
         */
        fun createInMemory(): SecureSessionManager {
            return SecureSessionManager(context = null, inMemoryOnly = true)
        }
    }

    private var inMemoryToken: String? = null
    private var inMemoryUserId: String? = null
    private val authListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    private val securePrefs: SharedPreferences? by lazy {
        if (inMemoryOnly || context == null) {
            null
        } else {
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Throwable) {
                Log.w(TAG, "EncryptedSharedPreferences initialization failed, falling back to memory mode: ${e.message}")
                null
            }
        }
    }

    override fun getSessionToken(): String? {
        return if (securePrefs != null) {
            securePrefs?.getString(KEY_SESSION_TOKEN, null)
        } else {
            inMemoryToken
        }
    }

    fun getUserId(): String? {
        return if (securePrefs != null) {
            securePrefs?.getString(KEY_USER_ID, null)
        } else {
            inMemoryUserId
        }
    }

    fun saveSession(token: String, userId: String? = null) {
        require(token.isNotBlank()) { "Session token cannot be blank" }

        inMemoryToken = token
        inMemoryUserId = userId

        securePrefs?.edit()?.apply {
            putString(KEY_SESSION_TOKEN, token)
            if (userId != null) {
                putString(KEY_USER_ID, userId)
            } else {
                remove(KEY_USER_ID)
            }
            apply()
        }

        notifyAuthStateChanged(true)
    }

    fun clearSession() {
        inMemoryToken = null
        inMemoryUserId = null

        securePrefs?.edit()?.apply {
            remove(KEY_SESSION_TOKEN)
            remove(KEY_USER_ID)
            apply()
        }

        notifyAuthStateChanged(false)
    }

    fun hasActiveSession(): Boolean {
        return !getSessionToken().isNullOrBlank()
    }

    override fun onSessionExpired() {
        Log.w(TAG, "Session expired or invalidated by server (401 Unauthorized)")
        clearSession()
    }

    fun addAuthListener(listener: (Boolean) -> Unit) {
        authListeners.add(listener)
        listener(hasActiveSession())
    }

    fun removeAuthListener(listener: (Boolean) -> Unit) {
        authListeners.remove(listener)
    }

    private fun notifyAuthStateChanged(isAuthenticated: Boolean) {
        authListeners.forEach { listener ->
            try {
                listener(isAuthenticated)
            } catch (e: Throwable) {
                Log.e(TAG, "Error notifying auth listener", e)
            }
        }
    }
}
