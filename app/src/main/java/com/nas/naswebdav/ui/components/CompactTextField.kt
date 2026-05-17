package com.nas.naswebdav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CompactTextField — Trường nhập gọn dùng BasicTextField + decoration tùy chỉnh.
 *
 * Lý do tồn tại:
 * - `OutlinedTextField` của Material3 mặc định reserve ~16dp top/bottom cho label
 *   → khi ép `.height(<56dp)` text bị clip ở trên (placeholder/nội dung bị che).
 * - Component này dùng `BasicTextField` + Row decoration căn giữa theo chiều dọc,
 *   nên có thể an toàn ở height tuỳ ý (mặc định 40dp).
 *
 * Visual tương thích với OutlinedTextField: border 1dp đổi màu khi focus,
 * placeholder xám, cursor accent, text sáng.
 *
 * @param accentColor màu border/cursor khi focus (mặc định teal giống app style)
 * @param compactHeight chiều cao tổng, không bị clip kể cả khi rất nhỏ (≥36dp)
 */
@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle? = null,
    accentColor: Color = Color(0xFF00897B),
    placeholderColor: Color = Color(0xFF8892B0),
    textColor: Color = Color(0xFFE8E8E8),
    background: Color = Color.Transparent,
    shape: Shape = RoundedCornerShape(10.dp),
    compactHeight: Dp = 40.dp,
    contentHorizontalPadding: Dp = 10.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = if (focused) accentColor else accentColor.copy(alpha = 0.45f)
    val baseStyle = textStyle ?: LocalTextStyle.current
    val effectiveTextStyle = baseStyle.copy(
        color = textColor,
        fontSize = if (baseStyle.fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) baseStyle.fontSize else 14.sp
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        interactionSource = interaction,
        textStyle = effectiveTextStyle,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        cursorBrush = SolidColor(accentColor),
        modifier = modifier
            .height(compactHeight)
            .background(background, shape)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = contentHorizontalPadding),
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize()
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                    Spacer(Modifier.width(6.dp))
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            color = placeholderColor,
                            style = effectiveTextStyle.copy(color = placeholderColor)
                        )
                    }
                    innerTextField()
                }
                if (trailingIcon != null) {
                    Spacer(Modifier.width(6.dp))
                    trailingIcon()
                }
            }
        }
    )
}

