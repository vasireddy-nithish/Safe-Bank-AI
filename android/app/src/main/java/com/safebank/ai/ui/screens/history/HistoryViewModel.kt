package com.safebank.ai.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safebank.ai.data.di.BankingRepositoryProvider
import com.safebank.ai.data.model.BankingResult
import com.safebank.ai.data.repository.BankingRepository
import com.safebank.ai.data.state.TransactionHistoryUiState
import com.safebank.ai.ui.model.TransactionStatus
import com.safebank.ai.ui.model.TransactionUiModel
import com.safebank.ai.ui.model.toUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistoryFilter(val label: String) {
    ALL("All"),
    COMPLETED("Completed"),
    PENDING("Pending"),
    DURESS("Duress Isolated")
}

data class HistoryUiState(
    val isLoading: Boolean = false,
    val historyState: TransactionHistoryUiState = TransactionHistoryUiState.Loading,
    val searchQuery: String = "",
    val selectedFilter: HistoryFilter = HistoryFilter.ALL,
    val allTransactions: List<TransactionUiModel> = emptyList(),
    val filteredTransactions: List<TransactionUiModel> = emptyList(),
    val selectedTransaction: TransactionUiModel? = null,
    val errorMessage: String? = null
)

class HistoryViewModel(
    private val repository: BankingRepository = BankingRepositoryProvider.getRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState(isLoading = true))
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        loadTransactions()
    }

    fun loadTransactions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            when (val result = repository.getTransactions()) {
                is BankingResult.Success -> {
                    val items = result.data
                    val uiModels = items.map { it.toUiModel() }
                    val filtered = filterList(uiModels, _uiState.value.searchQuery, _uiState.value.selectedFilter)
                    val nextHistoryState = if (uiModels.isEmpty()) {
                        TransactionHistoryUiState.Empty
                    } else {
                        TransactionHistoryUiState.Success(items)
                    }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            historyState = nextHistoryState,
                            allTransactions = uiModels,
                            filteredTransactions = filtered,
                            errorMessage = null
                        )
                    }
                }
                is BankingResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            historyState = TransactionHistoryUiState.Failure(result.message, result.isRetryable),
                            allTransactions = emptyList(),
                            filteredTransactions = emptyList(),
                            errorMessage = result.message
                        )
                    }
                }
                is BankingResult.Processing -> {
                    // Not expected for getTransactions
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update {
            val filtered = filterList(it.allTransactions, query, it.selectedFilter)
            it.copy(searchQuery = query, filteredTransactions = filtered)
        }
    }

    fun onFilterSelected(filter: HistoryFilter) {
        _uiState.update {
            val filtered = filterList(it.allTransactions, it.searchQuery, filter)
            it.copy(selectedFilter = filter, filteredTransactions = filtered)
        }
    }

    fun onTransactionSelected(transaction: TransactionUiModel) {
        _uiState.update { it.copy(selectedTransaction = transaction) }
    }

    fun onDismissTransactionDetail() {
        _uiState.update { it.copy(selectedTransaction = null) }
    }

    fun onDismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun filterList(
        list: List<TransactionUiModel>,
        query: String,
        filter: HistoryFilter
    ): List<TransactionUiModel> {
        return list.filter { tx ->
            val matchesFilter = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.COMPLETED -> tx.status == TransactionStatus.COMPLETED
                HistoryFilter.PENDING -> tx.status == TransactionStatus.PENDING
                HistoryFilter.DURESS -> tx.status == TransactionStatus.SIMULATED_DURESS
            }

            val matchesQuery = query.isBlank() ||
                tx.counterparty.contains(query, ignoreCase = true) ||
                tx.reference.contains(query, ignoreCase = true) ||
                tx.category.contains(query, ignoreCase = true) ||
                tx.note.contains(query, ignoreCase = true)

            matchesFilter && matchesQuery
        }
    }
}
