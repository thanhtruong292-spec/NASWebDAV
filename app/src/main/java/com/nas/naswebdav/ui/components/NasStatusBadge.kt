package com.nas.naswebdav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nas.naswebdav.ui.screens.*

/**
 * Status severity levels mapped to consistent colors across the app.
 * GREEN = healthy/online/running/success
 * ORANGE = warning/degraded/pending/caution
 * RED = error/offline/failure/critical
 * BLUE = informational/active/connecting
 */
enum class StatusLevel {
    Success,
    Warning,
    Error,
    Info,
}

/**
 * Reusable status badge with dot indicator + text label.
 * Used for: Online/Offline, Running/Stopped, Healthy/Degraded, etc.
 *
 * Pattern: ● Status Text
 * - Dot: 6dp circle, colored by level
 * - Text: 11sp semibold, colored by level
 * - Background: optional pill container
 */
@Composable
fun NasStatusBadge(
    level: StatusLevel,
    text: String,
    modifier: Modifier = Modifier,
    showDot: Boolean = true,
    showIcon: Boolean = false,
) {
    val (color, icon) = when (level) {
        StatusLevel.Success -> AccentGreen to Icons.Default.CheckCircle
        StatusLevel.Warning -> AccentOrange to Icons.Default.Warning
        StatusLevel.Error -> AccentRed to Icons.Default.Error
        StatusLevel.Info -> AccentCyan to Icons.Default.Info
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        if (showIcon) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = color,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = text,
            style = AppTypography.LabelSmall.copy(
                color = color,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
        )
    }
}

/**
 * Pill-shaped status badge with colored background.
 * Used for: feature toggles, active/inactive states, severity tags.
 *
 * Example: [● Running]  [⚠ Warning]  [✕ Failed]
 */
@Composable
fun NasStatusPill(
    level: StatusLevel,
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val color = when (level) {
        StatusLevel.Success -> AccentGreen
        StatusLevel.Warning -> AccentOrange
        StatusLevel.Error -> AccentRed
        StatusLevel.Info -> AccentCyan
    }

    Row(
        modifier = modifier
            .clip(AppShapes.Badge)
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = AppSpacing.SM, vertical = AppSpacing.XXS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color)
        )
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(10.dp),
            )
        }
        Text(
            text = text,
            style = AppTypography.LabelSmall.copy(
                color = color,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}
