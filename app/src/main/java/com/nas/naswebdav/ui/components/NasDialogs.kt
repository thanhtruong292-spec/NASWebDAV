package com.nas.naswebdav.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nas.naswebdav.ui.screens.*

/**
 * Gradient button đồng bộ với nút "Kết nối NAS" — Cyan → Green trên nền AMOLED.
 * Dùng cho tất cả nút hành động chính trong app.
 * Hiệu ứng shimmer động chạy liên tục từ trái → phải.
 */
@Composable
fun NasGradientButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 50.dp,
    shape: Shape = RoundedCornerShape(24.dp),
    gradientColors: List<Color> = listOf(AccentCyan, AccentCyan, AccentGreen),
    icon: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
    customContent: (@Composable RowScope.() -> Unit)? = null,
) {
    // FIX-AUDIT-#5: tat shimmer lien tuc khi nut bi disable (perf — tranh
    // nhieu infinite animation chay dong thoi tren cac man hinh nhieu nut).
    val shimmerOffset = if (enabled) {
        val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
        val offset by infiniteTransition.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmerOffset"
        )
        offset
    } else 0.5f
    val shimmerBrush = Brush.linearGradient(
        colors = gradientColors,
        start = Offset(shimmerOffset * 1000f, 0f),
        end = Offset(shimmerOffset * 1000f + 600f, 0f)
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = contentPadding,
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(shimmerBrush, shape)
    ) {
        if (customContent != null) {
            customContent()
        } else {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Compact drag handle cho tất cả bottom sheet — đồng bộ visual.
 */
@Composable
fun NasBottomSheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(5.dp)
                .background(TextTertiary, RoundedCornerShape(50))
        )
    }
}

/**
 * NAS flat dark dialog wrapper.
 * - Flat surface (no elevation/shadow), radius 16dp
 * - Consistent padding 24dp
 * - Title bold 18sp, body 14sp
 * - Buttons: end-aligned, primary filled + secondary outlined
 * - Focus first interactive element when dialog opens
 */
@Composable
fun NasAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
    confirmText: String = "Xác nhận",
    dismissText: String = "Hủy",
    onConfirm: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = onDismissRequest,
    focusFirstField: FocusRequester? = null,
) {
    LaunchedEffect(Unit) {
        focusFirstField?.let { runCatching { it.requestFocus() } }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            if (onConfirm != null) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan,
                        contentColor = DarkSurface
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) { Text(confirmText, fontWeight = FontWeight.SemiBold) }
            }
        },
        dismissButton = {
            if (onDismiss != null) {
                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    shape = RoundedCornerShape(10.dp)
                ) { Text(dismissText) }
            }
        },
        title = {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        },
        text = {
            Column(content = content)
        },
        containerColor = DarkCard,
        shape = RoundedCornerShape(16.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = modifier.padding(horizontal = 24.dp)
    )
}

/**
 * Minimal flat bottom sheet wrapper.
 * - Dark surface with 16dp top radius
 * - Safe-area padding for gesture bar
 * - Consistent title + content structure
 */
@Composable
fun NasBottomSheetContent(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkCard, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 24.dp)
            .navigationBarsPadding()
    ) {
        // Handle bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 4.dp)
                    .background(TextTertiary.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
            )
        }

        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        content()
    }
}

/**
 * AMOLED-consistent horizontal divider with subtle color.
 */
@Composable
fun NasHorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
) {
    HorizontalDivider(
        modifier = modifier,
        color = TextTertiary.copy(alpha = 0.2f),
        thickness = thickness,
    )
}

/**
 * AMOLED card wrapper using DarkCard.
 */
@Composable
fun NasCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(content = content)
        }
    } else {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(content = content)
        }
    }
}

/**
 * Standard dialog button row: primary gradient + secondary outlined.
 */
@Composable
fun NasDialogButtonRow(
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String = "Hủy",
    onSecondary: () -> Unit = {},
    primaryEnabled: Boolean = true,
    primaryGradientColors: List<Color> = listOf(AccentCyan, AccentCyan, AccentGreen),
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NasGradientButton(
            onClick = onPrimary,
            text = primaryText,
            modifier = Modifier.weight(1f),
            height = 36.dp,
            shape = RoundedCornerShape(10.dp),
            enabled = primaryEnabled,
            gradientColors = primaryGradientColors,
        )
        OutlinedButton(
            onClick = onSecondary,
            modifier = Modifier.weight(1f),
            enabled = primaryEnabled,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text(secondaryText, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

/**
 * AMOLED-consistent modal bottom sheet wrapper.
 * - Container: DarkSurface (AMOLED black)
 * - Drag handle: NasBottomSheetHandle (standard)
 * - Scrim: DarkSurface 60% alpha
 * - Skip partially expanded for full sheet view
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NasModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: androidx.compose.material3.SheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = DarkSurface,
        scrimColor = DarkSurface.copy(alpha = 0.6f),
        dragHandle = { NasBottomSheetHandle() },
        modifier = modifier,
        content = content,
    )
}
