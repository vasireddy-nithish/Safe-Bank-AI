package com.safebank.ai.ui.screens.security

import androidx.lifecycle.ViewModel
import com.safebank.ai.ui.model.MockDataProvider
import com.safebank.ai.ui.model.RiskTier
import com.safebank.ai.ui.model.SecurityStatusUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SecurityUiState(
    val status: SecurityStatusUiModel = MockDataProvider.sampleSecurityStatus,
    val auditMessage: String? = null,
    val isAuditing: Boolean = false
)

class SecurityViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    fun runHardwareKeyAudit() {
        _uiState.update {
            it.copy(
                auditMessage = "Simulated Check: StrongBox Keymaster hardware attestation will be wired in Stage 5. Zero hardware compromise in simulated baseline."
            )
        }
    }

    fun dismissAuditMessage() {
        _uiState.update { it.copy(auditMessage = null) }
    }

    fun simulateScoreToggle() {
        _uiState.update { current ->
            val nextScore = when {
                current.status.behavioralRiskScore < 30 -> 48 // Moderate
                current.status.behavioralRiskScore < 70 -> 82 // High
                else -> 14 // Reset to Low
            }
            val nextTier = RiskTier.fromScore(nextScore)
            current.copy(
                status = current.status.copy(
                    behavioralRiskScore = nextScore,
                    riskTier = nextTier
                ),
                auditMessage = "Simulated ML score shifted to $nextScore/100 (${nextTier.name}). Note: Coercion defense remains independently disarmed."
            )
        }
    }
}
