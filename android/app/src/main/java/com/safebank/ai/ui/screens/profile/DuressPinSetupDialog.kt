package com.safebank.ai.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safebank.ai.ui.components.SafeBankButton
import com.safebank.ai.ui.components.SafeBankButtonVariant
import com.safebank.ai.ui.components.SafeBankTextField
import com.safebank.ai.ui.theme.CardBackground
import com.safebank.ai.ui.theme.CardBorder
import com.safebank.ai.ui.theme.PrimaryPurple
import com.safebank.ai.ui.theme.StatusCritical
import com.safebank.ai.ui.theme.StatusWarning
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted

/**
 * DuressPinSetupDialog — allows the user to configure a Duress PIN.
 *
 * Security: PIN values are displayed as dots (PasswordVisualTransformation).
 * They are NEVER logged to Logcat or any persistent file.
 * The PIN is passed to onConfirm only once and immediately used by the ViewModel.
 */
@Composable
fun DuressPinSetupDialog(
    isLoading: Boolean = false,
    onConfirm: (pin: String) -> Unit,
    onDismiss: () -> Unit
) {
    // Local ephemeral state — PIN never leaves this composable in plaintext logs
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    fun validate(): Boolean {
        return when {
            pin.length < 6 || pin.length > 12 -> {
                pinError = "PIN must be 6–12 digits."
                false
            }
            pin != confirmPin -> {
                pinError = "PINs do not match."
                false
            }
            else -> {
                pinError = null
                true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBackground,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = "Configure Duress PIN",
                color = TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Your Duress PIN is separate from your login. Enter it if you're under coercion — it will silently alert the bank without arousing suspicion.",
                    color = TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                Text(
                    text = "⚠ Cannot be reset without contacting the bank.",
                    color = StatusWarning,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                // PIN input — dots only, never logged
                SafeBankTextField(
                    value = pin,
                    onValueChange = { pin = it; pinError = null },
                    label = "Duress PIN (6–12 digits)",
                    placeholder = "••••••",
                    isError = pinError != null,
                    errorMessage = pinError,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )

                SafeBankTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it; pinError = null },
                    label = "Confirm Duress PIN",
                    placeholder = "••••••",
                    isError = pinError != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
            }
        },
        confirmButton = {
            SafeBankButton(
                text = "Set Duress PIN",
                onClick = {
                    if (validate()) {
                        onConfirm(pin)
                    }
                },
                variant = SafeBankButtonVariant.Primary,
                isLoading = isLoading,
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            )
        },
        dismissButton = {
            SafeBankButton(
                text = "Cancel",
                onClick = onDismiss,
                variant = SafeBankButtonVariant.Secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    )
}
