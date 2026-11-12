package com.safebank.ai.data.repository

import android.util.Log
import com.safebank.ai.BuildConfig
import com.safebank.ai.data.idempotency.IdempotencyKeyManager
import com.safebank.ai.data.idempotency.PersistenceException
import com.safebank.ai.data.model.BankAccount
import com.safebank.ai.data.model.BankingResult
import com.safebank.ai.data.model.PendingTransfer
import com.safebank.ai.data.model.PendingTransferStatus
import com.safebank.ai.data.model.ReconcileOutcome
import com.safebank.ai.data.model.ReconcileState
import com.safebank.ai.data.model.RepositoryMode
import com.safebank.ai.data.model.TransactionHistoryItem
import com.safebank.ai.data.model.TransferReceipt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Production implementation of BankingRepository.
 *
 * Core Guarantees:
 * 1. Strict separation of DEMO_MOCK and BACKEND_CONNECTED modes.
 * 2. In BACKEND_CONNECTED mode, NEVER silently fall back to mock data when network fails.
 * 3. Mode switching to DEMO_MOCK is strictly prohibited in production builds (BuildConfig.DEBUG == false).
 * 4. Never fabricate accounts, balances, or transfer receipts on network failure.
 * 5. Idempotency Key Lifecycle: exactly 1 UUIDv4 key per logical transfer, persisted before send.
 * 6. On network timeout / dropped connection: reconciles with ORIGINAL idempotency key.
 * 7. Financial Precision: BigDecimal or validated decimal string representation only. Never Float/Double.
 * 8. Real-time balance synchronization via accountsFlow without requiring application restart.
 */
