package com.safebank.ai.ui.screens.profile

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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safebank.ai.ui.components.ConfirmationDialog
import com.safebank.ai.ui.components.SafeBankButton
import com.safebank.ai.ui.components.SafeBankButtonVariant
import com.safebank.ai.ui.components.SafeBankTextField
import com.safebank.ai.ui.model.UserProfileUiModel
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
fun ProfileScreen(
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshLocalState(context)
    }

    ProfileScreenContent(
        uiState = uiState,
        onLockClick = viewModel::showLockDialog,
        onDismissLockDialog = viewModel::dismissLockDialog,
        onConfirmLock = viewModel::confirmLockSession,
        onSignOutClick = viewModel::showSignOutDialog,
        onDismissSignOutDialog = viewModel::dismissSignOutDialog,
        onConfirmSignOut = { viewModel.confirmSignOut(context) },
        onDismissStatusMessage = viewModel::dismissStatusMessage,
        onDuressSetupClick = viewModel::showDuressSetupDialog,
        onDismissDuressSetup = viewModel::dismissDuressSetupDialog,
        onConfirmDuressSetup = { pin -> viewModel.configureDuressPin(context, pin) },
        onDuressActivateClick = viewModel::showDuressActivateDialog,
        onDismissDuressActivate = viewModel::dismissDuressActivateDialog,
        onConfirmDuressActivate = { username, pin -> viewModel.activateDuress(context, username, pin) },
        onResetBaselineClick = { viewModel.resetBehaviouralBaseline(context) },
        modifier = modifier
    )
}

