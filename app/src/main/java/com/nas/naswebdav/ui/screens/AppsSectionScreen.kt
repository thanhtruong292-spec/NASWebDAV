package com.nas.naswebdav.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nas.naswebdav.ui.components.NasSectionHeader

/**
 * App card — icon + name + description in a rounded card.
 */
@Composable
private fun AppCard(
    icon: ImageVector,
    name: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: androidx.compose.ui.graphics.Color = AccentCyan,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppSpacing.MD))
            .background(DarkCard)
            .clickable { onClick() }
            .padding(AppSpacing.LG),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = name,
            modifier = Modifier.size(32.dp),
            tint = iconTint,
        )
        Text(
            text = name,
            style = AppTypography.BodyLarge,
            color = TextPrimary,
        )
        Text(
            text = description,
            style = AppTypography.BodySmall,
            color = TextSecondary,
            maxLines = 2,
        )
    }
}

/**
 * Apps section — grid of available NAS applications.
 *
 * Pattern:
 * ┌──────────────────────────────────────────┐
 * │       ỨNG DỤNG                          │
 * │ ┌──────────────────┐ ┌──────────────────┐│
 * │ │ 🗂️               │ │ 🔗               ││
 * │ │ Smart Organizer  │ │ Guest Pass       ││
 * │ │ Sắp xếp tệp     │ │ Chia sẻ tạm     ││
 * │ └──────────────────┘ └──────────────────┘│
 * │ ┌──────────────────┐ ┌──────────────────┐│
 * │ │ 📱               │ │ 🎬               ││
 * │ │ Social Extractor │ │ Quay màn hình    ││
 * │ │ Tải MXH         │ │ Ghi lại thao tác ││
 * │ └──────────────────┘ └──────────────────┘│
 * └──────────────────────────────────────────┘
 */
@Composable
fun AppsSectionScreen(
    onOpenOrganizer: () -> Unit = {},
    onOpenGuestPass: () -> Unit = {},
    onOpenSocialExtractor: () -> Unit = {},
    onStartScreenRecord: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = AppSpacing.MD),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM),
    ) {
        NasSectionHeader(
            title = "ỨNG DỤNG",
            titleColor = PanelTitleCyan,
        )

        // App grid — 2 columns
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.LG),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM),
        ) {
            AppCard(
                icon = AppIcons.Folder,
                name = "Smart Organizer",
                description = "Sắp xếp và phân loại tệp tự động",
                onClick = onOpenOrganizer,
                iconTint = AccentPurple,
                modifier = Modifier.weight(1f),
            )
            AppCard(
                icon = AppIcons.Lock,
                name = "Guest Pass",
                description = "Chia sẻ tạm thời với khách",
                onClick = onOpenGuestPass,
                iconTint = AccentGreen,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.LG),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM),
        ) {
            AppCard(
                icon = AppIcons.Cloud,
                name = "Social Extractor",
                description = "Tải video từ mạng xã hội",
                onClick = onOpenSocialExtractor,
                iconTint = AccentPink,
                modifier = Modifier.weight(1f),
            )
            AppCard(
                icon = AppIcons.Play,
                name = "Quay màn hình",
                description = "Ghi lại thao tác trên thiết bị",
                onClick = onStartScreenRecord,
                iconTint = AccentRed,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
