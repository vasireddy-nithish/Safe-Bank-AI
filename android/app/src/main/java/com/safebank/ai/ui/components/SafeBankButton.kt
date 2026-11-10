package com.safebank.ai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safebank.ai.ui.theme.CardBackground
import com.safebank.ai.ui.theme.CardBorder
import com.safebank.ai.ui.theme.PrimaryPurple
import com.safebank.ai.ui.theme.StatusCritical
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted

enum class SafeBankButtonVariant {
    Primary,
    Secondary,
    Critical,
    Outline
}

/**
 * SafeBankButton — High-accessibility NeoSecure interactive button.
 * Enforces a minimum 48dp interactive touch target height and width.
 */
@Composable
fun SafeBankButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: SafeBankButtonVariant = SafeBankButtonVariant.Primary,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(12.dp)
    val minHeight = 48.dp

    val buttonModifier = modifier
        .defaultMinSize(minWidth = 48.dp, minHeight = minHeight)
        .heightIn(min = minHeight)
        .semantics { role = Role.Button }

    val contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

    when (variant) {
        SafeBankButtonVariant.Primary -> {
            Button(
                onClick = onClick,
                modifier = buttonModifier,
                enabled = enabled && !isLoading,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryPurple,
                    contentColor = TextMain,
                    disabledContainerColor = PrimaryPurple.copy(alpha = 0.35f),
                    disabledContentColor = TextMuted.copy(alpha = 0.6f)
                ),
                contentPadding = contentPadding
            ) {
                ButtonInnerContent(
                    text = text,
                    isLoading = isLoading,
                    contentColor = TextMain,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon
                )
            }
        }
        SafeBankButtonVariant.Secondary -> {
            Button(
                onClick = onClick,
                modifier = buttonModifier,
                enabled = enabled && !isLoading,
                shape = shape,
                border = BorderStroke(1.dp, CardBorder),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CardBackground,
                    contentColor = TextMain,
                    disabledContainerColor = CardBackground.copy(alpha = 0.5f),
                    disabledContentColor = TextMuted.copy(alpha = 0.4f)
                ),
                contentPadding = contentPadding
            ) {
                ButtonInnerContent(
                    text = text,
                    isLoading = isLoading,
                    contentColor = TextMain,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon
                )
            }
        }
        SafeBankButtonVariant.Critical -> {
            Button(
                onClick = onClick,
                modifier = buttonModifier,
                enabled = enabled && !isLoading,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusCritical,
                    contentColor = TextMain,
                    disabledContainerColor = StatusCritical.copy(alpha = 0.35f),
                    disabledContentColor = TextMuted.copy(alpha = 0.6f)
                ),
                contentPadding = contentPadding
            ) {
                ButtonInnerContent(
                    text = text,
                    isLoading = isLoading,
                    contentColor = TextMain,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon
                )
            }
        }
        SafeBankButtonVariant.Outline -> {
            OutlinedButton(
                onClick = onClick,
                modifier = buttonModifier,
                enabled = enabled && !isLoading,
                shape = shape,
                border = BorderStroke(1.dp, PrimaryPurple),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = PrimaryPurple,
                    disabledContentColor = PrimaryPurple.copy(alpha = 0.4f)
                ),
                contentPadding = contentPadding
            ) {
                ButtonInnerContent(
                    text = text,
                    isLoading = isLoading,
                    contentColor = PrimaryPurple,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon
                )
            }
        }
    }
}

@Composable
private fun ButtonInnerContent(
    text: String,
    isLoading: Boolean,
    contentColor: Color,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?
) {
    if (isLoading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = contentColor,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Processing...",
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leadingIcon?.let {
                it()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            trailingIcon?.let {
                Spacer(modifier = Modifier.width(8.dp))
                it()
            }
        }
    }
}
