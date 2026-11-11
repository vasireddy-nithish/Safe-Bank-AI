package com.safebank.ai.data.repository

import com.safebank.ai.data.model.BankAccount
import com.safebank.ai.data.model.BankingResult
import com.safebank.ai.data.model.ReconcileOutcome
import com.safebank.ai.data.model.ReconcileState
import com.safebank.ai.data.model.TransactionHistoryItem
import com.safebank.ai.data.model.TransferReceipt
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Offline Mock data source for DEMO_MOCK mode.
 *
 * Rules:
 * - Operates entirely in memory with simulated Indian Rupee (INR) accounts.
 * - Used exclusively when repository mode is explicitly DEMO_MOCK.
 * - Never invoked silently during BACKEND_CONNECTED network failures.
 */
class MockBankingDataSource {

    private val accounts = mutableListOf(
        BankAccount(
            id = "00000000-0000-4000-a000-000000000010",
            maskedAccountNumber = "•••• 9481",
            accountType = "checking",
            balance = BigDecimal("124500.80"),
            currency = "INR",
            status = "active",
            isPrimary = true
        ),
        BankAccount(
            id = "00000000-0000-4000-a000-000000000011",
            maskedAccountNumber = "•••• 8492",
            accountType = "savings",
            balance = BigDecimal("450000.00"),
            currency = "INR",
            status = "active",
            isPrimary = false
        )
    )

    private val transactions = mutableListOf(
        TransactionHistoryItem(
            id = "tx-mock-001",
            referenceNumber = "REF-MOCK-INIT-01",
            fromAccountId = "00000000-0000-4000-a000-000000000010",
            toAccountId = "00000000-0000-4000-a000-000000000011",
            amount = BigDecimal("12000.00"),
            currency = "INR",
            transactionType = "transfer",
            status = "completed",
            description = "Monthly savings transfer",
            idempotencyKey = "mock-key-001",
            isDuressMasked = false,
            completedAt = java.time.Instant.now().minusSeconds(86400).toString(),
            createdAt = java.time.Instant.now().minusSeconds(86400).toString()
        )
    )

    fun getAccounts(): BankingResult<List<BankAccount>> {
        return BankingResult.Success(accounts.toList())
    }

    fun getTransactions(): BankingResult<List<TransactionHistoryItem>> {
        return BankingResult.Success(transactions.toList())
    }

    fun executeTransfer(
        idempotencyKey: String,
        fromAccountId: String,
        toAccountId: String,
        amount: BigDecimal,
        description: String? = null
    ): BankingResult<TransferReceipt> {
        val scaledAmount = amount.setScale(2, RoundingMode.HALF_UP)
        if (scaledAmount <= BigDecimal.ZERO) {
            return BankingResult.Error(
                message = "Transfer amount must be positive.",
                code = "INVALID_AMOUNT",
                httpStatus = 422
            )
        }

        val source = accounts.find { it.id == fromAccountId }
            ?: return BankingResult.Error(
                message = "Source account not found.",
                code = "ACCOUNT_NOT_FOUND",
                httpStatus = 404
            )

        val destination = accounts.find { it.id == toAccountId }
            ?: return BankingResult.Error(
                message = "Destination account not found.",
                code = "ACCOUNT_NOT_FOUND",
                httpStatus = 404
            )

        if (source.balance < scaledAmount) {
            return BankingResult.Error(
                message = "Insufficient funds in source account.",
                code = "INSUFFICIENT_FUNDS",
                httpStatus = 422
            )
        }

        // Apply in-memory balance update
        val updatedSource = source.copy(balance = source.balance.subtract(scaledAmount))
        val updatedDest = destination.copy(balance = destination.balance.add(scaledAmount))

        accounts[accounts.indexOf(source)] = updatedSource
        accounts[accounts.indexOf(destination)] = updatedDest

        val receipt = TransferReceipt(
            transactionId = "tx-mock-${UUID.randomUUID().toString().take(8)}",
            referenceNumber = "REF-MOCK-${System.currentTimeMillis().toString().takeLast(6)}",
            status = "completed",
            fromAccountId = fromAccountId,
            toAccountId = toAccountId,
            amount = scaledAmount,
            currency = "INR",
            idempotencyKey = idempotencyKey,
            completedAt = java.time.Instant.now().toString(),
            message = description ?: "Simulated mock transfer completed"
        )

        val txItem = TransactionHistoryItem(
            id = receipt.transactionId,
            referenceNumber = receipt.referenceNumber,
            fromAccountId = fromAccountId,
            toAccountId = toAccountId,
            amount = scaledAmount,
            currency = "INR",
            transactionType = "transfer",
            status = "completed",
            description = description,
            idempotencyKey = idempotencyKey,
            isDuressMasked = false,
            completedAt = receipt.completedAt,
            createdAt = receipt.completedAt
        )
        transactions.add(0, txItem)

        return BankingResult.Success(receipt)
    }

    fun reconcileTransfer(idempotencyKey: String): BankingResult<ReconcileOutcome> {
        val outcome = ReconcileOutcome(
            state = ReconcileState.COMPLETED,
            success = true,
            retryable = false,
            idempotencyKey = idempotencyKey,
            transactionId = "tx-mock-rec",
            referenceNumber = "REF-MOCK-REC",
            status = "completed",
            amount = BigDecimal("5000.00"),
            currency = "INR",
            completedAt = java.time.Instant.now().toString(),
            receipt = null,
            detail = "Mock transfer reconciliation completed"
        )
        return BankingResult.Success(outcome)
    }
}
