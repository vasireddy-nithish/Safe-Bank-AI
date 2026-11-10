package com.safebank.ai.ui.screens.home

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safebank.ai.ui.components.BalanceCard
import com.safebank.ai.ui.components.LoadingIndicator
import com.safebank.ai.ui.components.SecurityStatusCard
import com.safebank.ai.ui.components.TransactionCard
import com.safebank.ai.ui.theme.BackgroundDark
import com.safebank.ai.ui.theme.CardBackground
import com.safebank.ai.ui.theme.PrimaryPurple
import com.safebank.ai.ui.theme.PrimaryPurpleGlow
import com.safebank.ai.ui.theme.StatusSuccess
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted

@Composable
fun HomeScreen(
    onNavigateToTransfers: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSecurity: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    HomeScreenContent(
        uiState = uiState,
        onSendClick = onNavigateToTransfers,
        onRequestClick = onNavigateToTransfers,
        onSecurityScanClick = {
            viewModel.triggerSecurityScan()
        },
        onSecurityDetailsClick = onNavigateToSecurity,
        onViewAllTransactions = onNavigateToHistory,
        onDismissMessage = viewModel::dismissMessage,
        modifier = modifier
    )
}

@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    onSendClick: () -> Unit,
    onRequestClick: () -> Unit,
    onSecurityScanClick: () -> Unit,
    onSecurityDetailsClick: () -> Unit,
    onViewAllTransactions: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (uiState.isLoading) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(BackgroundDark),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator(message = "Synchronizing mock accounts...")
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Top Greeting Bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Welcome back,",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "${uiState.userName} 👋",
                        color = TextMain,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(StatusSuccess.copy(alpha = 0.15f), shape = RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(StatusSuccess, shape = CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Protected",
                                color = StatusSuccess,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // Optional Feedback Message Banner
        if (uiState.message != null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PrimaryPurpleGlow, shape = RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = PrimaryPurple,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = uiState.message,
                            color = TextMain,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    IconButton(
                        onClick = onDismissMessage,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { role = Role.Button }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss notice",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Hero Balance Card
        item {
            BalanceCard(
                account = uiState.account,
                onSendClick = onSendClick,
                onRequestClick = onRequestClick,
                onSecurityScanClick = onSecurityScanClick
            )
        }

        // Security Status Card
        item {
            SecurityStatusCard(
                status = uiState.securityStatus,
                onDetailsClick = onSecurityDetailsClick
            )
        }

        // Recent Activity Section Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Recent Transactions",
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(onClick = onViewAllTransactions)
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                        .semantics { role = Role.Button },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "View All",
                        color = PrimaryPurple,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // List of Recent Transactions
        items(uiState.recentTransactions, key = { it.id }) { transaction ->
            TransactionCard(
                transaction = transaction,
                onClick = onViewAllTransactions
            )
        }
    }
}
