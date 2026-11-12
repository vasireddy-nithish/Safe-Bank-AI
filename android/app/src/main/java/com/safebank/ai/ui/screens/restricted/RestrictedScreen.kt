package com.safebank.ai.ui.screens.restricted

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
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
import com.safebank.ai.ui.theme.PrimaryPurple
import com.safebank.ai.ui.theme.PrimaryPurpleGlow
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted

@Composable
fun RestrictedScreen(
    onSignOut: () -> Unit,
    accountRef: String = "9481"
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Shield + Lock icon stack
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(PrimaryPurpleGlow, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = PrimaryPurple,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Account Protected",
            color = TextMain,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Your account access is temporarily restricted. This may be a precautionary security measure. If you are safe and need to access your account, please contact SafeBank support directly.",
            color = TextMuted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Account •••• $accountRef",
            color = PrimaryPurple,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(40.dp))

        SafeBankButton(
            text = "Call SafeBank: 1800-XXX-XXXX",
            onClick = { /* Simulated — no real call */ },
            variant = SafeBankButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        SafeBankButton(
            text = "Sign Out",
            onClick = onSignOut,
            variant = SafeBankButtonVariant.Critical,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
