package com.safebank.ai.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Real Android BiometricPrompt + Device Credential step-up verification helper.
 * Used when the Risk Engine classifies a session/transaction as Medium Risk (step_up).
 */
object BiometricStepUp {

    private const val ALLOWED_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAvailable(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(ALLOWED_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun show(
        activity: FragmentActivity,
        title: String = "Verify Identity",
        subtitle: String = "Additional verification required for this transaction",
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure("Verification cancelled or unavailable: $errString")
                }

                override fun onAuthenticationFailed() {
                    onFailure("Biometric verification did not match. Please try again.")
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()

        prompt.authenticate(promptInfo)
    }
}

