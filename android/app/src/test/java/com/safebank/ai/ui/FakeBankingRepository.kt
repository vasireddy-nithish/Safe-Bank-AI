package com.safebank.ai.ui

import com.safebank.ai.data.model.BankAccount
import com.safebank.ai.data.model.BankingResult
import com.safebank.ai.data.model.PendingTransfer
import com.safebank.ai.data.model.ReconcileOutcome
import com.safebank.ai.data.model.ReconcileState
import com.safebank.ai.data.model.RepositoryMode
import com.safebank.ai.data.model.TransactionHistoryItem
import com.safebank.ai.data.model.TransferReceipt
import com.safebank.ai.data.repository.BankingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.util.UUID

class FakeBankingRepository(
    initialMode: RepositoryMode = RepositoryMode.DEMO_MOCK,
    var isDebugBuild: Boolean = true
) : BankingRepository {

    var mode: RepositoryMode = initialMode
    var accountsResult: BankingResult<List<BankAccount>> = BankingResult.Success(
        listOf(
            BankAccount(
                id = "acc-01",
                maskedAccountNumber = "•••• 9481",
                accountType = "checking",
                balance = BigDecimal("124500.80"),
                currency = "INR",
                status = "active",
                isPrimary = true
            ),
            BankAccount(
                id = "acc-02",
                maskedAccountNumber = "•••• 8492",
                accountType = "savings",
                balance = BigDecimal("450000.00"),
                currency = "INR",
                status = "active",
                isPrimary = false
            )
        )
    )
        set(value) {
            field = value
            if (value is BankingResult.Success) {
                _accountsFlow.value = value.data
            } else {
                _accountsFlow.value = emptyList()
            }
        }

    private val _accountsFlow = MutableStateFlow<List<BankAccount>>(emptyList())
    override val accountsFlow: StateFlow<List<BankAccount>> = _accountsFlow.asStateFlow()

    override fun isModeSwitchingAllowed(): Boolean = isDebugBuild

    fun emitAccounts(accounts: List<BankAccount>) {
        accountsResult = BankingResult.Success(accounts)
        _accountsFlow.value = accounts
    }

    var transactionsResult: BankingResult<List<TransactionHistoryItem>> = BankingResult.Success(
        listOf(
            TransactionHistoryItem(
                id = "tx-001",
                referenceNumber = "REF-INIT-01",
                fromAccountId = "acc-01",
                toAccountId = "acc-02",
                amount = BigDecimal("1500.00"),
                currency = "INR",
                transactionType = "transfer",
                status = "completed",
                description = "Legit transfer",
                idempotencyKey = "key-001",
                isDuressMasked = false,
                completedAt = "2026-11-10T10:00:00Z",
                createdAt = "2026-11-10T10:00:00Z"
            ),
            TransactionHistoryItem(
                id = "tx-002",
                referenceNumber = "REF-DURESS-02",
                fromAccountId = "acc-01",
                toAccountId = "acc-02",
                amount = BigDecimal("25000.00"),
                currency = "INR",
                transactionType = "transfer",
                status = "simulated_duress",
                description = "Duress transfer under threat",
                idempotencyKey = "key-002",
                isDuressMasked = true,
                completedAt = "2026-11-10T11:00:00Z",
                createdAt = "2026-11-10T11:00:00Z"
            )
        )
    )

    var transferOutcome: BankingResult<TransferReceipt>? = null
    var reconcileOutcome: BankingResult<ReconcileOutcome>? = null
    var activePending: PendingTransfer? = null

    override fun getRepositoryMode(): RepositoryMode = mode

    override fun setRepositoryMode(newMode: RepositoryMode) {
        if (!isDebugBuild && newMode == RepositoryMode.DEMO_MOCK) {
            throw IllegalStateException("MODE_SWITCH_FORBIDDEN: Switching to DEMO_MOCK is strictly forbidden in production builds.")
        }
        mode = newMode
    }

    override suspend fun getAccounts(): BankingResult<List<BankAccount>> {
        if (accountsResult is BankingResult.Success) {
            _accountsFlow.value = (accountsResult as BankingResult.Success).data
        }
        return accountsResult
    }

    override suspend fun getTransactions(): BankingResult<List<TransactionHistoryItem>> = transactionsResult

    override suspend fun executeTransfer(
        fromAccountId: String,
        toAccountId: String,
        amount: BigDecimal,
        description: String?
    ): BankingResult<TransferReceipt> {
        val isDuress = description?.contains("duress", ignoreCase = true) == true
        if (transferOutcome == null && accountsResult is BankingResult.Success && !isDuress) {
            val list = (accountsResult as BankingResult.Success).data.toMutableList()
            val src = list.find { it.id == fromAccountId }
            val dst = list.find { it.id == toAccountId }
            if (src != null && dst != null) {
                list[list.indexOf(src)] = src.copy(balance = src.balance.subtract(amount))
                list[list.indexOf(dst)] = dst.copy(balance = dst.balance.add(amount))
                accountsResult = BankingResult.Success(list)
                _accountsFlow.value = list
            }
        }

        return transferOutcome ?: BankingResult.Success(
            TransferReceipt(
                transactionId = "tx-test-${UUID.randomUUID().toString().take(6)}",
                referenceNumber = "REF-TEST-001",
                status = if (isDuress) "simulated_duress" else "completed",
                fromAccountId = fromAccountId,
                toAccountId = toAccountId,
                amount = amount,
                currency = "INR",
                idempotencyKey = UUID.randomUUID().toString(),
                completedAt = "2026-11-11T00:00:00Z",
                message = description ?: "Transfer completed"
            )
        )
    }

    override suspend fun reconcileTransfer(idempotencyKey: String): BankingResult<ReconcileOutcome> {
        return reconcileOutcome ?: BankingResult.Success(
            ReconcileOutcome(
                state = ReconcileState.COMPLETED,
                success = true,
                retryable = false,
                idempotencyKey = idempotencyKey,
                transactionId = "tx-rec-001",
                referenceNumber = "REF-REC-001",
                status = "completed",
                amount = BigDecimal("1000.00"),
                currency = "INR",
                completedAt = "2026-11-11T00:00:00Z"
            )
        )
    }

    override fun getActivePendingTransfer(): PendingTransfer? = activePending
    override fun clearActiveTransfer() { activePending = null }
}