@Composable
fun ProfileScreenContent(
    uiState: ProfileUiState,
    onLockClick: () -> Unit,
    onDismissLockDialog: () -> Unit,
    onConfirmLock: () -> Unit,
    onSignOutClick: () -> Unit,
    onDismissSignOutDialog: () -> Unit,
    onConfirmSignOut: () -> Unit,
    onDismissStatusMessage: () -> Unit,
    onDuressSetupClick: () -> Unit = {},
    onDismissDuressSetup: () -> Unit = {},
    onConfirmDuressSetup: (String) -> Unit = {},
    onDuressActivateClick: () -> Unit = {},
    onDismissDuressActivate: () -> Unit = {},
    onConfirmDuressActivate: (String, String) -> Unit = { _, _ -> },
    onResetBaselineClick: () -> Unit = {},
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
                    text = "Profile & Settings",
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Identity, Cryptographic Hardware & Sessions",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        }

        // Status Feedback Banner
        if (uiState.statusMessage != null) {
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
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = PrimaryPurple,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = uiState.statusMessage,
                            color = TextMain,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    IconButton(
                        onClick = onDismissStatusMessage,
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

        // Customer Profile Card
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
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .background(PrimaryPurpleGlow, shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = PrimaryPurple,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = uiState.userProfile.fullName,
                            color = TextMain,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = uiState.userProfile.email,
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "User ID: ${uiState.userProfile.userId}",
                            color = SecondaryViolet,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Enrolled Device Card
        item {
            InfoSectionCard(
                title = "Hardware & Device Attestation",
                icon = Icons.Default.PhoneAndroid,
                rows = listOf(
                    "Device Model" to uiState.userProfile.currentDeviceName,
                    "OS Version" to uiState.userProfile.osVersion,
                    "Enrolled Devices" to "${uiState.userProfile.enrolledDevicesCount} active device",
                    "Hardware Isolation" to "StrongBox / TEE Bound"
                )
            )
        }

        // Security Limits & Preferences
        item {
            InfoSectionCard(
                title = "Security Policies",
                icon = Icons.Default.Speed,
                rows = listOf(
                    "Daily Transfer Limit" to uiState.userProfile.formattedDailyLimit,
                    "Coercion Defense" to "Armed (Simulated Honeypot)",
                    "Biometric Prompt" to "Class 3 (Strong Cryptographic)",
                    "Passwordless Mode" to "Enforced (Zero Plaintext Passwords)"
                )
            )
        }

        // Prototype Build Information Card
        item {
            InfoSectionCard(
                title = "Prototype Architecture",
                icon = Icons.Default.Info,
                rows = listOf(
                    "Build Version" to uiState.userProfile.appVersion,
                    "Design System" to "NeoSecure Dark Theme",
                    "Problem Statement" to "PS-05 Coercion-Aware Auth",
                    "Security Framework" to "FIDO2 / WebAuthn + StrongBox"
                )
            )
        }

        // Session & Coercion Actions
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SafeBankButton(
                    text = if (uiState.duressConfigured) "Duress PIN Configured" else "Configure Duress PIN",
                    onClick = onDuressSetupClick,
                    variant = SafeBankButtonVariant.Primary,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = TextMain,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                SafeBankButton(
                    text = "Demo: Activate Duress PIN (Scenario C)",
                    onClick = onDuressActivateClick,
                    variant = SafeBankButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )

                SafeBankButton(
                    text = if (uiState.behaviouralEnrolmentComplete) "Demo Reset: Clear Behavioural Baseline" else "Behavioural Baseline: Not Enrolled",
                    onClick = onResetBaselineClick,
                    variant = SafeBankButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )

                SafeBankButton(
                    text = "Lock Current Session",
                    onClick = onLockClick,
                    variant = SafeBankButtonVariant.Secondary,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = PrimaryPurple,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                SafeBankButton(
                    text = "Sign Out & Revoke Credentials",
                    onClick = onSignOutClick,
                    variant = SafeBankButtonVariant.Critical,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            tint = TextMain,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (uiState.isDuressSetupDialogVisible) {
        DuressPinSetupDialog(
            isLoading = uiState.isBusy,
            onConfirm = onConfirmDuressSetup,
            onDismiss = onDismissDuressSetup
        )
    }

    if (uiState.isDuressActivateDialogVisible) {
        var username by remember { mutableStateOf("usr_dev_alex_chen") }
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismissDuressActivate,
            containerColor = CardBackground,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text("Demo: Trigger Duress PIN", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter username and configured Duress PIN to verify server restriction and bank console alert.",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                    SafeBankTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = "Username",
                        placeholder = "usr_dev_alex_chen"
                    )
                    SafeBankTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = "Duress PIN",
                        placeholder = "••••••",
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
            },
            confirmButton = {
                SafeBankButton(
                    text = "Verify & Activate",
                    onClick = { onConfirmDuressActivate(username, pin) },
                    variant = SafeBankButtonVariant.Critical,
                    isLoading = uiState.isBusy,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                SafeBankButton(
                    text = "Cancel",
                    onClick = onDismissDuressActivate,
                    variant = SafeBankButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }

    // Lock Session Dialog
    if (uiState.isLockDialogVisible) {
        ConfirmationDialog(
            title = "Lock Session",
            message = "Locking will invalidate your active session token. You will need to re-authenticate with your passkey or biometric prompt.",
            confirmText = "Lock Session",
            cancelText = "Cancel",
            onConfirm = onConfirmLock,
            onDismiss = onDismissLockDialog,
            isCritical = false
        )
    }

    // Sign Out Dialog
    if (uiState.isSignOutDialogVisible) {
        ConfirmationDialog(
            title = "Sign Out Device",
            message = "Signing out will clear local session data and revoke your authenticated session on the backend.",
            confirmText = "Sign Out",
            cancelText = "Cancel",
            onConfirm = onConfirmSignOut,
            onDismiss = onDismissSignOutDialog,
            isCritical = true
        )
    }
}

@Composable
private fun InfoSectionCard(
    title: String,
    icon: ImageVector,
    rows: List<Pair<String, String>>
) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = PrimaryPurple,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F142A), shape = RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rows.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label,
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                        Text(
                            text = value,
                            color = TextMain,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
