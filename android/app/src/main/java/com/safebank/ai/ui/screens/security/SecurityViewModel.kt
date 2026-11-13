package com.safebank.ai.ui.screens.security

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safebank.ai.data.network.BankingApiClient
import com.safebank.ai.data.network.DemoScenarioRequest
import com.safebank.ai.data.network.RiskSubmissionRequest
import com.safebank.ai.data.network.SecurityEventDto
import com.safebank.ai.data.security.SecureSessionManager
import com.safebank.ai.ml.BehaviourSessionManager
import com.safebank.ai.ui.model.MockDataProvider
import com.safebank.ai.ui.model.RiskTier
import com.safebank.ai.ui.model.SecurityStatusUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SecurityUiState(
    val status: SecurityStatusUiModel = MockDataProvider.sampleSecurityStatus,
    val auditMessage: String? = null,
    val isAuditing: Boolean = false,
    val securityEvents: List<SecurityEventDto> = emptyList(),
    val isLiveModelActive: Boolean = false,
    val serverRiskScore: Int? = null
)

class SecurityViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    fun loadLiveSecurityData(context: Context) {
        viewModelScope.launch {
            val sessions = SecureSessionManager.getInstance(context)
            if (!sessions.hasActiveSession()) return@launch
            val api = BankingApiClient(sessions).apiService
            try {
                val eventsResp = api.getSecurityEvents()
                if (eventsResp.isSuccessful) {
                    _uiState.update { it.copy(securityEvents = eventsResp.body().orEmpty()) }
                }
                val statusResp = api.securityStatus()
                if (statusResp.isSuccessful) {
                    val body = statusResp.body()
                    if (body != null) {
                        val mappedScore = body.server_score ?: 30
                        _uiState.update {
                            it.copy(
                                serverRiskScore = body.server_score,
                                status = it.status.copy(
                                    behavioralRiskScore = mappedScore,
                                    riskTier = RiskTier.fromScore(mappedScore),
                                    coercionDefenseArmed = body.restricted
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(serverRiskScore = null) }
                // Keep UI usable when offline
            }
        }
    }

    fun runOnDeviceBehaviorAssessment(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuditing = true) }
            val manager = BehaviourSessionManager.getInstance(context)
            val result = manager.classifyWithBaseline()
            val tier = RiskTier.fromScore(result.riskScore)
            var serverNote = if (result.isFromLiveModel) "Local TFLite assessment" else "Insufficient data or inference unavailable; verification required"

            try {
                val sessions = SecureSessionManager.getInstance(context)
                if (sessions.hasActiveSession()) {
                    val resp = BankingApiClient(sessions).apiService.submitRiskScore(
                        RiskSubmissionRequest(
                            genuine_probability = result.genuineProbability,
                            uncertain_probability = result.uncertainProbability,
                            anomaly_probability = result.anomalyProbability,
                            risk_score = result.riskScore,
                            is_from_live_model = result.isFromLiveModel,
                            session_data_points = manager.getDataPointCount()
                        )
                    )
                    if (resp.isSuccessful) {
                        val decision = resp.body()
                        serverNote = "Server decision: ${decision?.action ?: "recorded"} (${decision?.risk_level ?: "ok"})"
                    }
                }
            } catch (_: Exception) {
                serverNote = "On-device assessment (offline)"
            }

            _uiState.update {
                it.copy(
                    isAuditing = false,
                    isLiveModelActive = result.isFromLiveModel,
                    status = it.status.copy(
                        behavioralRiskScore = result.riskScore,
                        riskTier = tier
                    ),
                    auditMessage = "On-device ML (${result.inferenceStatus}): score ${result.riskScore}/100 (${tier.name}). $serverNote"
                )
            }
            loadLiveSecurityData(context)
        }
    }

    fun triggerBackendDemoScenario(context: Context, scenario: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuditing = true) }
            try {
                val sessions = SecureSessionManager.getInstance(context)
                val api = BankingApiClient(sessions).apiService
                val resp = api.triggerDemoScenario(DemoScenarioRequest(scenario))
                if (resp.isSuccessful) {
                    val body = resp.body()
                    val score = if (scenario == "suspicious") 50 else 90
                    _uiState.update {
                        it.copy(
                            isAuditing = false,
                            status = it.status.copy(
                                behavioralRiskScore = score,
                                riskTier = RiskTier.fromScore(score)
                            ),
                            auditMessage = "Demo scenario '$scenario' triggered -> server action: ${body?.action ?: "recorded"}"
                        )
                    }
                    loadLiveSecurityData(context)
                } else {
                    _uiState.update {
                        it.copy(
                            isAuditing = false,
                            auditMessage = "Demo scenario unavailable (${resp.code()}). Ensure ENABLE_DEV_SESSION_ENDPOINT=true on backend."
                        )
                    }
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isAuditing = false, auditMessage = "Cannot reach backend for demo scenario.")
                }
            }
        }
    }

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
