package com.safebank.ai.ui.screens.transfers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safebank.ai.data.model.BankAccount
import com.safebank.ai.data.model.IndianCurrencyFormatter
import com.safebank.ai.data.state.TransferSubmissionUiState
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
import com.safebank.ai.ui.theme.StatusCritical
import com.safebank.ai.ui.theme.StatusSuccess
import com.safebank.ai.ui.theme.StatusSuccessGlow
import com.safebank.ai.ui.theme.StatusWarning
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted

@Composable
fun TransfersScreen(
    modifier: Modifier = Modifier,
    viewModel: TransfersViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    TransfersScreenContent(
        uiState = uiState,
        onSourceAccountSelected = viewModel::onSourceAccountSelected,
        onDestinationAccountSelected = viewModel::onDestinationAccountSelected,
        onAmountChanged = { value ->
            com.safebank.ai.ml.BehaviourSessionManager.getInstance(context).recordTypingTiming(value.length < uiState.amountInput.length)
            viewModel.onAmountChanged(value)
        },
        onNoteChanged = viewModel::onNoteChanged,
        onReviewTransfer = viewModel::onReviewTransfer,
        onDismissReview = viewModel::onDismissReview,
        onConfirmTransfer = { viewModel.onConfirmTransfer(context) },
        onReconcile = viewModel::onReconcile,
        onDismissSubmissionResult = viewModel::onDismissSubmissionResult,
        modifier = modifier
    )
}

