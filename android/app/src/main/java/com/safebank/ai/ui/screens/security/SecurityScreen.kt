package com.safebank.ai.ui.screens.security

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safebank.ai.ui.components.RiskIndicator
import com.safebank.ai.ui.components.SafeBankButton
import com.safebank.ai.ui.components.SafeBankButtonVariant
import com.safebank.ai.ui.components.SecurityStatusCard
import com.safebank.ai.ui.theme.BackgroundDark
import com.safebank.ai.ui.theme.CardBackground
import com.safebank.ai.ui.theme.CardBorder
import com.safebank.ai.ui.theme.PrimaryPurple
import com.safebank.ai.ui.theme.PrimaryPurpleGlow
import com.safebank.ai.ui.theme.SecondaryViolet
import com.safebank.ai.ui.theme.StatusSuccess
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted

@Composable
fun SecurityScreen(
    modifier: Modifier = Modifier,
    viewModel: SecurityViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadLiveSecurityData(context)
    }

    SecurityScreenContent(
        uiState = uiState,
        onRunHardwareKeyAudit = viewModel::runHardwareKeyAudit,
        onSimulateScoreToggle = viewModel::simulateScoreToggle,
        onDismissAuditMessage = viewModel::dismissAuditMessage,
        onRunLiveMlCheck = { viewModel.runOnDeviceBehaviorAssessment(context) },
        onTriggerDemoScenario = { scenario -> viewModel.triggerBackendDemoScenario(context, scenario) },
        modifier = modifier
    )
}

@Composable
fun SecurityScreenContent(
    uiState: SecurityUiState,
    onRunHardwareKeyAudit: () -> Unit,
    onSimulateScoreToggle: () -> Unit,
    onDismissAuditMessage: () -> Unit,
    onRunLiveMlCheck: () -> Unit = {},
    onTriggerDemoScenario: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Header
        item {
            Column {
                Text(
                    text = "Security Centre",
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Hardware Keymaster, Passkeys & Coercion Defense",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        }

        // Live Audit Message Banner
        if (uiState.auditMessage != null) {
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
                            text = uiState.auditMessage,
                            color = TextMain,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    IconButton(
                        onClick = onDismissAuditMessage,
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

        // Security Status Posture Card
        item {
            SecurityStatusCard(
                status = uiState.status
            )
        }

        // Behavioral Risk Index Meter
        item {
            val score = uiState.serverRiskScore
            if (score != null) RiskIndicator(riskScore = score, showDetails = true)
            else Text("Server risk assessment unavailable or not yet performed", color = TextMuted)
        }

        // Three Security Pillars Architecture Card
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
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "The Three Security Questions (PS-05)",
                        color = TextMain,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    PillarRow(
                        number = "1",
                        title = "Identity: FIDO2 / Passkeys",
                        description = "Asymmetric ECDSA P-256 keys bound to device hardware. Eliminates credential stuffing and phishing."
                    )

                    PillarRow(
                        number = "2",
                        title = "Device: StrongBox Keymaster",
                        description = "Hardware-isolated Secure Element with physical tamper resistance ensuring session authenticity."
                    )

                    PillarRow(
                        number = "3",
                        title = "Freedom to Act: Independent Duress",
                        description = "Explicit duress activation triggers simulated honeypot banking with zero ledger impact. Behavioral ML does NOT substitute for explicit duress."
                    )
                }
            }
        }

        // Recent Security Events (Live from backend)
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Recent Security Events",
                        color = TextMain,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    if (uiState.securityEvents.isEmpty()) {
                        Text(
                            text = "No backend security events recorded for this user yet.",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    } else {
                        uiState.securityEvents.take(5).forEach { ev ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ev.type.replace("_", " ").replaceFirstChar { it.uppercase() },
                                        color = TextMain,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = ev.timestamp.take(19).replace("T", " "),
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                                Text(
                                    text = ev.severity.uppercase(),
                                    color = when (ev.severity) {
                                        "critical" -> Color(0xFFE53935)
                                        "warning" -> Color(0xFFFF9800)
                                        else -> StatusSuccess
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Interactive Security Audit Actions
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SafeBankButton(
                    text = "Run On-Device ML Risk Assessment",
                    onClick = onRunLiveMlCheck,
                    variant = SafeBankButtonVariant.Primary,
                    isLoading = uiState.isAuditing,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = TextMain,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                SafeBankButton(
                    text = "Demo: Trigger Suspicious Behaviour (Scenario B)",
                    onClick = { onTriggerDemoScenario("suspicious") },
                    variant = SafeBankButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )

                SafeBankButton(
                    text = "Demo: Trigger Device / Replay Attack (Scenario D)",
                    onClick = { onTriggerDemoScenario("device") },
                    variant = SafeBankButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )

                SafeBankButton(
                    text = "Simulate StrongBox Keymaster Check",
                    onClick = onRunHardwareKeyAudit,
                    variant = SafeBankButtonVariant.Secondary,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = PrimaryPurple,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                SafeBankButton(
                    text = "Simulate Risk Level Transition",
                    onClick = onSimulateScoreToggle,
                    variant = SafeBankButtonVariant.Secondary,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = PrimaryPurple,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun PillarRow(
    number: String,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(PrimaryPurpleGlow, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = PrimaryPurple,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(
                text = title,
                color = TextMain,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}
