package com.safebank.ai.ui.screens.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safebank.ai.ui.components.ConfirmationDialog
import com.safebank.ai.ui.components.SafeBankTextField
import com.safebank.ai.ui.components.TransactionCard
import com.safebank.ai.ui.model.TransactionUiModel
import com.safebank.ai.ui.theme.BackgroundDark
import com.safebank.ai.ui.theme.CardBackground
import com.safebank.ai.ui.theme.CardBorder
import com.safebank.ai.ui.theme.PrimaryPurple
import com.safebank.ai.ui.theme.PrimaryPurpleGlow
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    HistoryScreenContent(
        uiState = uiState,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onFilterSelected = viewModel::onFilterSelected,
        onTransactionSelected = viewModel::onTransactionSelected,
        onDismissTransactionDetail = viewModel::onDismissTransactionDetail,
        modifier = modifier
    )
}

@Composable
fun HistoryScreenContent(
    uiState: HistoryUiState,
    onSearchQueryChanged: (String) -> Unit,
    onFilterSelected: (HistoryFilter) -> Unit,
    onTransactionSelected: (TransactionUiModel) -> Unit,
    onDismissTransactionDetail: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Column {
                Text(
                    text = "Transaction History",
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Auditable Mock Ledger Entries",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        }

        // Search Bar
        item {
            SafeBankTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChanged,
                placeholder = "Search by payee, reference, or note...",
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = PrimaryPurple,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = if (uiState.searchQuery.isNotBlank()) {
                    {
                        IconButton(
                            onClick = { onSearchQueryChanged("") },
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { role = Role.Button }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else null
            )
        }

        // Filter Chips Row (each min 48dp height interactive target)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HistoryFilter.entries.forEach { filter ->
                    val isSelected = filter == uiState.selectedFilter
                    val chipBg = if (isSelected) PrimaryPurple else CardBackground
                    val chipBorder = if (isSelected) PrimaryPurple else CardBorder
                    val chipTextColor = if (isSelected) TextMain else TextMuted

                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 56.dp, minHeight = 48.dp)
                            .background(chipBg, shape = RoundedCornerShape(24.dp))
                            .clickable { onFilterSelected(filter) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .semantics { role = Role.Button },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = filter.label,
                            color = chipTextColor,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Transactions List or Empty State
        if (uiState.filteredTransactions.isEmpty()) {
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
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No matching transactions found",
                            color = TextMain,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Try adjusting your search query or filter selection.",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            items(uiState.filteredTransactions, key = { it.id }) { transaction ->
                TransactionCard(
                    transaction = transaction,
                    onClick = { onTransactionSelected(transaction) }
                )
            }
        }
    }

    // Transaction Details Dialog
    uiState.selectedTransaction?.let { tx ->
        ConfirmationDialog(
            title = "Transaction Audit Record",
            message = "Detailed ledger metadata for audit and compliance inspection.",
            confirmText = "Done",
            cancelText = "Close",
            onConfirm = onDismissTransactionDetail,
            onDismiss = onDismissTransactionDetail,
            details = listOf(
                "Reference" to tx.reference,
                "Counterparty" to tx.counterparty,
                "Amount" to tx.formattedAmount,
                "Type" to tx.type.name,
                "Status" to tx.status.name,
                "Timestamp" to tx.timestamp,
                "Memo" to tx.note
            ),
            warningBanner = if (tx.status.name == "SIMULATED_DURESS") {
                "COERCION DEFENSE NOTICE: This transaction was isolated under simulated duress. Ledger balance was untouched and internal audit flag was raised."
            } else null
        )
    }
}
