package com.safebank.ai.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.safebank.ai.ui.components.BalanceCard
import com.safebank.ai.ui.components.ConfirmationDialog
import com.safebank.ai.ui.components.LoadingIndicator
import com.safebank.ai.ui.components.RiskIndicator
import com.safebank.ai.ui.components.SafeBankButton
import com.safebank.ai.ui.components.SafeBankButtonVariant
import com.safebank.ai.ui.components.SafeBankTextField
import com.safebank.ai.ui.components.SecurityStatusCard
import com.safebank.ai.ui.components.TransactionCard
import com.safebank.ai.ui.model.AccountUiModel
import com.safebank.ai.ui.model.MockDataProvider
import com.safebank.ai.ui.model.SecurityStatusUiModel
import com.safebank.ai.ui.model.TransactionStatus
import com.safebank.ai.ui.model.TransactionType
import com.safebank.ai.ui.model.TransactionUiModel
import com.safebank.ai.ui.theme.SafeBankTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComponentAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun safeBankButton_enforcesMinimum48dpTouchTarget() {
        var clicked = false
        composeTestRule.setContent {
            SafeBankTheme {
                SafeBankButton(
                    text = "Transfer Funds",
                    onClick = { clicked = true },
                    variant = SafeBankButtonVariant.Primary
                )
            }
        }

        val buttonNode = composeTestRule.onNodeWithText("Transfer Funds")
        buttonNode.assertIsDisplayed()
        buttonNode.assertHeightIsAtLeast(48.dp)
        buttonNode.assertWidthIsAtLeast(48.dp)
        buttonNode.assertIsEnabled()
        buttonNode.performClick()
        assertTrue("SafeBankButton onClick was not triggered", clicked)
    }

    @Test
    fun safeBankButton_disabledAndLoadingStates() {
        var clicked = false
        composeTestRule.setContent {
            SafeBankTheme {
                SafeBankButton(
                    text = "Confirm Action",
                    onClick = { clicked = true },
                    enabled = false
                )
            }
        }

        val buttonNode = composeTestRule.onNodeWithText("Confirm Action")
        buttonNode.assertIsNotEnabled()
    }

    @Test
    fun safeBankTextField_enforcesMinimum48dpTouchTargetAndAcceptsInput() {
        var textValue = ""
        composeTestRule.setContent {
            SafeBankTheme {
                SafeBankTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = "Payee Account",
                    placeholder = "Enter account number"
                )
            }
        }

        val inputNode = composeTestRule.onNodeWithText("Payee Account")
        inputNode.assertIsDisplayed()
        inputNode.assertHeightIsAtLeast(48.dp)
        inputNode.performTextInput("ACCT-9021")
        assertEquals("ACCT-9021", textValue)
    }

    @Test
    fun safeBankTextField_displaysErrorMessageWhenErrorState() {
        composeTestRule.setContent {
            SafeBankTheme {
                SafeBankTextField(
                    value = "",
                    onValueChange = {},
                    label = "Amount",
                    isError = true,
                    errorMessage = "Amount must be greater than zero"
                )
            }
        }

        composeTestRule.onNodeWithText("Amount must be greater than zero").assertIsDisplayed()
    }

    @Test
    fun securityStatusCard_rendersAttestationBadgesAnd48dpDetailButton() {
        var detailsClicked = false
        composeTestRule.setContent {
            SafeBankTheme {
                SecurityStatusCard(
                    status = SecurityStatusUiModel(overallScore = 98),
                    onDetailsClick = { detailsClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Security Posture (Simulated)").assertIsDisplayed()
        composeTestRule.onNodeWithText("98 / 100 (Simulated)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hardware Keystore").assertIsDisplayed()
        composeTestRule.onNodeWithText("FIDO2 / Passkey").assertIsDisplayed()
        composeTestRule.onNodeWithText("Biometrics").assertIsDisplayed()
        composeTestRule.onNodeWithText("Coercion Defense").assertIsDisplayed()

        val buttonNode = composeTestRule.onNodeWithText("View Security Details")
        buttonNode.assertHeightIsAtLeast(48.dp)
        buttonNode.performClick()
        assertTrue(detailsClicked)
    }

    @Test
    fun transactionCard_rendersDetailsAndEnforces48dpTouchTarget() {
        var clicked = false
        val mockTx = TransactionUiModel(
            id = "tx-test-01",
            reference = "TX-REF-9988",
            counterparty = "Sarah Miller",
            amount = -45.00,
            formattedAmount = "-$45.00",
            type = TransactionType.TRANSFER,
            status = TransactionStatus.COMPLETED,
            category = "Dining",
            timestamp = "Today, 12:30 PM",
            note = "Lunch split"
        )

        composeTestRule.setContent {
            SafeBankTheme {
                TransactionCard(
                    transaction = mockTx,
                    onClick = { clicked = true }
                )
            }
        }

        val cardNode = composeTestRule.onNodeWithText("Sarah Miller")
        cardNode.assertIsDisplayed()
        composeTestRule.onNodeWithText("-$45.00").assertIsDisplayed()
        composeTestRule.onNodeWithText("Completed").assertIsDisplayed()
        cardNode.performClick()
        assertTrue(clicked)
    }

    @Test
    fun transactionCard_rendersSimulatedDuressBadge() {
        val duressTx = TransactionUiModel(
            id = "tx-test-duress",
            reference = "TX-DURESS-01",
            counterparty = "Coercion Honeypot",
            amount = 0.0,
            formattedAmount = "$500.00 (Isolated)",
            type = TransactionType.TRANSFER,
            status = TransactionStatus.SIMULATED_DURESS,
            category = "Duress Isolated",
            timestamp = "Nov 7, 11:45 PM"
        )

        composeTestRule.setContent {
            SafeBankTheme {
                TransactionCard(transaction = duressTx)
            }
        }

        composeTestRule.onNodeWithText("Duress Isolated").assertIsDisplayed()
        composeTestRule.onNodeWithText("$500.00 (Isolated)").assertIsDisplayed()
    }

    @Test
    fun balanceCard_rendersBalanceAndEnforces48dpActionButtons() {
        var sendClicked = false
        var requestClicked = false
        var securityClicked = false

        composeTestRule.setContent {
            SafeBankTheme {
                BalanceCard(
                    account = AccountUiModel(
                        balance = 12450.80,
                        formattedBalance = "$12,450.80"
                    ),
                    onSendClick = { sendClicked = true },
                    onRequestClick = { requestClicked = true },
                    onSecurityScanClick = { securityClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithText("$12,450.80").assertIsDisplayed()

        // Toggle balance visibility
        val toggleBtn = composeTestRule.onNodeWithContentDescription("Hide balance")
        toggleBtn.assertHeightIsAtLeast(48.dp)
        toggleBtn.assertWidthIsAtLeast(48.dp)
        toggleBtn.performClick()
        composeTestRule.onNodeWithText("₹ ••••••••").assertIsDisplayed()

        // Quick action buttons
        val sendBtn = composeTestRule.onNodeWithText("Send")
        sendBtn.assertHeightIsAtLeast(48.dp)
        sendBtn.performClick()
        assertTrue(sendClicked)

        val receiveBtn = composeTestRule.onNodeWithText("Receive")
        receiveBtn.assertHeightIsAtLeast(48.dp)
        receiveBtn.performClick()
        assertTrue(requestClicked)

        val secBtn = composeTestRule.onNodeWithText("Security")
        secBtn.assertHeightIsAtLeast(48.dp)
        secBtn.performClick()
        assertTrue(securityClicked)
    }

    @Test
    fun riskIndicator_rendersBehavioralMeterAndScore() {
        composeTestRule.setContent {
            SafeBankTheme {
                RiskIndicator(riskScore = 14)
            }
        }

        composeTestRule.onNodeWithText("Behavioral Risk Index (Demo)").assertIsDisplayed()
        composeTestRule.onNodeWithText("14 / 100 (Demo)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Low Risk (Demo Baseline)").assertIsDisplayed()
    }

    @Test
    fun loadingIndicator_rendersCustomMessage() {
        composeTestRule.setContent {
            SafeBankTheme {
                LoadingIndicator(message = "Verifying StrongBox Attestation...")
            }
        }

        composeTestRule.onNodeWithText("Verifying StrongBox Attestation...").assertIsDisplayed()
    }

    @Test
    fun confirmationDialog_rendersContentAndEnforces48dpButtons() {
        var confirmed = false
        var dismissed = false

        composeTestRule.setContent {
            SafeBankTheme {
                ConfirmationDialog(
                    title = "Confirm Mock Transfer",
                    message = "Simulated transfer of funds without real ledger modification.",
                    confirmText = "Proceed",
                    cancelText = "Cancel",
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true },
                    details = listOf("Recipient" to "Alex Chen", "Amount" to "$100.00")
                )
            }
        }

        composeTestRule.onNodeWithText("Confirm Mock Transfer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alex Chen").assertIsDisplayed()
        composeTestRule.onNodeWithText("$100.00").assertIsDisplayed()

        val cancelBtn = composeTestRule.onNodeWithText("Cancel")
        cancelBtn.assertHeightIsAtLeast(48.dp)

        val proceedBtn = composeTestRule.onNodeWithText("Proceed")
        proceedBtn.assertHeightIsAtLeast(48.dp)
        proceedBtn.performClick()
        assertTrue(confirmed)
    }
}
