package com.safebank.ai.ui

import com.safebank.ai.ui.model.RiskTier
import com.safebank.ai.ui.model.TransactionStatus
import com.safebank.ai.ui.screens.history.HistoryFilter
import com.safebank.ai.ui.screens.history.HistoryViewModel
import com.safebank.ai.ui.screens.home.HomeViewModel
import com.safebank.ai.ui.screens.profile.ProfileViewModel
import com.safebank.ai.ui.screens.security.SecurityViewModel
import com.safebank.ai.ui.screens.transfers.TransfersViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewModelUnitTests {

    @Test
    fun homeViewModel_securityScanAndDismiss() {
        val vm = HomeViewModel()
        assertEquals("Alex", vm.uiState.value.userName)
        assertNull(vm.uiState.value.message)

        vm.triggerSecurityScan()
        assertNotNull(vm.uiState.value.message)
        assertTrue(vm.uiState.value.message!!.contains("StrongBox"))

        vm.dismissMessage()
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun transfersViewModel_validatesRecipientAndAmount() {
        val vm = TransfersViewModel()

        // Empty inputs
        vm.onReviewTransfer()
        assertNotNull(vm.uiState.value.recipientError)
        assertNotNull(vm.uiState.value.amountError)
        assertFalse(vm.uiState.value.isReviewing)

        // Invalid amount
        vm.onRecipientChanged("Sarah")
        vm.onAmountChanged("0.00")
        vm.onReviewTransfer()
        assertNull(vm.uiState.value.recipientError)
        assertNotNull(vm.uiState.value.amountError)

        // Exceed balance
        vm.onAmountChanged("99999999.00")
        vm.onReviewTransfer()
        assertNotNull(vm.uiState.value.amountError)

        // Valid inputs
        vm.onAmountChanged("150.00")
        vm.onNoteChanged("Dinner")
        vm.onReviewTransfer()
        assertNull(vm.uiState.value.recipientError)
        assertNull(vm.uiState.value.amountError)
        assertTrue(vm.uiState.value.isReviewing)

        // Confirm
        vm.onConfirmTransfer()
        assertFalse(vm.uiState.value.isReviewing)
        val result = vm.uiState.value.lastTransferResult
        assertNotNull(result)
        assertEquals("Sarah", result!!.counterparty)
        assertEquals("-$150.00", result.formattedAmount)
        assertEquals(TransactionStatus.COMPLETED, result.status)
        assertEquals("", vm.uiState.value.recipient)
        assertEquals("", vm.uiState.value.amount)

        vm.onDismissResult()
        assertNull(vm.uiState.value.lastTransferResult)
    }

    @Test
    fun historyViewModel_searchAndStatusFiltering() {
        val vm = HistoryViewModel()
        val allCount = vm.uiState.value.allTransactions.size
        assertEquals(allCount, vm.uiState.value.filteredTransactions.size)

        // Filter by Duress
        vm.onFilterSelected(HistoryFilter.DURESS)
        val duressList = vm.uiState.value.filteredTransactions
        assertTrue(duressList.isNotEmpty())
        assertTrue(duressList.all { it.status == TransactionStatus.SIMULATED_DURESS })

        // Filter by Completed
        vm.onFilterSelected(HistoryFilter.COMPLETED)
        assertTrue(vm.uiState.value.filteredTransactions.all { it.status == TransactionStatus.COMPLETED })

        // Search Query
        vm.onFilterSelected(HistoryFilter.ALL)
        vm.onSearchQueryChanged("Coffee")
        val coffeeList = vm.uiState.value.filteredTransactions
        assertEquals(1, coffeeList.size)
        assertTrue(coffeeList.first().counterparty.contains("Coffee"))

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
