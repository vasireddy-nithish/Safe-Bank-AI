package com.safebank.ai.ui.screens.transfers

import androidx.lifecycle.ViewModel
import com.safebank.ai.ui.model.AccountUiModel
import com.safebank.ai.ui.model.MockDataProvider
import com.safebank.ai.ui.model.TransactionStatus
import com.safebank.ai.ui.model.TransactionType
import com.safebank.ai.ui.model.TransactionUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class TransfersUiState(
    val account: AccountUiModel = MockDataProvider.sampleAccount,
    val recipient: String = "",
    val amount: String = "",
    val note: String = "",
    val recipientError: String? = null,
    val amountError: String? = null,
    val isReviewing: Boolean = false,
    val isExecuting: Boolean = false,
    val lastTransferResult: TransactionUiModel? = null,
    val riskScore: Int = 14
)

class TransfersViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(TransfersUiState())
    val uiState: StateFlow<TransfersUiState> = _uiState.asStateFlow()

    fun onRecipientChanged(value: String) {
        _uiState.update { it.copy(recipient = value, recipientError = null) }
    }

    fun onAmountChanged(value: String) {
        // Only allow digits and at most one decimal point
        val filtered = value.filter { it.isDigit() || it == '.' }
        _uiState.update { it.copy(amount = filtered, amountError = null) }
    }

    fun onNoteChanged(value: String) {
        _uiState.update { it.copy(note = value) }
    }

    fun onReviewTransfer() {
        val current = _uiState.value
        var hasError = false
        var rError: String? = null
        var aError: String? = null

        if (current.recipient.isBlank()) {
            rError = "Recipient account or name is required."
            hasError = true
        }

        val parsedAmount = current.amount.toDoubleOrNull()
        if (parsedAmount == null || parsedAmount <= 0.0) {
            aError = "Enter a valid positive transfer amount."
            hasError = true
        } else if (parsedAmount > current.account.balance) {
            aError = "Amount exceeds available balance (${current.account.formattedBalance})."
            hasError = true
        }

        if (hasError) {
            _uiState.update { it.copy(recipientError = rError, amountError = aError) }
        } else {
            _uiState.update { it.copy(isReviewing = true, recipientError = null, amountError = null) }
        }
    }

    fun onDismissReview() {
        _uiState.update { it.copy(isReviewing = false) }
    }

    fun onConfirmTransfer() {
        val current = _uiState.value
        val amountNum = current.amount.toDoubleOrNull() ?: 0.0

        val newTransaction = TransactionUiModel(
            id = UUID.randomUUID().toString(),
            reference = "TX-SIM-${System.currentTimeMillis().toString().takeLast(6)}",
            counterparty = current.recipient,
            amount = -amountNum,
            formattedAmount = "-$${String.format(java.util.Locale.US, "%.2f", amountNum)}",
            type = TransactionType.TRANSFER,
            status = TransactionStatus.COMPLETED,
            category = "Mock Transfer",
            timestamp = "Just now",
            note = current.note.ifBlank { "Simulated transfer" }
        )

        _uiState.update {
            it.copy(
                isReviewing = false,
                isExecuting = false,
                lastTransferResult = newTransaction,
                recipient = "",
                amount = "",
                note = ""
            )
        }
    }

    fun onDismissResult() {
        _uiState.update { it.copy(lastTransferResult = null) }
    }
}
