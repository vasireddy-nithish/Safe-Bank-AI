package com.safebank.ai.data.repository

import com.safebank.ai.data.model.BankAccount
import com.safebank.ai.data.model.BankingResult
import com.safebank.ai.data.model.PendingTransfer
import com.safebank.ai.data.model.ReconcileOutcome
import com.safebank.ai.data.model.RepositoryMode
import com.safebank.ai.data.model.TransferReceipt
import java.math.BigDecimal

/**
 * Repository interface governing banking data access and transaction orchestration.
 */
interface BankingRepository {

    /**
     * Gets the currently active repository operational mode.
     */
    fun getRepositoryMode(): RepositoryMode

    /**
     * Explicitly sets the repository operational mode (DEMO_MOCK vs BACKEND_CONNECTED).
     * Silent mode transitions are strictly forbidden.
     */
    fun setRepositoryMode(mode: RepositoryMode)

    /**
     * Lists user accounts.
     * In BACKEND_CONNECTED mode, failure returns explicit Error, never fallback mock accounts.
     */
    suspend fun getAccounts(): BankingResult<List<BankAccount>>

    /**
     * Executes an internal transfer between user accounts.
     * Uses strict BigDecimal amounts, INR currency, and persistent idempotency key lifecycle.
     */
    suspend fun executeTransfer(
        fromAccountId: String,
        toAccountId: String,
        amount: BigDecimal,
        description: String? = null
    ): BankingResult<TransferReceipt>

    /**
     * Reconciles an in-flight, timed-out, or uncertain transfer using its original idempotency key.
     */
    suspend fun reconcileTransfer(idempotencyKey: String): BankingResult<ReconcileOutcome>

    /**
     * Returns the currently active in-flight or pending transfer intent, if any.
     */
    fun getActivePendingTransfer(): PendingTransfer?

    /**
     * Explicitly clears the active transfer intent.
     */
    fun clearActiveTransfer()
}
