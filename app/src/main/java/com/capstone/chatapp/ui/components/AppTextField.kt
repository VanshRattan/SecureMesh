package com.capstone.chatapp.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Shared text field for the whole app.
 *
 * - [isEmail] = true: strips whitespace and forces lowercase on every keystroke, so
 *   emails can never contain spaces or capital letters (a common source of login bugs).
 * - Cursor / focus indicators come from [MaterialTheme.colorScheme], so they render
 *   correctly in both light and dark mode.
 * - No fixed height: grows with the system font scale without clipping.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isEmail: Boolean = false,
    isPassword: Boolean = false,
    errorText: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    singleLine: Boolean = true,
) {
    val keyboardType = when {
        isEmail -> KeyboardType.Email
        isPassword -> KeyboardType.Password
        else -> KeyboardType.Text
    }

    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            // Email: no spaces, always lowercase.
            val cleaned = if (isEmail) raw.filterNot { it.isWhitespace() }.lowercase() else raw
            onValueChange(cleaned)
        },
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        isError = errorText != null,
        supportingText = errorText?.let { { Text(it) } },
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}
