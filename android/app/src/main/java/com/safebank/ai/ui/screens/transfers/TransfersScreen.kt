package com.safebank.ai.ui.screens.transfers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safebank.ai.ui.components.ConfirmationDialog
import com.safebank.ai.ui.components.RiskIndicator
import com.safebank.ai.ui.components.SafeBankButton
import com.safebank.ai.ui.components.SafeBankButtonVariant
import com.safebank.ai.ui.components.SafeBankTextField
import com.safebank.ai.ui.theme.BackgroundDark
import com.safebank.ai.ui.theme.CardBackground
import com.safebank.ai.ui.theme.CardBorder
import com.safebank.ai.ui.theme.PrimaryPurple
import com.safebank.ai.ui.theme.PrimaryPurpleGlow
import com.safebank.ai.ui.theme.SecondaryViolet
import com.safebank.ai.ui.theme.StatusSuccess
import com.safebank.ai.ui.theme.StatusSuccessGlow
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted

@Composable
fun TransfersScreen(
    modifier: Modifier = Modifier,
    viewModel: TransfersViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    TransfersScreenContent(
        uiState = uiState,
        onRecipientChanged = viewModel::onRecipientChanged,
        onAmountChanged = viewModel::onAmountChanged,
        onNoteChanged = viewModel::onNoteChanged,
        onReviewTransfer = viewModel::onReviewTransfer,
        onDismissReview = viewModel::onDismissReview,
        onConfirmTransfer = viewModel::onConfirmTransfer,
        onDismissResult = viewModel::onDismissResult,
        modifier = modifier
    )
}

@Composable
fun TransfersScreenContent(
    uiState: TransfersUiState,
    onRecipientChanged: (String) -> Unit,
    onAmountChanged: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onReviewTransfer: () -> Unit,
    onDismissReview: () -> Unit,
    onConfirmTransfer: () -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Screen Header
        item {
            Column {
                Text(
                    text = "Transfer Money",
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Simulated Zero-Risk Financial Engine",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        }

        // Source Account Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(PrimaryPurpleGlow, shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountBalance,
                                contentDescription = null,
                                tint = PrimaryPurple,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "From: ${uiState.account.accountType}",
                                color = TextMain,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Balance: ${uiState.account.formattedBalance}",
                                color = SecondaryViolet,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .background(StatusSuccessGlow, shape = RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Active",
                            color = StatusSuccess,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Transfer Form
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Transfer Details",
                        color = TextMain,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    SafeBankTextField(
                        value = uiState.recipient,
                        onValueChange = onRecipientChanged,
                        label = "Recipient (Account or Name)",
                        placeholder = "e.g. Sarah Miller or ACCT-4891",
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = PrimaryPurple,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        isError = uiState.recipientError != null,
                        errorMessage = uiState.recipientError
                    )

                    SafeBankTextField(
                        value = uiState.amount,
                        onValueChange = onAmountChanged,
                        label = "Amount ($ USD)",
                        placeholder = "0.00",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.AttachMoney,
                                contentDescription = null,
                                tint = PrimaryPurple,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        isError = uiState.amountError != null,
                        errorMessage = uiState.amountError
                    )

                    SafeBankTextField(
                        value = uiState.note,
                        onValueChange = onNoteChanged,
                        label = "Memo / Reference (Optional)",
                        placeholder = "What is this for?"
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    SafeBankButton(
                        text = "Review Transfer",
                        onClick = onReviewTransfer,
                        variant = SafeBankButtonVariant.Primary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Live Risk Index Preview
        item {
            RiskIndicator(
                riskScore = uiState.riskScore,
                showDetails = true
            )
        }

        // Successful Transfer Result Banner
        if (uiState.lastTransferResult != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    border = BorderStroke(1.dp, StatusSuccess)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = StatusSuccess,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Transfer Simulation Successful",
                                color = StatusSuccess,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Reference: ${uiState.lastTransferResult.reference}",
                            color = TextMain,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Recipient: ${uiState.lastTransferResult.counterparty}",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Amount: ${uiState.lastTransferResult.formattedAmount}",
                            color = TextMain,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        SafeBankButton(
                            text = "Done",
                            onClick = onDismissResult,
                            variant = SafeBankButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    // Confirmation Dialog
    if (uiState.isReviewing) {
        val amountDisplay = "$${uiState.amount.toDoubleOrNull()?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: uiState.amount}"
        ConfirmationDialog(
            title = "Confirm Mock Transfer",
            message = "Please review your simulated transaction details before confirming. No real funds will move.",
            confirmText = "Confirm & Execute",
            cancelText = "Edit Details",
            onConfirm = onConfirmTransfer,
            onDismiss = onDismissReview,
            details = listOf(
                "Source" to uiState.account.maskedAccountNumber,
                "Recipient" to uiState.recipient,
                "Amount" to amountDisplay,
                "Security Level" to "StrongBox Attested"
            ),
            warningBanner = "Mock Mode Active: Ledger updates are sandboxed."
        )
    }
}
