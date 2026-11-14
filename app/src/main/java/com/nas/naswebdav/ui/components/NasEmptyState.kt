package com.nas.naswebdav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nas.naswebdav.ui.screens.*

/**
 * Standardized empty state — centered icon + title + description + optional CTA button.
 *
 * Usage:
 *   NasEmptyState(
 *       icon = Icons.Outlined.FolderOpen,
 *       title = "Thư mục trống",
 *       description = "Chưa có tệp nào trong thư mục này.",
 *       actionText = "Tải lên",
 *       onAction = { viewModel.uploadFile() },
 *   )
 *
 * Pattern:
 * ┌──────────────────────────────┐
 * │            [■ icon]          │
 * │        Chưa có dữ liệu      │  ← title (16sp bold, TextPrimary)
 * │  Mô tả ngắn gọn ở đây      │  ← description (13sp, TextSecondary)
 * │                              │
 * │       [  Hành động  ]       │  ← optional CTA (NasGradientButton)
 * └──────────────────────────────┘
 */
@Composable
fun NasEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    iconTint: androidx.compose.ui.graphics.Color = TextTertiary,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppSpacing.XL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM, Alignment.CenterVertically),
    ) {
        // Large dimmed icon
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(iconTint.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(32.dp),
                tint = iconTint,
            )
        }

        // Title
        Text(
            text = title,
            style = AppTypography.TitleMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )

        // Optional description
        if (description != null) {
            Text(
                text = description,
                style = AppTypography.BodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }

        // Optional CTA button
        if (actionText != null && onAction != null) {
            Spacer(modifier = Modifier.height(AppSpacing.XS))
            NasGradientButton(
                text = actionText,
                onClick = onAction,
            )
        }
    }
}
