package com.safebank.ai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.safebank.ai.ui.screens.home.HomeScreenContent
import com.safebank.ai.ui.screens.home.HomeUiState
import com.safebank.ai.ui.screens.security.SecurityScreenContent
import com.safebank.ai.ui.screens.security.SecurityUiState
import com.safebank.ai.ui.screens.transfers.TransfersScreenContent
import com.safebank.ai.ui.screens.transfers.TransfersUiState
import com.safebank.ai.ui.theme.SafeBankTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenResponsiveAndScalingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun homeScreen_rendersCleanlyOnCompactPhone_360dp() {
        composeTestRule.setContent {
            SafeBankTheme {
                Box(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                    HomeScreenContent(
                        uiState = HomeUiState(userName = "Alex"),
                        onSendClick = {},
                        onRequestClick = {},
                        onSecurityScanClick = {},
                        onSecurityDetailsClick = {},
                        onViewAllTransactions = {},
                        onDismissMessage = {},
                        onDismissError = {},
                        onRetry = {}
                    )
                }
            }
        }

        // Header and top components are displayed cleanly without overlap
        composeTestRule.onNodeWithText("Welcome back,").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alex 👋").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send").assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithText("Receive").assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithText("Security").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun homeScreen_rendersCleanlyOnTablet_800dp() {
        composeTestRule.setContent {
            SafeBankTheme {
                Box(modifier = Modifier.size(width = 800.dp, height = 1280.dp)) {
                    HomeScreenContent(
                        uiState = HomeUiState(userName = "Alex"),
                        onSendClick = {},
                        onRequestClick = {},
                        onSecurityScanClick = {},
                        onSecurityDetailsClick = {},
                        onViewAllTransactions = {},
                        onDismissMessage = {},
                        onDismissError = {},
                        onRetry = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Welcome back,").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alex 👋").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send").assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithText("Receive").assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithText("Security").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun transfersScreen_rendersCleanlyWith150PercentTextScaling() {
        composeTestRule.setContent {
            val currentDensity = LocalDensity.current
            // Apply 1.5x accessibility text scaling preserving device density
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale = 1.5f)
            ) {
                SafeBankTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TransfersScreenContent(
                            uiState = TransfersUiState(),
                            onSourceAccountSelected = {},
                            onDestinationAccountSelected = {},
                            onAmountChanged = {},
                            onNoteChanged = {},
                            onReviewTransfer = {},
                            onDismissReview = {},
                            onConfirmTransfer = {},
                            onReconcile = {},
                            onDismissSubmissionResult = {}
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Transfer Money").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transfer Amount").assertIsDisplayed()
        composeTestRule.onNodeWithText("Amount (₹ INR)").assertIsDisplayed()
    }

    @Test
    fun securityScreen_rendersCleanlyWith150PercentTextScaling() {
        composeTestRule.setContent {
            val currentDensity = LocalDensity.current
            // Apply 1.5x accessibility text scaling preserving device density
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale = 1.5f)
            ) {
                SafeBankTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        SecurityScreenContent(
                            uiState = SecurityUiState(),
                            onRunHardwareKeyAudit = {},
                            onSimulateScoreToggle = {},
                            onDismissAuditMessage = {}
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Security Centre").assertIsDisplayed()
        composeTestRule.onNodeWithText("Security Posture (Simulated)").assertIsDisplayed()
        composeTestRule.onNodeWithText("96 / 100 (Simulated)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hardware Keystore").assertIsDisplayed()
    }
}
