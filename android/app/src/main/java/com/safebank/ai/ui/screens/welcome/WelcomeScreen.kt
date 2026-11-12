package com.safebank.ai.ui.screens.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safebank.ai.ui.components.SafeBankButton
import com.safebank.ai.ui.components.SafeBankButtonVariant
import com.safebank.ai.ui.theme.BackgroundDark
import com.safebank.ai.ui.theme.CardBackground
import com.safebank.ai.ui.theme.CardBorder
import com.safebank.ai.ui.theme.PrimaryPurple
import com.safebank.ai.ui.theme.PrimaryPurpleGlow
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted

@Composable
fun WelcomeScreen(
    onNavigateToRegister: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Top spacer
        Spacer(modifier = Modifier.height(16.dp))

        // Logo area
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .background(PrimaryPurpleGlow, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "SafeBank Shield",
                    tint = PrimaryPurple,
                    modifier = Modifier.size(52.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "SafeBank AI",
                color = TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Coercion-Aware Banking Security",
                color = TextMuted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
        }

        // Privacy feature pills
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FeaturePill(
                emoji = "🔑",
                title = "Passwordless",
                description = "Passkey & biometric authentication — no passwords ever stored."
            )
            FeaturePill(
                emoji = "🧠",
                title = "On-device AI",
                description = "Behavioural biometrics run entirely on your device."
            )
            FeaturePill(
                emoji = "🛡️",
                title = "Coercion-aware",
                description = "Silent duress detection protects you under threat."
            )
        }

        // Buttons + disclaimer
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SafeBankButton(
                text = "Register Account",
                onClick = onNavigateToRegister,
                variant = SafeBankButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth()
            )

            SafeBankButton(
                text = "Sign In",
                onClick = onNavigateToLogin,
                variant = SafeBankButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Demo prototype — no real banking operations",
                color = TextMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FeaturePill(
    emoji: String,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, shape = RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = emoji, fontSize = 24.sp)
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                color = TextMain,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Text(
                text = description,
                color = TextMuted,
                fontSize = 12.sp
            )
        }
    }
}
