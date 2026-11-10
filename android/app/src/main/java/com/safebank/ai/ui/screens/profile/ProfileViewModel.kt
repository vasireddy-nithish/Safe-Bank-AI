package com.safebank.ai.ui.screens.profile

import androidx.lifecycle.ViewModel
import com.safebank.ai.ui.model.MockDataProvider
import com.safebank.ai.ui.model.UserProfileUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ProfileUiState(
    val userProfile: UserProfileUiModel = MockDataProvider.sampleUserProfile,
    val isLockDialogVisible: Boolean = false,
    val isSignOutDialogVisible: Boolean = false,
    val statusMessage: String? = null
)

class ProfileViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

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

    fun confirmSignOut() {
        _uiState.update {
            it.copy(
                isSignOutDialogVisible = false,
                statusMessage = "Session tokens invalidated and wiped from encrypted storage."
            )
        }
    }

    fun dismissStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
