package com.nas.naswebdav.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * SwipeDeleteRow — Bọc một item trong list để vuốt sang trái xoá.
 *
 * Khi user vuốt từ phải → trái vượt qua [threshold] thì gọi [onDelete] và item
 * bị xoá. Trong lúc vuốt sẽ hiện nền đỏ + icon thùng rác trắng ở mép phải.
 *
 * Để state ổn định khi item bị xoá, nhất định phải bọc trong `key(id) {...}`
 * ở callsite — kèm theo `id` duy nhất của item.
 *
 * @param onDelete callback khi vuốt vượt qua threshold; trả lời `true` thì
 *                 SwipeToDismissBox sẽ giữ trạng thái dismissed, ngược lại snap back.
 *                 Mặc định luôn `true` vì caller thường remove ngay khỏi list.
 * @param requireConfirmation khi `true`, luôn snap-back và chỉ xoá khi [confirmDismiss] trả `true`.
 *                            Dùng khi list đang ở selection mode để tránh xoá nhầm.
 * @param threshold % quãng đường để kích hoạt (0..1), mặc định 0.35.
 * @param shape bo góc của nền đỏ (phải khớp với background của row con).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    threshold: Float = 0.35f,
    backgroundColor: Color = Color(0xFFFF1744).copy(alpha = 0.55f),
    iconTint: Color = Color.White,
    iconSize: Dp = 22.dp,
    shape: Shape = RoundedCornerShape(10.dp),
    backgroundPaddingHorizontal: Dp = 16.dp,
    requireConfirmation: Boolean = false,
    confirmDismiss: (() -> Boolean) = { true },
    content: @Composable () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                if (!requireConfirmation && confirmDismiss()) {
                    onDelete()
                    true
                } else if (requireConfirmation && confirmDismiss()) {
                    onDelete()
                    true
                } else {
                    false
                }
            } else false
        },
        positionalThreshold = { totalDistance -> totalDistance * threshold }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val bgColor by animateColorAsState(
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                    backgroundColor else Color.Transparent,
                label = "swipeDeleteBg"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(bgColor, shape)
                    .padding(horizontal = backgroundPaddingHorizontal),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Xoá",
                    tint = iconTint,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    ) {
        content()
    }
}
