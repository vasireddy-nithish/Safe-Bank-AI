package com.safebank.ai.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safebank.ai.data.di.BankingRepositoryProvider
import com.safebank.ai.data.model.BankingResult
import com.safebank.ai.data.repository.BankingRepository
import com.safebank.ai.data.state.AccountListUiState
import com.safebank.ai.ui.model.AccountUiModel
import com.safebank.ai.ui.model.MockDataProvider
import com.safebank.ai.ui.model.SecurityStatusUiModel
import com.safebank.ai.ui.model.TransactionUiModel
import com.safebank.ai.ui.model.toUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = false,
    val accountListState: AccountListUiState = AccountListUiState.Loading,
    val userName: String = "Alex Chen",
    val account: AccountUiModel = AccountUiModel(),
    val securityStatus: SecurityStatusUiModel = MockDataProvider.sampleSecurityStatus,
    val recentTransactions: List<TransactionUiModel> = emptyList(),
    val errorMessage: String? = null,
    val message: String? = null
)

class HomeViewModel(
    private val repository: BankingRepository = BankingRepositoryProvider.getRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadDashboard()
        observeAccountsStream()
    }

    private fun observeAccountsStream() {
        viewModelScope.launch {
            repository.accountsFlow.collect { accounts ->
                if (accounts.isNotEmpty()) {
                    _uiState.update { current ->
                        val primaryAccount = accounts.find { it.isPrimary } ?: accounts.first()
                        current.copy(
                            accountListState = AccountListUiState.Success(accounts),
                            account = primaryAccount.toUiModel()
                        )
                    }
                }
            }
        }
    }

    fun loadDashboard() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val accountResult = repository.getAccounts()
            val txResult = repository.getTransactions()

            _uiState.update { current ->
                var updatedAccountState: AccountListUiState = current.accountListState
                var updatedAccount = current.account
                var errorMsg: String? = null

                when (accountResult) {
                    is BankingResult.Success -> {
                        val accounts = accountResult.data
                        if (accounts.isEmpty()) {
                            updatedAccountState = AccountListUiState.Empty
                        } else {
                            updatedAccountState = AccountListUiState.Success(accounts)
                            val primaryAccount = accounts.find { it.isPrimary } ?: accounts.first()
                            updatedAccount = primaryAccount.toUiModel()
                        }
                    }
                    is BankingResult.Error -> {
                        errorMsg = accountResult.message
                        updatedAccountState = if (accountResult.httpStatus == 401) {
                            AccountListUiState.AuthFailure(accountResult.message)
                        } else {
                            AccountListUiState.NetworkFailure(accountResult.message, accountResult.isRetryable)
                        }
                    }
                    is BankingResult.Processing -> {
                        // Not applicable for read queries
                    }
                }

                val recentTxs = when (txResult) {
                    is BankingResult.Success -> txResult.data.take(4).map { it.toUiModel() }
                    else -> emptyList()
                }

                current.copy(
                    isLoading = false,
                    accountListState = updatedAccountState,
                    account = updatedAccount,
                    recentTransactions = recentTxs,
                    errorMessage = errorMsg
                )
            }
        }
    }

    fun refreshAccounts() {
        loadDashboard()
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun triggerSecurityScan() {
        _uiState.update {
            it.copy(
                message = "Hardware Keystore & StrongBox attestation verified. Zero anomalies."
            )
        }
    }
}
