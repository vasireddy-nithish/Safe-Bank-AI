package com.safebank.ai.ui.screens.home

import androidx.lifecycle.ViewModel
import com.safebank.ai.ui.model.AccountUiModel
import com.safebank.ai.ui.model.MockDataProvider
import com.safebank.ai.ui.model.SecurityStatusUiModel
import com.safebank.ai.ui.model.TransactionUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HomeUiState(
    val isLoading: Boolean = false,
    val userName: String = "Alex",
    val account: AccountUiModel = MockDataProvider.sampleAccount,
    val securityStatus: SecurityStatusUiModel = MockDataProvider.sampleSecurityStatus,
    val recentTransactions: List<TransactionUiModel> = MockDataProvider.sampleTransactions.take(4),
    val message: String? = null
)

class HomeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun triggerSecurityScan() {
        _uiState.update {
            it.copy(
                message = "Hardware Keystore & StrongBox attestation verified. Zero anomalies."
            )
        }
    }
}
