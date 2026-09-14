package com.nas.naswebdav.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nas.naswebdav.ui.screens.*

/**
 * Standardized error state — icon + message + retry button + optional details.
 *
 * Usage:
 *   NasErrorState(
 *       message = "Không thể kết nối NAS",
 *       details = "java.net.ConnectException: Connection refused\n  at ...",
 *       onRetry = { viewModel.retryConnection() },
 *   )
 *
 * Pattern:
 * ┌──────────────────────────────┐
 * │          [⚠ icon]           │
 * │    Không thể kết nối NAS     │  ← message (16sp bold, TextPrimary)
 * │                              │
 * │       [  Thử lại  ]         │  ← retry button (NasGradientButton)
 * │                              │
 * │      ▶ Chi tiết lỗi         │  ← optional expandable details
 * │ ┌──────────────────────────┐ │
 * │ │ java.net.ConnectException│ │  ← monospace error text
 * │ │ Connection refused       │ │
 * │ └──────────────────────────┘ │
 * └──────────────────────────────┘
 */
@Composable
fun NasErrorState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.ErrorOutline,
    details: String? = null,
    onRetry: (() -> Unit)? = null,
    retryText: String = "Thử lại",
    iconTint: androidx.compose.ui.graphics.Color = AccentRed,
) {
    var showDetails by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppSpacing.XL)
            .animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM, Alignment.CenterVertically),
    ) {
        // Error icon
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = iconTint,
        )

        // Error message
        Text(
            text = message,
            style = AppTypography.BodyLarge,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )

        // Retry button
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(AppSpacing.XS))
            NasGradientButton(
                text = retryText,
                onClick = onRetry,
            )
        }

        // Expandable details
        if (details != null) {
            Spacer(modifier = Modifier.height(AppSpacing.XS))

            Text(
                text = if (showDetails) "▼ Ẩn chi tiết" else "▶ Chi tiết lỗi",
                style = AppTypography.BodySmall,
                color = PanelTitleCyan,
                modifier = Modifier.clickable { showDetails = !showDetails },
            )

            if (showDetails) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.XS),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                ) {
                    Text(
                        text = details,
                        modifier = Modifier.padding(AppSpacing.SM),
                        style = AppTypography.LabelSmall,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}
