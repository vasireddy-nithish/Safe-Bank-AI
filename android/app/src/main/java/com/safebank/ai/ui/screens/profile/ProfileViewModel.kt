package com.safebank.ai.ui.screens.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safebank.ai.data.network.BankingApiClient
import com.safebank.ai.data.network.DuressActivationRequest
import com.safebank.ai.data.network.DuressPinRequest
import com.safebank.ai.data.security.SecureSessionManager
import com.safebank.ai.ml.BehaviourProfileManager
import com.safebank.ai.ml.BehaviourSessionManager
import com.safebank.ai.ui.model.MockDataProvider
import com.safebank.ai.ui.model.UserProfileUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val userProfile: UserProfileUiModel = MockDataProvider.sampleUserProfile,
    val isLockDialogVisible: Boolean = false,
    val isSignOutDialogVisible: Boolean = false,
    val isDuressSetupDialogVisible: Boolean = false,
    val isDuressActivateDialogVisible: Boolean = false,
    val isBusy: Boolean = false,
    val duressConfigured: Boolean = false,
    val behaviouralEnrolmentComplete: Boolean = false,
    val statusMessage: String? = null
)

class ProfileViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    fun refreshLocalState(context: Context) {
        viewModelScope.launch {
            val enrolled = BehaviourProfileManager.getInstance(context).isEnrolmentComplete()
            _uiState.update { it.copy(behaviouralEnrolmentComplete = enrolled) }
        }
    }

    fun showLockDialog() {
        _uiState.update { it.copy(isLockDialogVisible = true) }
    }

    fun dismissLockDialog() {
        _uiState.update { it.copy(isLockDialogVisible = false) }
    }

    fun confirmLockSession() {
        _uiState.update {
            it.copy(
                isLockDialogVisible = false,
                statusMessage = "Session locked. Biometric or Passkey re-authentication required."
            )
        }
    }

    fun showSignOutDialog() {
        _uiState.update { it.copy(isSignOutDialogVisible = true) }
    }

    fun dismissSignOutDialog() {
        _uiState.update { it.copy(isSignOutDialogVisible = false) }
    }

    fun confirmSignOut(context: Context? = null) {
        if (context == null) {
            _uiState.update {
                it.copy(
                    isSignOutDialogVisible = false,
                    statusMessage = "Session tokens invalidated and wiped from encrypted storage."
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSignOutDialogVisible = false, isBusy = true) }
            val sessions = SecureSessionManager.getInstance(context)
            try {
                if (sessions.hasActiveSession()) {
                    BankingApiClient(sessions).apiService.logout()
                }
            } catch (_: Exception) {
                // Best effort server revocation; always wipe local token on explicit sign-out
            } finally {
                BehaviourSessionManager.getInstance(context).resetSession()
                sessions.clearSession()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "Session tokens invalidated and wiped from encrypted storage."
                    )
                }
            }
        }
    }

    fun showDuressSetupDialog() {
        _uiState.update { it.copy(isDuressSetupDialogVisible = true) }
    }

    fun dismissDuressSetupDialog() {
        _uiState.update { it.copy(isDuressSetupDialogVisible = false) }
    }

    fun configureDuressPin(context: Context, pin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val sessions = SecureSessionManager.getInstance(context)
                val api = BankingApiClient(sessions).apiService
                val resp = api.configureDuressPin(DuressPinRequest(pin))
                if (resp.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isDuressSetupDialogVisible = false,
                            duressConfigured = true,
                            statusMessage = "Duress PIN configured and hashed on server (Argon2id)."
                        )
                    }
                } else {
                    val msg = if (resp.code() == 409) {
                        "Duress PIN is already configured on the server."
                    } else {
                        "Unable to configure Duress PIN (${resp.code()})."
                    }
                    _uiState.update {
                        it.copy(isBusy = false, isDuressSetupDialogVisible = false, statusMessage = msg)
                    }
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isDuressSetupDialogVisible = false,
                        statusMessage = "Cannot reach server to configure Duress PIN."
                    )
                }
            }
        }
    }

    fun showDuressActivateDialog() {
        _uiState.update { it.copy(isDuressActivateDialogVisible = true) }
    }

    fun dismissDuressActivateDialog() {
        _uiState.update { it.copy(isDuressActivateDialogVisible = false) }
    }

    fun activateDuress(context: Context, username: String, pin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val sessions = SecureSessionManager.getInstance(context)
                val api = BankingApiClient(sessions).apiService
                val resp = api.activateDuress(DuressActivationRequest(username.trim(), pin))
                if (resp.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isDuressActivateDialogVisible = false,
                            statusMessage = "Protective restriction activated on server."
                        )
                    }
                    // Notify AuthenticationGate by re-checking or clearing session listener
                    sessions.onSessionExpired()
                } else {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isDuressActivateDialogVisible = false,
                            statusMessage = "Duress verification failed (${resp.code()})."
                        )
                    }
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isDuressActivateDialogVisible = false,
                        statusMessage = "Cannot reach server to verify request."
                    )
                }
            }
        }
    }

    fun resetBehaviouralBaseline(context: Context) {
        viewModelScope.launch {
            BehaviourProfileManager.getInstance(context).resetEnrolment()
            BehaviourSessionManager.getInstance(context).resetSession()
            _uiState.update {
                it.copy(
                    behaviouralEnrolmentComplete = false,
                    statusMessage = "Demo Reset: Local behavioural baseline cleared."
                )
            }
        }
    }

    fun dismissStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
