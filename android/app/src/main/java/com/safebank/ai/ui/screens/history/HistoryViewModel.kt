package com.safebank.ai.ui.screens.history

import androidx.lifecycle.ViewModel
import com.safebank.ai.ui.model.MockDataProvider
import com.safebank.ai.ui.model.TransactionStatus
import com.safebank.ai.ui.model.TransactionUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class HistoryFilter(val label: String) {
    ALL("All"),
    COMPLETED("Completed"),
    PENDING("Pending"),
    DURESS("Duress Isolated")
}

data class HistoryUiState(
    val searchQuery: String = "",
    val selectedFilter: HistoryFilter = HistoryFilter.ALL,
    val allTransactions: List<TransactionUiModel> = MockDataProvider.sampleTransactions,
    val filteredTransactions: List<TransactionUiModel> = MockDataProvider.sampleTransactions,
    val selectedTransaction: TransactionUiModel? = null
)

class HistoryViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

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
