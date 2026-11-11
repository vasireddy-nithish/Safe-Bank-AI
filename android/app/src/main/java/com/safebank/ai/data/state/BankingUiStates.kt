package com.safebank.ai.data.state

import com.safebank.ai.data.model.BankAccount
import com.safebank.ai.data.model.TransactionHistoryItem
import com.safebank.ai.data.model.TransferReceipt

/**
 * UI State representation for Account Listing screens.
 */
sealed class AccountListUiState {
    object Loading : AccountListUiState()
    data class Success(val accounts: List<BankAccount>) : AccountListUiState()
    object Empty : AccountListUiState()
    data class AuthFailure(val message: String = "Session expired. Please log in again.") : AccountListUiState()
    data class NetworkFailure(val message: String, val isRetryable: Boolean = true) : AccountListUiState()
}

/**
 * UI State representation for Transfer Submission screens.
 */
sealed class TransferSubmissionUiState {
    object Idle : TransferSubmissionUiState()
    data class Submitting(val idempotencyKey: String) : TransferSubmissionUiState()
    data class Processing(val idempotencyKey: String, val message: String) : TransferSubmissionUiState()
    data class Completed(val receipt: TransferReceipt) : TransferSubmissionUiState()
    data class Failed(
        val message: String,
        val code: String? = null,
        val isRetryable: Boolean = false
    ) : TransferSubmissionUiState()
    data class ReconciliationRequired(
        val idempotencyKey: String,
        val message: String
    ) : TransferSubmissionUiState()
    data class RestrictedDuress(
        val message: String,
        val receipt: TransferReceipt
    ) : TransferSubmissionUiState()
}

/**
 * UI State representation for Transaction History screen.
 */
sealed class TransactionHistoryUiState {
    object Loading : TransactionHistoryUiState()
    data class Success(val transactions: List<TransactionHistoryItem>) : TransactionHistoryUiState()
    object Empty : TransactionHistoryUiState()
    data class Failure(val message: String, val isRetryable: Boolean = true) : TransactionHistoryUiState()
}