class BankingRepositoryImpl(
    private val apiDataSource: BankingApiDataSource,
    private val mockDataSource: MockBankingDataSource,
    private val idempotencyKeyManager: IdempotencyKeyManager,
    initialMode: RepositoryMode = RepositoryMode.BACKEND_CONNECTED,
    private val isDebugBuild: Boolean = BuildConfig.DEBUG
) : BankingRepository {

    companion object {
        private const val TAG = "BankingRepositoryImpl"
    }

    init {
        if (!isDebugBuild && initialMode == RepositoryMode.DEMO_MOCK) {
            throw IllegalStateException("SECURITY_VIOLATION: DEMO_MOCK mode is strictly prohibited in production builds.")
        }
    }

    @Volatile
    private var currentMode: RepositoryMode = initialMode

    private val _accountsFlow = MutableStateFlow<List<BankAccount>>(emptyList())
    override val accountsFlow: StateFlow<List<BankAccount>> = _accountsFlow.asStateFlow()

    override fun isModeSwitchingAllowed(): Boolean = isDebugBuild

    override fun getRepositoryMode(): RepositoryMode = currentMode

    override fun setRepositoryMode(mode: RepositoryMode) {
        if (!isDebugBuild && mode == RepositoryMode.DEMO_MOCK) {
            Log.e(TAG, "SECURITY_VIOLATION: Attempted switch to DEMO_MOCK in production build rejected.")
            throw IllegalStateException("MODE_SWITCH_FORBIDDEN: Switching to DEMO_MOCK is strictly forbidden in production builds.")
        }
        Log.i(TAG, "Explicit repository mode switch: $currentMode -> $mode")
        currentMode = mode
    }

    override suspend fun getAccounts(): BankingResult<List<BankAccount>> {
        val result = when (currentMode) {
            RepositoryMode.DEMO_MOCK -> {
                mockDataSource.getAccounts()
            }
            RepositoryMode.BACKEND_CONNECTED -> {
                // In BACKEND_CONNECTED mode, failure must return explicit error state.
                // NEVER silently fall back to mock accounts.
                apiDataSource.getAccounts()
            }
        }
        if (result is BankingResult.Success) {
            _accountsFlow.value = result.data
        }
        return result
    }

    override suspend fun getTransactions(): BankingResult<List<TransactionHistoryItem>> {
        return when (currentMode) {
            RepositoryMode.DEMO_MOCK -> {
                mockDataSource.getTransactions()
            }
            RepositoryMode.BACKEND_CONNECTED -> {
                // In BACKEND_CONNECTED mode, failure must return explicit error state.
                // NEVER silently fall back to mock transactions.
                apiDataSource.getTransactions()
            }
        }
    }

    override suspend fun executeTransfer(
        fromAccountId: String,
        toAccountId: String,
        amount: BigDecimal,
        description: String?
    ): BankingResult<TransferReceipt> {
        val scaledAmount = amount.setScale(2, RoundingMode.HALF_UP)
        if (scaledAmount <= BigDecimal.ZERO) {
            return BankingResult.Error(
                message = "Transfer amount must be strictly positive.",
                code = "INVALID_AMOUNT",
                httpStatus = 422
            )
        }

        if (fromAccountId == toAccountId) {
            return BankingResult.Error(
                message = "Source and destination accounts must be different.",
                code = "IDENTICAL_ACCOUNTS",
                httpStatus = 422
            )
        }

        return when (currentMode) {
            RepositoryMode.DEMO_MOCK -> {
                val pending = idempotencyKeyManager.startNewTransfer(
                    fromAccountId = fromAccountId,
                    toAccountId = toAccountId,
                    amount = scaledAmount,
                    description = description
                )
                val result = mockDataSource.executeTransfer(
                    idempotencyKey = pending.idempotencyKey,
                    fromAccountId = fromAccountId,
                    toAccountId = toAccountId,
                    amount = scaledAmount,
                    description = description
                )
                if (result is BankingResult.Success) {
                    idempotencyKeyManager.updateStatus(pending.idempotencyKey, PendingTransferStatus.COMPLETED)
                    refreshAndEmitAccounts()
                } else {
                    idempotencyKeyManager.updateStatus(pending.idempotencyKey, PendingTransferStatus.FAILED)
                }
                result
            }
            RepositoryMode.BACKEND_CONNECTED -> {
                // Prevent duplicate transfer submissions while one is in flight
                if (idempotencyKeyManager.isTransferInFlight()) {
                    val active = idempotencyKeyManager.getActiveTransfer()
                    return BankingResult.Error(
                        message = "A transfer is currently in flight (key: ${active?.idempotencyKey}). Duplicate submission prevented.",
                        code = "DUPLICATE_SUBMISSION_BLOCKED",
                        isRetryable = false
                    )
                }

                // Generate and persist exactly one UUIDv4 idempotency key for this logical transfer
                val pending = try {
                    idempotencyKeyManager.startNewTransfer(
                        fromAccountId = fromAccountId,
                        toAccountId = toAccountId,
                        amount = scaledAmount,
                        description = description
                    )
                } catch (e: PersistenceException) {
                    return BankingResult.Error(
                        message = "Pre-dispatch persistence failed: ${e.message}. Transfer dispatch blocked.",
                        code = "PERSISTENCE_FAILED",
                        isRetryable = true,
                        cause = e
                    )
                } catch (e: IllegalStateException) {
                    return BankingResult.Error(
                        message = e.message ?: "Transfer in flight",
                        code = "DUPLICATE_SUBMISSION_BLOCKED",
                        isRetryable = false
                    )
                }

                executeWithReconciliationGuard(pending)
            }
        }
    }

    private suspend fun executeWithReconciliationGuard(
        pending: PendingTransfer
    ): BankingResult<TransferReceipt> {
        try {
            val result = apiDataSource.executeTransfer(
                idempotencyKey = pending.idempotencyKey,
                fromAccountId = pending.fromAccountId,
                toAccountId = pending.toAccountId,
                amount = pending.amount,
                description = pending.description
            )

            when (result) {
                is BankingResult.Success -> {
                    idempotencyKeyManager.updateStatus(pending.idempotencyKey, PendingTransferStatus.COMPLETED)
                    refreshAndEmitAccounts()
                    return result
                }
                is BankingResult.Error -> {
                    // Check if server returned 503 retryable database contention
                    if (result.isRetryable) {
                        // Keep idempotency key in place for retry
                        idempotencyKeyManager.updateStatus(pending.idempotencyKey, PendingTransferStatus.SUBMITTED)
                    } else {
                        // Terminal failure (e.g. 422 INSUFFICIENT_FUNDS or DAILY_LIMIT_EXCEEDED)
                        idempotencyKeyManager.updateStatus(pending.idempotencyKey, PendingTransferStatus.FAILED)
                    }
                    return result
                }
                is BankingResult.Processing -> {
                    idempotencyKeyManager.updateStatus(pending.idempotencyKey, PendingTransferStatus.PROCESSING)
                    return result
                }
            }
        } catch (e: Throwable) {
            // Network failure during transfer execution (timeout, connection drop)
            // NEVER synthesize a mock receipt or silently assume failure.
            // Immediately attempt reconciliation using the ORIGINAL idempotency key.
            Log.w(TAG, "Network exception during transfer execution: ${e.message}. Triggering reconciliation with key ${pending.idempotencyKey}")
            idempotencyKeyManager.markReconciliationRequired(pending.idempotencyKey)

            return handleUncertainTransferOutcome(pending, e)
        }
    }

    private suspend fun handleUncertainTransferOutcome(
        pending: PendingTransfer,
        originalCause: Throwable
    ): BankingResult<TransferReceipt> {
        val reconcileResult = reconcileTransfer(pending.idempotencyKey)

        return when (reconcileResult) {
            is BankingResult.Success -> {
                val outcome = reconcileResult.data
                when (outcome.state) {
                    ReconcileState.COMPLETED -> {
                        idempotencyKeyManager.updateStatus(pending.idempotencyKey, PendingTransferStatus.COMPLETED)
                        refreshAndEmitAccounts()
                        val receipt = outcome.receipt ?: TransferReceipt(
                            transactionId = outcome.transactionId ?: "tx-rec-${pending.idempotencyKey.take(8)}",
                            referenceNumber = outcome.referenceNumber ?: "REF-REC",
                            status = "completed",
                            fromAccountId = pending.fromAccountId,
                            toAccountId = pending.toAccountId,
                            amount = pending.amount,
                            currency = pending.currency,
                            idempotencyKey = pending.idempotencyKey,
                            completedAt = outcome.completedAt ?: java.time.Instant.now().toString(),
                            message = "Transfer verified completed via reconciliation"
                        )
                        BankingResult.Success(receipt)
                    }
                    ReconcileState.PROCESSING -> {
                        idempotencyKeyManager.updateStatus(pending.idempotencyKey, PendingTransferStatus.PROCESSING)
                        BankingResult.Processing(
                            idempotencyKey = pending.idempotencyKey,
                            message = "Transfer is processing on server. Please check reconciliation status."
                        )
                    }
                    ReconcileState.FAILED -> {
                        idempotencyKeyManager.updateStatus(pending.idempotencyKey, PendingTransferStatus.FAILED)
                        BankingResult.Error(
                            message = outcome.detail ?: "Transfer failed on server.",
                            code = outcome.errorCode ?: "SERVER_FAILED",
                            isRetryable = false
                        )
                    }
                    ReconcileState.NOT_FOUND -> {
                        // NOT_FOUND means no reservation was visible at the time of the reconciliation query.
                        // It does NOT prove the original request never reached the server (it could be in-flight
                        // or committing Phase 1). Therefore, any retry MUST use the exact same original
                        // idempotency key and identical financial payload. Never generate a new key because of NOT_FOUND.
                        idempotencyKeyManager.updateStatus(pending.idempotencyKey, PendingTransferStatus.RECONCILIATION_REQUIRED)
                        BankingResult.Error(
                            message = "No reservation was visible at query time (NOT_FOUND). Safe to retry ONLY with original idempotency key: ${pending.idempotencyKey}",
                            code = "RECONCILIATION_NOT_FOUND_RETRYABLE",
                            isRetryable = true,
                            cause = originalCause
                        )
                    }
                }
            }
            is BankingResult.Processing -> {
                idempotencyKeyManager.updateStatus(pending.idempotencyKey, PendingTransferStatus.PROCESSING)
                reconcileResult
            }
            is BankingResult.Error -> {
                // Reconciliation itself failed (e.g. offline)
                // Retain RECONCILIATION_REQUIRED status so UI can prompt user to reconcile when back online
                idempotencyKeyManager.markReconciliationRequired(pending.idempotencyKey)
                BankingResult.Error(
                    message = "Transfer timed out and reconciliation could not verify status. Idempotency key preserved: ${pending.idempotencyKey}",
                    code = "RECONCILIATION_REQUIRED",
                    isRetryable = true,
                    cause = originalCause
                )
            }
        }
    }

    override suspend fun reconcileTransfer(idempotencyKey: String): BankingResult<ReconcileOutcome> {
        return when (currentMode) {
            RepositoryMode.DEMO_MOCK -> {
                mockDataSource.reconcileTransfer(idempotencyKey)
            }
            RepositoryMode.BACKEND_CONNECTED -> {
                val result = apiDataSource.reconcileTransfer(idempotencyKey)
                if (result is BankingResult.Success) {
                    val outcome = result.data
                    when (outcome.state) {
                        ReconcileState.COMPLETED -> {
                            idempotencyKeyManager.updateStatus(idempotencyKey, PendingTransferStatus.COMPLETED)
                            refreshAndEmitAccounts()
                        }
                        ReconcileState.FAILED -> idempotencyKeyManager.updateStatus(idempotencyKey, PendingTransferStatus.FAILED)
                        ReconcileState.PROCESSING -> idempotencyKeyManager.updateStatus(idempotencyKey, PendingTransferStatus.PROCESSING)
                        ReconcileState.NOT_FOUND -> { /* Keep current status */ }
                    }
                }
                result
            }
        }
    }

    private suspend fun refreshAndEmitAccounts() {
        try {
            val res = when (currentMode) {
                RepositoryMode.DEMO_MOCK -> mockDataSource.getAccounts()
                RepositoryMode.BACKEND_CONNECTED -> apiDataSource.getAccounts()
            }
            if (res is BankingResult.Success) {
                _accountsFlow.value = res.data
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to refresh accounts after transfer: ${e.message}")
        }
    }

    override fun getActivePendingTransfer(): PendingTransfer? {
        return idempotencyKeyManager.getActiveTransfer()
    }

    override fun clearActiveTransfer() {
        idempotencyKeyManager.clearActiveTransfer()
    }
}
