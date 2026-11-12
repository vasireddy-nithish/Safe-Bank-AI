package com.safebank.ai.ui.screens.register

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.KeyboardOptions
import com.safebank.ai.ui.components.SafeBankButton
import com.safebank.ai.ui.components.SafeBankButtonVariant
import com.safebank.ai.ui.components.SafeBankTextField
import com.safebank.ai.ui.theme.BackgroundDark
import com.safebank.ai.ui.theme.CardBackground
import com.safebank.ai.ui.theme.PrimaryPurple
import com.safebank.ai.ui.theme.StatusCritical
import com.safebank.ai.ui.theme.StatusWarning
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted

@Composable
fun RegisterScreen(
    onRegistrationComplete: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: RegisterViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextMain
                )
            }
            Text(
                text = "Create Account",
                color = TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Error / success banner
            uiState.errorMessage?.let { msg ->
                Text(
                    text = msg,
                    color = StatusCritical,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StatusCritical.copy(alpha = 0.08f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .padding(12.dp)
                )
            }
            uiState.successMessage?.let { msg ->
                Text(
                    text = msg,
                    color = com.safebank.ai.ui.theme.StatusSuccess,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(com.safebank.ai.ui.theme.StatusSuccess.copy(alpha = 0.08f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .padding(12.dp)
                )
            }

            Text(
                text = "Register with SafeBank AI",
                color = TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )

            Text(
                text = "Create a demo account using your enrolment code.",
                color = TextMuted,
                fontSize = 14.sp
            )

            SafeBankTextField(
                value = uiState.fullName,
                onValueChange = viewModel::onFullNameChange,
                label = "Full Name",
                placeholder = "e.g. Alex Chen",
                isError = uiState.nameError != null,
                errorMessage = uiState.nameError,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = if (uiState.nameError != null) StatusCritical else PrimaryPurple,
                        modifier = Modifier.size(20.dp)
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            SafeBankTextField(
                value = uiState.username,
                onValueChange = viewModel::onUsernameChange,
                label = "Username",
                placeholder = "e.g. alex_chen",
                isError = uiState.usernameError != null,
                errorMessage = uiState.usernameError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            SafeBankTextField(
                value = uiState.enrolmentCode,
                onValueChange = viewModel::onEnrolmentCodeChange,
                label = "Demo Enrolment Code",
                placeholder = "SAFEBANK2024",
                isError = uiState.enrolmentError != null,
                errorMessage = uiState.enrolmentError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            // Duress PIN — SecretStr treatment: PasswordVisualTransformation, never logged
            SafeBankTextField(
                value = uiState.duressPin,
                onValueChange = viewModel::onDuresspinChange,
                label = "Duress PIN (Optional, 6–12 digits)",
                placeholder = "••••••",
                isError = uiState.duressPinError != null,
                errorMessage = uiState.duressPinError,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )

            Text(
                text = "⚠ The Duress PIN is a coercion-safety feature. If provided, never share it. It is treated as a secret and is never stored in plaintext.",
                color = StatusWarning,
                fontSize = 12.sp
            )

            // Consent checkboxes
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.onConsentBiometricChange(!uiState.consentBiometric) }
                    .padding(vertical = 4.dp)
            ) {
                Checkbox(
                    checked = uiState.consentBiometric,
                    onCheckedChange = viewModel::onConsentBiometricChange,
                    colors = CheckboxDefaults.colors(checkedColor = PrimaryPurple)
                )
                Text(
                    text = "I consent to behavioural biometric monitoring",
                    color = TextMain,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.onConsentDemoChange(!uiState.consentDemo) }
                    .padding(vertical = 4.dp)
            ) {
                Checkbox(
                    checked = uiState.consentDemo,
                    onCheckedChange = viewModel::onConsentDemoChange,
                    colors = CheckboxDefaults.colors(checkedColor = PrimaryPurple)
                )
                Text(
                    text = "I confirm this is a demo account",
                    color = TextMain,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            SafeBankButton(
                text = "Register Account",
                onClick = { viewModel.register(onRegistrationComplete) },
                variant = SafeBankButtonVariant.Primary,
                isLoading = uiState.isLoading,
                enabled = uiState.consentBiometric && uiState.consentDemo && !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