@Composable
fun TransfersScreenContent(
    uiState: TransfersUiState,
    onSourceAccountSelected: (BankAccount) -> Unit,
    onDestinationAccountSelected: (BankAccount) -> Unit,
    onAmountChanged: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onReviewTransfer: () -> Unit,
    onDismissReview: () -> Unit,
    onConfirmTransfer: () -> Unit,
    onReconcile: (String) -> Unit,
    onDismissSubmissionResult: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sourceDropdownExpanded by remember { mutableStateOf(false) }
    var destDropdownExpanded by remember { mutableStateOf(false) }

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
                    text = if (uiState.isModeMock) "Simulated Zero-Risk Financial Engine" else "Direct Core Banking Engine (INR)",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        }

        // Account Selection Error Banner (if any)
        if (uiState.accountError != null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StatusCritical.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = StatusCritical,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = uiState.accountError,
                        color = TextMain,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Source Account Selector Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { sourceDropdownExpanded = true }
                    .semantics { role = Role.Button },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                                    text = "From: ${uiState.sourceAccount?.accountType?.replaceFirstChar { it.uppercase() } ?: "Select Account"}",
                                    color = TextMain,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = uiState.sourceAccount?.let { "${it.maskedAccountNumber} • ${it.formattedBalance}" } ?: "Tap to choose source",
                                    color = SecondaryViolet,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select Source Account",
                            tint = TextMuted,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = sourceDropdownExpanded,
                        onDismissRequest = { sourceDropdownExpanded = false }
                    ) {
                        uiState.availableAccounts.forEach { acc ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("${acc.accountType.replaceFirstChar { it.uppercase() }} (${acc.maskedAccountNumber})", fontWeight = FontWeight.SemiBold)
                                        Text("Balance: ${acc.formattedBalance}", color = SecondaryViolet, fontSize = 12.sp)
                                    }
                                },
                                onClick = {
                                    onSourceAccountSelected(acc)
                                    sourceDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Destination Account Selector Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { destDropdownExpanded = true }
                    .semantics { role = Role.Button },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(StatusSuccessGlow, shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountBalance,
                                    contentDescription = null,
                                    tint = StatusSuccess,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "To: ${uiState.destinationAccount?.accountType?.replaceFirstChar { it.uppercase() } ?: "Select Destination"}",
                                    color = TextMain,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = uiState.destinationAccount?.let { "${it.maskedAccountNumber} • ${it.formattedBalance}" } ?: "Tap to choose destination",
                                    color = SecondaryViolet,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select Destination Account",
                            tint = TextMuted,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = destDropdownExpanded,
                        onDismissRequest = { destDropdownExpanded = false }
                    ) {
                        uiState.availableAccounts.filter { it.id != uiState.sourceAccount?.id }.forEach { acc ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("${acc.accountType.replaceFirstChar { it.uppercase() }} (${acc.maskedAccountNumber})", fontWeight = FontWeight.SemiBold)
                                        Text("Balance: ${acc.formattedBalance}", color = SecondaryViolet, fontSize = 12.sp)
                                    }
                                },
                                onClick = {
                                    onDestinationAccountSelected(acc)
                                    destDropdownExpanded = false
                                }
                            )
                        }
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
                        text = "Transfer Amount",
                        color = TextMain,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    SafeBankTextField(
                        value = uiState.amountInput,
                        onValueChange = onAmountChanged,
                        label = "Amount (₹ INR)",
                        placeholder = "0.00",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.CurrencyRupee,
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
                        placeholder = "What is this transfer for?"
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val isSubmitting = uiState.submissionState is TransferSubmissionUiState.Submitting
                    SafeBankButton(
                        text = if (isSubmitting) "Submitting Transfer..." else "Review Transfer",
                        onClick = onReviewTransfer,
                        enabled = !isSubmitting && uiState.availableAccounts.size >= 2,
                        variant = SafeBankButtonVariant.Primary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Live Risk Indicator
        item {
            RiskIndicator(
                riskScore = uiState.riskScore,
                showDetails = true
            )
        }

        // Dynamic Submission Outcome Banners / Cards
        when (val submission = uiState.submissionState) {
            is TransferSubmissionUiState.Idle -> {
                // Nothing extra
            }
            is TransferSubmissionUiState.Submitting -> {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        border = BorderStroke(1.dp, PrimaryPurple)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = PrimaryPurple,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Dispatching Transfer...",
                                    color = TextMain,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "Durable idempotency reservation acquired.",
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
            is TransferSubmissionUiState.Processing -> {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        border = BorderStroke(1.dp, StatusWarning)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.HourglassTop, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Transfer Processing", color = StatusWarning, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            Text(submission.message, color = TextMain, fontSize = 13.sp)
                            SafeBankButton(
                                text = "Reconcile Status",
                                onClick = { onReconcile(submission.idempotencyKey) },
                                variant = SafeBankButtonVariant.Secondary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            is TransferSubmissionUiState.Completed -> {
                val receipt = submission.receipt
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        border = BorderStroke(1.dp, StatusSuccess)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Transfer Completed Successfully", color = StatusSuccess, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            Text("Reference: ${receipt.referenceNumber}", color = TextMain, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text("Amount: ${receipt.formattedAmount}", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Status: ${receipt.status.replaceFirstChar { it.uppercase() }}", color = StatusSuccess, fontSize = 13.sp)
                            Text("Completed At: ${receipt.completedAt}", color = TextMuted, fontSize = 12.sp)

                            Spacer(modifier = Modifier.height(4.dp))
                            SafeBankButton(
                                text = "Done",
                                onClick = onDismissSubmissionResult,
                                variant = SafeBankButtonVariant.Secondary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            is TransferSubmissionUiState.Failed -> {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        border = BorderStroke(1.dp, StatusCritical)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusCritical, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Transfer Failed", color = StatusCritical, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            Text(submission.message, color = TextMain, fontSize = 13.sp)
                            if (submission.code != null) {
                                Text("Code: ${submission.code}", color = TextMuted, fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (submission.isRetryable) {
                                    SafeBankButton(
                                        text = "Retry",
                                        onClick = onConfirmTransfer,
                                        variant = SafeBankButtonVariant.Primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                SafeBankButton(
                                    text = "Dismiss",
                                    onClick = onDismissSubmissionResult,
                                    variant = SafeBankButtonVariant.Secondary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
            is TransferSubmissionUiState.ReconciliationRequired -> {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        border = BorderStroke(1.dp, StatusWarning)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Sync, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Reconciliation Required", color = StatusWarning, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            Text(
                                text = "A network timeout or uncertain status occurred. To verify whether the transfer succeeded without double-spending, reconcile below.",
                                color = TextMain,
                                fontSize = 13.sp
                            )
                            SafeBankButton(
                                text = "Reconcile Transfer",
                                onClick = { onReconcile(submission.idempotencyKey) },
                                variant = SafeBankButtonVariant.Primary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            is TransferSubmissionUiState.RestrictedDuress -> {
                val receipt = submission.receipt
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        border = BorderStroke(1.dp, PrimaryPurple)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Shield, contentDescription = null, tint = PrimaryPurple, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Simulated Transfer (Duress Isolated)", color = PrimaryPurple, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            Text(submission.message, color = TextMain, fontSize = 13.sp)
                            Text("Reference: ${receipt.referenceNumber}", color = TextMain, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text("Amount: ${receipt.formattedAmount}", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Isolation Status: Ledger accounts untouched. Zero funds moved.", color = TextMuted, fontSize = 12.sp)

                            Spacer(modifier = Modifier.height(4.dp))
                            SafeBankButton(
                                text = "Dismiss",
                                onClick = onDismissSubmissionResult,
                                variant = SafeBankButtonVariant.Secondary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }

    // Confirmation Dialog before dispatch
    if (uiState.isReviewing) {
        val amountDisplay = uiState.parsedAmount?.let { IndianCurrencyFormatter.formatInr(it) } ?: "₹0.00"
        val fromDisplay = uiState.sourceAccount?.let { "${it.accountType} (${it.maskedAccountNumber})" } ?: "Unknown"
        val toDisplay = uiState.destinationAccount?.let { "${it.accountType} (${it.maskedAccountNumber})" } ?: "Unknown"

        ConfirmationDialog(
            title = "Confirm Internal Transfer",
            message = "Please review your transaction details before confirming. Exact INR currency and idempotency protection applied.",
            confirmText = "Confirm & Transfer",
            cancelText = "Edit Details",
            onConfirm = onConfirmTransfer,
            onDismiss = onDismissReview,
            details = listOf(
                "From" to fromDisplay,
                "To" to toDisplay,
                "Amount" to amountDisplay,
                "Currency" to "INR (₹)",
                "Security Level" to "Keystore Attested"
            ),
            warningBanner = if (uiState.isModeMock) "Mock Mode Active: Ledger updates are sandboxed in memory." else null
        )
    }
}
