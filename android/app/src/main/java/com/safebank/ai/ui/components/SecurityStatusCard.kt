package com.safebank.ai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safebank.ai.ui.model.SecurityStatusUiModel
import com.safebank.ai.ui.theme.CardBackground
import com.safebank.ai.ui.theme.CardBorder
import com.safebank.ai.ui.theme.PrimaryPurple
import com.safebank.ai.ui.theme.PrimaryPurpleGlow
import com.safebank.ai.ui.theme.StatusCritical
import com.safebank.ai.ui.theme.StatusSuccess
import com.safebank.ai.ui.theme.StatusSuccessGlow
import com.safebank.ai.ui.theme.StatusWarning
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted

/**
 * SecurityStatusCard — Displays the live cryptographic and coercion-defense posture.
 * Labels all scores and attestations as simulated until live Stage 5/6 integration.
 */
@Composable
fun SecurityStatusCard(
    status: SecurityStatusUiModel,
    modifier: Modifier = Modifier,
    onDetailsClick: (() -> Unit)? = null
) {
    val statusColor = if (status.isProtected) StatusSuccess else StatusWarning
    val statusGlow = if (status.isProtected) StatusSuccessGlow else StatusCritical.copy(alpha = 0.2f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onDetailsClick != null) {
                    Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { role = Role.Button }
                } else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header: Shield Icon + Title + Score Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(statusGlow, shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Security Status Icon",
                            tint = statusColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "Security Posture (Simulated)",
                            color = TextMain,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text(
                            text = if (status.isProtected) "Simulated Posture • Demo Mock" else "Action Recommended",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }

                // Score Badge (Explicitly labeled Simulated)
                Box(
                    modifier = Modifier
                        .background(statusGlow, shape = RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${status.overallScore} / 100 (Simulated)",
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4 Security Attestation Badges (Explicitly labeled as Simulated/Demo)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecurityFeatureRow(
                    icon = Icons.Default.Security,
                    title = "Hardware Keystore",
                    value = if (status.hardwareKeystoreBound) "${status.hardwareKeystoreType} (Simulated)" else "Unbound",
                    isVerified = status.hardwareKeystoreBound
                )
                SecurityFeatureRow(
                    icon = Icons.Default.VpnKey,
                    title = "FIDO2 / Passkey",
                    value = if (status.passkeyEnrolled) "Mock Passkey (Simulated)" else "Not Registered",
                    isVerified = status.passkeyEnrolled
                )
                SecurityFeatureRow(
                    icon = Icons.Default.Fingerprint,
                    title = "Biometrics",
                    value = if (status.biometricEnrolled) "${status.biometricLevel} (Simulated)" else "Not Enrolled",
                    isVerified = status.biometricEnrolled
                )
                SecurityFeatureRow(
                    icon = Icons.Default.Key,
                    title = "Coercion Defense",
                    value = if (status.coercionDefenseArmed) "Armed (Simulated Honeypot)" else "Disabled",
                    isVerified = status.coercionDefenseArmed
                )
            }

            if (onDetailsClick != null) {
                Spacer(modifier = Modifier.height(16.dp))
                SafeBankButton(
                    text = "View Security Details",
                    onClick = onDetailsClick,
                    variant = SafeBankButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun SecurityFeatureRow(
    icon: ImageVector,
    title: String,
    value: String,
    isVerified: Boolean
) {
    val indicatorColor = if (isVerified) StatusSuccess else StatusWarning

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F142A), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PrimaryPurple,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(indicatorColor, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = value,
                color = TextMain,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
