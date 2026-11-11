package com.safebank.ai.ui

import com.safebank.ai.data.di.BankingRepositoryProvider
import com.safebank.ai.data.model.BankAccount
import com.safebank.ai.data.model.BankingResult
import com.safebank.ai.data.model.PendingTransfer
import com.safebank.ai.data.model.ReconcileOutcome
import com.safebank.ai.data.model.ReconcileState
import com.safebank.ai.data.model.RepositoryMode
import com.safebank.ai.data.model.TransactionHistoryItem
import com.safebank.ai.data.model.TransferReceipt
import com.safebank.ai.data.state.AccountListUiState
import com.safebank.ai.data.state.TransactionHistoryUiState
import com.safebank.ai.data.state.TransferSubmissionUiState
import com.safebank.ai.ui.model.TransactionStatus
import com.safebank.ai.ui.screens.history.HistoryFilter
import com.safebank.ai.ui.screens.history.HistoryViewModel
import com.safebank.ai.ui.screens.home.HomeViewModel
import com.safebank.ai.ui.screens.transfers.TransfersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class BankingWorkflowIntegrationTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        BankingRepositoryProvider.resetForTesting()
    }

    @After
    fun tearDown() {
        BankingRepositoryProvider.resetForTesting()
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. Dependency Injection & Isolation Tests
    // =========================================================================

    @Test
    fun repositoryProvider_supportsTestInjectionAndIsolation() {
        val fakeRepo1 = FakeBankingRepository(initialMode = RepositoryMode.DEMO_MOCK)
        BankingRepositoryProvider.setRepositoryForTesting(fakeRepo1)
        assertEquals(fakeRepo1, BankingRepositoryProvider.getRepository())

        // Reset clears test injection
        BankingRepositoryProvider.resetForTesting()
        val defaultRepo = BankingRepositoryProvider.getRepository()
        assertNotNull(defaultRepo)
        assertFalse(defaultRepo === fakeRepo1)
    }

    // =========================================================================
    // 2. Home Screen & ViewModel Integration Tests
    // =========================================================================

    @Test
    fun homeViewModel_loadsAccountsAndPreservesErrorStateWithoutSilentMockFallback() {
        val fakeRepo = FakeBankingRepository(initialMode = RepositoryMode.BACKEND_CONNECTED)
        // Simulate backend error
        fakeRepo.accountsResult = BankingResult.Error(
            message = "Unable to connect to SafeBank Core API.",
            code = "NETWORK_ERROR",
            isRetryable = true,
            httpStatus = 503
        )

        val vm = HomeViewModel(fakeRepo)
        // Must reflect NetworkFailure, NEVER fall back to mock data
        val state = vm.uiState.value
        assertTrue(state.accountListState is AccountListUiState.NetworkFailure)
        assertEquals("Unable to connect to SafeBank Core API.", state.errorMessage)

        // Now simulate successful retry
        fakeRepo.accountsResult = BankingResult.Success(
            listOf(
                BankAccount(
                    id = "acc-live-01",
                    maskedAccountNumber = "•••• 9481",
                    accountType = "checking",
                    balance = BigDecimal("124500.80"),
                    currency = "INR",
                    isPrimary = true
                )
            )
        )
        vm.refreshAccounts()

        val refreshedState = vm.uiState.value
        assertTrue(refreshedState.accountListState is AccountListUiState.Success)
        assertNull(refreshedState.errorMessage)
        assertEquals("₹1,24,500.80", refreshedState.account.formattedBalance)
    }

    // =========================================================================
    // 3. Transfers Screen & ViewModel State Machine Tests
    // =========================================================================

    @Test
    fun transfersViewModel_fullStateMachineAndDuplicateGuard() {
        val fakeRepo = FakeBankingRepository(initialMode = RepositoryMode.BACKEND_CONNECTED)
        val vm = TransfersViewModel(fakeRepo)

        // 1. Validate Form Rules
        vm.onAmountChanged("0.00")
        vm.onReviewTransfer()
        assertNotNull(vm.uiState.value.amountError)

        vm.onAmountChanged("99999999.00") // Exceeds balance
        vm.onReviewTransfer()
        assertNotNull(vm.uiState.value.amountError)

        // Identical accounts
        val src = vm.uiState.value.sourceAccount!!
        vm.onDestinationAccountSelected(src)
        vm.onAmountChanged("500.00")
        vm.onReviewTransfer()
        assertNotNull(vm.uiState.value.accountError)

        // Valid inputs
        val dest = vm.uiState.value.availableAccounts.first { it.id != src.id }
        vm.onDestinationAccountSelected(dest)
        vm.onAmountChanged("2500.00")
        vm.onNoteChanged("Monthly deposit")
        vm.onReviewTransfer()
        assertNull(vm.uiState.value.amountError)
        assertNull(vm.uiState.value.accountError)
        assertTrue(vm.uiState.value.isReviewing)

        // 2. Successful Execution
        fakeRepo.transferOutcome = BankingResult.Success(
            TransferReceipt(
                transactionId = "tx-success-001",
                referenceNumber = "REF-LIVE-001",
                status = "completed",
                fromAccountId = src.id,
                toAccountId = dest.id,
                amount = BigDecimal("2500.00"),
                currency = "INR",
                idempotencyKey = "key-live-001",
                completedAt = "2026-11-11T12:00:00Z"
            )
        )
        vm.onConfirmTransfer()

        val submission = vm.uiState.value.submissionState
        assertTrue(submission is TransferSubmissionUiState.Completed)
        val receipt = (submission as TransferSubmissionUiState.Completed).receipt
        assertEquals("REF-LIVE-001", receipt.referenceNumber)
        assertEquals("₹2,500.00", receipt.formattedAmount)

        vm.onDismissSubmissionResult()
        assertTrue(vm.uiState.value.submissionState is TransferSubmissionUiState.Idle)
    }

    @Test
    fun transfersViewModel_reconciliationRequiredAndRecovery() {
        val fakeRepo = FakeBankingRepository(initialMode = RepositoryMode.BACKEND_CONNECTED)
        fakeRepo.activePending = PendingTransfer(
            idempotencyKey = "key-reconcile-target",
            fromAccountId = "acc-01",
            toAccountId = "acc-02",
            amount = BigDecimal("5000.00"),
            currency = "INR"
        )
        fakeRepo.transferOutcome = BankingResult.Error(
            message = "Network timeout: transfer status uncertain. Reconciliation required.",
            code = "RECONCILIATION_REQUIRED",
            isRetryable = true
        )

        val vm = TransfersViewModel(fakeRepo)
        vm.onAmountChanged("5000.00")
        vm.onReviewTransfer()
        vm.onConfirmTransfer()

        // Should enter ReconciliationRequired state
        val subState = vm.uiState.value.submissionState
        assertTrue(subState is TransferSubmissionUiState.ReconciliationRequired)
        val reqKey = (subState as TransferSubmissionUiState.ReconciliationRequired).idempotencyKey
        assertEquals("key-reconcile-target", reqKey)

        // Execute Reconcile Action with original key
        fakeRepo.reconcileOutcome = BankingResult.Success(
            ReconcileOutcome(
                state = ReconcileState.COMPLETED,
                success = true,
                retryable = false,
                idempotencyKey = reqKey,
                transactionId = "tx-rec-done",
                referenceNumber = "REF-REC-DONE",
                status = "completed",
                amount = BigDecimal("5000.00"),
                currency = "INR",
                completedAt = "2026-11-11T12:05:00Z"
            )
        )
        vm.onReconcile(reqKey)

        assertTrue(vm.uiState.value.submissionState is TransferSubmissionUiState.Completed)
        val completedReceipt = (vm.uiState.value.submissionState as TransferSubmissionUiState.Completed).receipt
        assertEquals("REF-REC-DONE", completedReceipt.referenceNumber)
    }

    @Test
    fun transfersViewModel_duressCoercionIsolation() {
        val fakeRepo = FakeBankingRepository(initialMode = RepositoryMode.BACKEND_CONNECTED)
        fakeRepo.transferOutcome = BankingResult.Success(
            TransferReceipt(
                transactionId = "tx-duress-isolated",
                referenceNumber = "REF-DURESS-ISOLATED",
                status = "simulated_duress",
                fromAccountId = "acc-01",
                toAccountId = "acc-02",
                amount = BigDecimal("25000.00"),
                currency = "INR",
                idempotencyKey = "key-duress-01",
                completedAt = "2026-11-11T12:10:00Z",
                message = "Simulated duress transfer isolated"
            )
        )

        val vm = TransfersViewModel(fakeRepo)
        vm.onAmountChanged("25000.00")
        vm.onReviewTransfer()
        vm.onConfirmTransfer()

        val subState = vm.uiState.value.submissionState
        assertTrue(subState is TransferSubmissionUiState.RestrictedDuress)
        val duress = subState as TransferSubmissionUiState.RestrictedDuress
        assertTrue(duress.message.contains("Isolated"))
        assertEquals("REF-DURESS-ISOLATED", duress.receipt.referenceNumber)
    }

    // =========================================================================
    // 4. Transaction History Screen & ViewModel Tests
    // =========================================================================

    @Test
    fun historyViewModel_emptyStateAndDuressDistinction() {
        val fakeRepo = FakeBankingRepository(initialMode = RepositoryMode.BACKEND_CONNECTED)
        fakeRepo.transactionsResult = BankingResult.Success(emptyList())

        val vm = HistoryViewModel(fakeRepo)
        // In BACKEND_CONNECTED mode, must show clean Empty state, never invent phantom transactions
        assertTrue(vm.uiState.value.historyState is TransactionHistoryUiState.Empty)
        assertEquals(0, vm.uiState.value.allTransactions.size)

        // Seed with legitimate and duress transactions
        fakeRepo.transactionsResult = BankingResult.Success(
            listOf(
                TransactionHistoryItem(
                    id = "tx-1",
                    referenceNumber = "REF-1",
                    fromAccountId = "acc-1",
                    toAccountId = "acc-2",
                    amount = BigDecimal("1000.00"),
                    currency = "INR",
                    status = "completed",
                    description = "Grocery Store",
                    idempotencyKey = "k1",
                    isDuressMasked = false,
                    createdAt = "2026-11-11T09:00:00Z"
                ),
                TransactionHistoryItem(
                    id = "tx-2",
                    referenceNumber = "REF-2",
                    fromAccountId = "acc-1",
                    toAccountId = "acc-2",
                    amount = BigDecimal("50000.00"),
                    currency = "INR",
                    status = "simulated_duress",
                    description = "Duress Transfer",
                    idempotencyKey = "k2",
                    isDuressMasked = true,
                    createdAt = "2026-11-11T09:30:00Z"
                )
            )
        )
        vm.loadTransactions()

        assertTrue(vm.uiState.value.historyState is TransactionHistoryUiState.Success)
        assertEquals(2, vm.uiState.value.allTransactions.size)

        // Filter by Duress
        vm.onFilterSelected(HistoryFilter.DURESS)
        val duressOnly = vm.uiState.value.filteredTransactions
        assertEquals(1, duressOnly.size)
        assertEquals(TransactionStatus.SIMULATED_DURESS, duressOnly.first().status)
        assertTrue(duressOnly.first().formattedAmount.contains("(Isolated)"))
    }

    // =========================================================================
    // 5. Mode Separation Tests
    // =========================================================================

    @Test
    fun repositoryMode_explicitSwitchingNeverSilentFallback() {
        val fakeRepo = FakeBankingRepository(initialMode = RepositoryMode.BACKEND_CONNECTED)
        assertEquals(RepositoryMode.BACKEND_CONNECTED, fakeRepo.getRepositoryMode())

        fakeRepo.setRepositoryMode(RepositoryMode.DEMO_MOCK)
        assertEquals(RepositoryMode.DEMO_MOCK, fakeRepo.getRepositoryMode())

        fakeRepo.setRepositoryMode(RepositoryMode.BACKEND_CONNECTED)
        assertEquals(RepositoryMode.BACKEND_CONNECTED, fakeRepo.getRepositoryMode())
    }
}
