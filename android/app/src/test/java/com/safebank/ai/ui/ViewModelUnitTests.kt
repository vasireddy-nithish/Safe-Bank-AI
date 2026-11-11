package com.safebank.ai.ui

import com.safebank.ai.data.state.TransferSubmissionUiState
import com.safebank.ai.ui.model.RiskTier
import com.safebank.ai.ui.model.TransactionStatus
import com.safebank.ai.ui.screens.history.HistoryFilter
import com.safebank.ai.ui.screens.history.HistoryViewModel
import com.safebank.ai.ui.screens.home.HomeViewModel
import com.safebank.ai.ui.screens.profile.ProfileViewModel
import com.safebank.ai.ui.screens.security.SecurityViewModel
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

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelUnitTests {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun homeViewModel_securityScanAndDismiss() {
        val fakeRepo = FakeBankingRepository()
        val vm = HomeViewModel(fakeRepo)

        assertEquals("Alex Chen", vm.uiState.value.userName)
        assertNull(vm.uiState.value.message)

        vm.triggerSecurityScan()
        assertNotNull(vm.uiState.value.message)
        assertTrue(vm.uiState.value.message!!.contains("StrongBox"))

        vm.dismissMessage()
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun transfersViewModel_validatesAccountAndAmount() {
        val fakeRepo = FakeBankingRepository()
        val vm = TransfersViewModel(fakeRepo)

        // Initial accounts loaded
        assertEquals(2, vm.uiState.value.availableAccounts.size)
        assertNotNull(vm.uiState.value.sourceAccount)
        assertNotNull(vm.uiState.value.destinationAccount)

        // Empty amount
        vm.onReviewTransfer()
        assertNotNull(vm.uiState.value.amountError)
        assertFalse(vm.uiState.value.isReviewing)

        // Invalid zero or negative amount
        vm.onAmountChanged("0.00")
        vm.onReviewTransfer()
        assertNotNull(vm.uiState.value.amountError)

        // Exceed source balance
        vm.onAmountChanged("99999999.00")
        vm.onReviewTransfer()
        assertNotNull(vm.uiState.value.amountError)
        assertTrue(vm.uiState.value.amountError!!.contains("exceeds available balance"))

        // Valid positive amount
        vm.onAmountChanged("1500.00")
        vm.onNoteChanged("Dinner split")
        vm.onReviewTransfer()
        assertNull(vm.uiState.value.amountError)
        assertNull(vm.uiState.value.accountError)
        assertTrue(vm.uiState.value.isReviewing)

        // Dismiss review
        vm.onDismissReview()
        assertFalse(vm.uiState.value.isReviewing)

        // Re-review and confirm transfer
        vm.onReviewTransfer()
        vm.onConfirmTransfer()
        assertFalse(vm.uiState.value.isReviewing)
        assertTrue(vm.uiState.value.submissionState is TransferSubmissionUiState.Completed)

        val completed = vm.uiState.value.submissionState as TransferSubmissionUiState.Completed
        assertEquals("REF-TEST-001", completed.receipt.referenceNumber)
        assertEquals("₹1,500.00", completed.receipt.formattedAmount)

        vm.onDismissSubmissionResult()
        assertTrue(vm.uiState.value.submissionState is TransferSubmissionUiState.Idle)
    }

    @Test
    fun historyViewModel_searchAndStatusFiltering() {
        val fakeRepo = FakeBankingRepository()
        val vm = HistoryViewModel(fakeRepo)

        val allCount = vm.uiState.value.allTransactions.size
        assertEquals(2, allCount)
        assertEquals(allCount, vm.uiState.value.filteredTransactions.size)

        // Filter by Duress
        vm.onFilterSelected(HistoryFilter.DURESS)
        val duressList = vm.uiState.value.filteredTransactions
        assertEquals(1, duressList.size)
        assertEquals(TransactionStatus.SIMULATED_DURESS, duressList.first().status)
        assertTrue(duressList.first().formattedAmount.contains("(Isolated)"))

        // Filter by Completed
        vm.onFilterSelected(HistoryFilter.COMPLETED)
        val completedList = vm.uiState.value.filteredTransactions
        assertEquals(1, completedList.size)
        assertEquals(TransactionStatus.COMPLETED, completedList.first().status)

        // Search Query
        vm.onFilterSelected(HistoryFilter.ALL)
        vm.onSearchQueryChanged("Legit")
        val searchList = vm.uiState.value.filteredTransactions
        assertEquals(1, searchList.size)
        assertTrue(searchList.first().counterparty.contains("Legit"))

        // Reset search
        vm.onSearchQueryChanged("")
        assertEquals(allCount, vm.uiState.value.filteredTransactions.size)
    }

    @Test
    fun securityViewModel_auditAndScoreTransition() {
        val vm = SecurityViewModel()
        assertEquals(14, vm.uiState.value.status.behavioralRiskScore)
        assertEquals(RiskTier.LOW, vm.uiState.value.status.riskTier)
        assertNull(vm.uiState.value.auditMessage)

        vm.runHardwareKeyAudit()
        assertNotNull(vm.uiState.value.auditMessage)
        assertTrue(vm.uiState.value.auditMessage!!.contains("StrongBox Keymaster"))

        // Toggle score to Moderate (48)
        vm.simulateScoreToggle()
        assertEquals(48, vm.uiState.value.status.behavioralRiskScore)
        assertEquals(RiskTier.MODERATE, vm.uiState.value.status.riskTier)

        // Toggle score to High (82)
        vm.simulateScoreToggle()
        assertEquals(82, vm.uiState.value.status.behavioralRiskScore)
        assertEquals(RiskTier.HIGH, vm.uiState.value.status.riskTier)

        // Reset to Low (14)
        vm.simulateScoreToggle()
        assertEquals(14, vm.uiState.value.status.behavioralRiskScore)
        assertEquals(RiskTier.LOW, vm.uiState.value.status.riskTier)
    }

    @Test
    fun profileViewModel_sessionLockAndSignOutLifecycle() {
        val vm = ProfileViewModel()
        assertFalse(vm.uiState.value.isLockDialogVisible)
        assertFalse(vm.uiState.value.isSignOutDialogVisible)

        vm.showLockDialog()
        assertTrue(vm.uiState.value.isLockDialogVisible)
        vm.confirmLockSession()
        assertFalse(vm.uiState.value.isLockDialogVisible)
        assertTrue(vm.uiState.value.statusMessage!!.contains("Session locked"))

        vm.showSignOutDialog()
        assertTrue(vm.uiState.value.isSignOutDialogVisible)
        vm.confirmSignOut()
        assertFalse(vm.uiState.value.isSignOutDialogVisible)
        assertTrue(vm.uiState.value.statusMessage!!.contains("invalidated"))

        vm.dismissStatusMessage()
        assertNull(vm.uiState.value.statusMessage)
    }
}
