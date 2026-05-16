package com.nas.naswebdav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PillTab — Tab/chip kiểu giống dashboard "Nhiệt độ / Tài nguyên / Mạng".
 *
 * Standard UI pattern cho mọi tab/segment mới trong app (Dialogs, Screens, Cards).
 *
 *  - Nền fill solid, KHÔNG border outline (RoundedCornerShape 8dp)
 *  - Selected: nền [accentColor] alpha 0.5 + red-dot indicator 6dp + chữ trắng BOLD
 *  - Unselected: nền 0xFF0F0F0F + chữ xám 0xFF8892B0 Medium
 *  - Bỏ ripple/indication, click không nhấp nháy
 *  - Optional [emoji] hoặc [icon] prefix
 *
 * Dùng trong [Row] với `Modifier.weight(1f)` để chia đều cho 2-4 tab.
 *
 * @param selected trạng thái active của tab
 * @param label nhãn text hiển thị
 * @param onClick callback khi tap
 * @param modifier modifier ngoài (đa số dùng `Modifier.weight(1f)`)
 * @param accentColor màu accent — selected sẽ dùng alpha 0.5 làm nền
 * @param emoji emoji prefix optional (e.g. "👤", "🔗", "🌡️")
 * @param dotColor màu của dot indicator khi selected (mặc định đỏ 0xFFFF1744)
 * @param fontSize size chữ (mặc định 13.sp)
 * @param height chiều cao tab (mặc định 36.dp)
 * @param iconContent slot cho custom icon thay emoji (nếu cần Material Icon)
 */
@Composable
fun PillTab(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFFEE1D52),
    emoji: String? = null,
    dotColor: Color = Color(0xFFFF1744),
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    height: androidx.compose.ui.unit.Dp = 36.dp,
    iconContent: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) accentColor.copy(alpha = 0.5f) else Color(0xFF0F0F0F),
        modifier = modifier
            .height(height)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Box(Modifier.size(6.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(6.dp))
            }
            if (iconContent != null) {
                iconContent()
                Spacer(Modifier.width(4.dp))
            }
            val displayText = if (emoji != null) "$emoji $label" else label
            Text(
                text = displayText,
                fontSize = fontSize,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else Color(0xFF8892B0),
            )
        }
    }
}
