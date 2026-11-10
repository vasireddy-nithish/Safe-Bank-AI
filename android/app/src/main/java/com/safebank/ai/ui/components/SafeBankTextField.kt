package com.safebank.ai.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safebank.ai.ui.theme.CardBackground
import com.safebank.ai.ui.theme.CardBorder
import com.safebank.ai.ui.theme.PrimaryPurple
import com.safebank.ai.ui.theme.StatusCritical
import com.safebank.ai.ui.theme.TextMain
import com.safebank.ai.ui.theme.TextMuted
import com.safebank.ai.ui.theme.TextPlaceholder

/**
 * SafeBankTextField — NeoSecure high-contrast accessible input field.
 * Minimum 48dp interactive touch target height guaranteed.
 */
@Composable
fun SafeBankTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    enabled: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .heightIn(min = 48.dp)
                .semantics {
                    if (isError && errorMessage != null) {
                        error(errorMessage)
                    }
                },
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            label = label?.let {
                {
                    Text(
                        text = it,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            placeholder = placeholder?.let {
                {
                    Text(
                        text = it,
                        fontSize = 14.sp,
                        color = TextPlaceholder
                    )
                }
            },
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CardBackground,
                unfocusedContainerColor = CardBackground,
                disabledContainerColor = CardBackground.copy(alpha = 0.5f),
                errorContainerColor = CardBackground,
                focusedTextColor = TextMain,
                unfocusedTextColor = TextMain,
                disabledTextColor = TextMuted.copy(alpha = 0.5f),
                focusedBorderColor = PrimaryPurple,
                unfocusedBorderColor = CardBorder,
                focusedLabelColor = PrimaryPurple,
                unfocusedLabelColor = TextMuted,
                cursorColor = PrimaryPurple,
                errorBorderColor = StatusCritical,
                errorLabelColor = StatusCritical,
                errorLeadingIconColor = StatusCritical,
                errorTrailingIconColor = StatusCritical
            )
        )

        if (isError && !errorMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = errorMessage,
                color = StatusCritical,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
