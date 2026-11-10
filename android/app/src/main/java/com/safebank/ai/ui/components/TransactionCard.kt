package com.safebank.ai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safebank.ai.ui.model.TransactionStatus
import com.safebank.ai.ui.model.TransactionType
import com.safebank.ai.ui.model.TransactionUiModel
import com.safebank.ai.ui.theme.CardBackground
import com.safebank.ai.ui.theme.CardBorder
import com.safebank.ai.ui.theme.PrimaryPurple
import com.safebank.ai.ui.theme.StatusCritical
import com.safebank.ai.ui.theme.StatusSuccess
import com.safebank.ai.ui.theme.StatusWarning
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted

/**
 * TransactionCard — Displays transaction details with status badges and minimum 48dp touch targets.
 */
@Composable
fun TransactionCard(
    transaction: TransactionUiModel,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val clickableModifier = if (onClick != null) {
        Modifier
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
    } else Modifier

    Card(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .then(clickableModifier),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Type Icon + Description/Timestamp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                TransactionTypeIcon(type = transaction.type, status = transaction.status)

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = transaction.counterparty,
                        color = TextMain,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${transaction.category} • ${transaction.timestamp}",
                        color = TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Right: Amount + Status Pill
            Column(horizontalAlignment = Alignment.End) {
                val amountColor = when {
                    transaction.status == TransactionStatus.SIMULATED_DURESS -> StatusWarning
                    transaction.type == TransactionType.CREDIT -> StatusSuccess
                    else -> TextMain
                }

                Text(
                    text = transaction.formattedAmount,
                    color = amountColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                TransactionStatusPill(status = transaction.status)
            }
        }
    }
}

@Composable
private fun TransactionTypeIcon(type: TransactionType, status: TransactionStatus) {
    val (icon, bg, tint) = when {
        status == TransactionStatus.SIMULATED_DURESS -> Triple(
            Icons.Default.Security,
            StatusWarning.copy(alpha = 0.15f),
            StatusWarning
        )
        type == TransactionType.CREDIT -> Triple(
            Icons.Default.ArrowDownward,
            StatusSuccess.copy(alpha = 0.15f),
            StatusSuccess
        )
        type == TransactionType.DEBIT -> Triple(
            Icons.Default.ArrowUpward,
            PrimaryPurple.copy(alpha = 0.15f),
            PrimaryPurple
        )
        else -> Triple(
            Icons.Default.SwapHoriz,
            PrimaryPurple.copy(alpha = 0.15f),
            PrimaryPurple
        )
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .background(bg, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun TransactionStatusPill(status: TransactionStatus) {
    val (label, bg, color) = when (status) {
        TransactionStatus.COMPLETED -> Triple("Completed", StatusSuccess.copy(alpha = 0.12f), StatusSuccess)
        TransactionStatus.PENDING -> Triple("Pending", StatusWarning.copy(alpha = 0.12f), StatusWarning)
        TransactionStatus.SIMULATED_DURESS -> Triple("Duress Isolated", StatusWarning.copy(alpha = 0.18f), StatusWarning)
        TransactionStatus.FAILED -> Triple("Failed", StatusCritical.copy(alpha = 0.12f), StatusCritical)
    }

    Box(
        modifier = Modifier
            .background(bg, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
