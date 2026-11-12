package com.safebank.ai.ui.screens.register

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safebank.ai.data.network.BankingApiClient
import com.safebank.ai.data.network.RegisterRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RegisterUiState(
    val fullName: String = "",
    val username: String = "",
    val enrolmentCode: String = "",
    val duressPin: String = "",
    val consentBiometric: Boolean = false,
    val consentDemo: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    // Field-level errors
    val nameError: String? = null,
    val usernameError: String? = null,
    val enrolmentError: String? = null,
    val duressPinError: String? = null
)

class RegisterViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    fun onFullNameChange(value: String) {
        val suggestedUsername = value.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .take(20)
        _uiState.update {
            it.copy(
                fullName = value,
                username = if (it.username.isEmpty() || it.username == deriveSuggestion(it.fullName))
                    suggestedUsername else it.username,
                nameError = null
            )
        }
    }

    private fun deriveSuggestion(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9]"), "_").take(20)

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, usernameError = null) }
    }

    fun onEnrolmentCodeChange(value: String) {
        _uiState.update { it.copy(enrolmentCode = value, enrolmentError = null) }
    }

    // NOTE: duress PIN is never logged — only stored in memory during this screen's lifetime
    fun onDuresspinChange(value: String) {
        _uiState.update { it.copy(duressPin = value, duressPinError = null) }
    }

    fun onConsentBiometricChange(value: Boolean) {
        _uiState.update { it.copy(consentBiometric = value) }
    }

    fun onConsentDemoChange(value: Boolean) {
        _uiState.update { it.copy(consentDemo = value) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun register(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (!validate(state)) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                // NOTE: We use a placeholder API client here — no session needed for registration.
                // The backend register endpoint (POST /api/v1/auth/register) will be added by backend agent.
                val api = BankingApiClient.forRegistration().apiService
                val request = RegisterRequest(
                    full_name = state.fullName.trim(),
                    username = state.username.trim(),
                    enrolment_code = state.enrolmentCode.trim(),
                    duress_pin_opt_in = state.duressPin.isNotBlank()
                )
                val response = api.register(request)
                when {
                    response.isSuccessful -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                successMessage = "Account registered! Please sign in."
                            )
                        }
                        onSuccess()
                    }
                    response.code() == 404 -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = "Registration Pending — Backend endpoint in progress. (404)"
                            )
                        }
                    }
                    else -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = "Registration failed (${response.code()}). Please retry."
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Cannot reach the server. Check your connection."
                    )
                }
            }
        }
    }

    private fun validate(state: RegisterUiState): Boolean {
        var valid = true
        var nameError: String? = null
        var usernameError: String? = null
        var enrolmentError: String? = null
        var duressPinError: String? = null

        if (state.fullName.isBlank()) {
            nameError = "Full name is required."
            valid = false
        }
        if (state.username.isBlank() || !state.username.matches(Regex("[a-zA-Z0-9_]+"))) {
            usernameError = "Username must be alphanumeric (letters, digits, underscores)."
            valid = false
        }
        if (state.enrolmentCode.trim() != "SAFEBANK2024") {
            enrolmentError = "Invalid enrolment code."
            valid = false
        }
        if (state.duressPin.isNotBlank() && (state.duressPin.length < 6 || state.duressPin.length > 12)) {
            duressPinError = "Duress PIN must be 6–12 digits."
            valid = false
        }
        if (!valid) {
            _uiState.update {
                it.copy(
                    nameError = nameError,
                    usernameError = usernameError,
                    enrolmentError = enrolmentError,
                    duressPinError = duressPinError
                )
            }
        }
        return valid
    }
}
