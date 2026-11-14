package com.nas.naswebdav.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nas.naswebdav.ui.screens.AccentCyan
import com.nas.naswebdav.ui.screens.AppShapes
import com.nas.naswebdav.ui.screens.DarkInput
import com.nas.naswebdav.ui.screens.OutlineVariantDark
import com.nas.naswebdav.ui.screens.TextPrimary

/**
 * CompactTextField — wrapper `OutlinedTextField` với các tham số optional mà
 * các dialog cũ trong dự án hay dùng (`leadingIcon`, `trailingIcon`,
 * `accentColor`, `textStyle`, `shape`, `keyboardOptions`). Ở đây icon được
 * nhận dưới dạng composable lambda để phù hợp với call-site hiện tại (xem
 * Dialogs.kt:499). Stub này dùng để compile pass khi blob gốc chưa được
 * khôi phục; nên thay bằng bản gốc trong tương lai nếu tìm thấy ở commit.
 */
@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    textStyle: TextStyle? = null,
    accentColor: Color = AccentCyan,
    shape: Shape = AppShapes.Input,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        textStyle = textStyle ?: TextStyle.Default,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accentColor,
            unfocusedBorderColor = OutlineVariantDark,
            focusedLabelColor = accentColor,
            cursorColor = accentColor,
            focusedContainerColor = DarkInput,
            unfocusedContainerColor = DarkInput,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
        ),
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
    )
}
