package com.nas.naswebdav.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nas.naswebdav.ui.screens.*

/**
 * Consistent section header for dashboard and settings screens.
 *
 * Layout: [TITLE]                    [count] [action]
 * - Title: uppercase, letter-spaced, bold
 * - Count: optional badge (e.g., "3 services")
 * - Action: optional "See all" / "Manage" text button
 *
 * Replaces inline Text("SECTION NAME", ...) scattered across the codebase.
 */
@Composable
fun NasSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: Color = PanelTitleCyan,
    count: Int? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.LG, vertical = AppSpacing.SM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = AppTypography.SectionTitle.copy(color = titleColor),
            modifier = Modifier.weight(1f),
        )

        if (count != null) {
            Text(
                text = "$count",
                style = AppTypography.LabelSmall.copy(
                    color = TextTertiary,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier.padding(end = AppSpacing.XS),
            )
        }

        if (actionText != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = AppSpacing.XS, vertical = 0.dp),
            ) {
                Text(
                    text = actionText,
                    style = AppTypography.LabelSmall.copy(
                        color = AccentCyan,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
    }
}
