package com.nas.naswebdav.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nas.naswebdav.ui.screens.*

/**
 * Single shimmer skeleton element.
 * Animated gradient sweeps left → right across [SkeletonBase] → [SkeletonHighlight].
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    width: Dp = Dp.Unspecified,
    height: Dp = 16.dp,
    cornerRadius: Dp = 4.dp,
) {
    val shimmerTransition = rememberInfiniteTransition(label = "skeleton")
    val shimmerOffset by shimmerTransition.animateFloat(
        initialValue = -300f,
        targetValue = 300f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )

    val brush = Brush.linearGradient(
        colors = listOf(SkeletonBase, SkeletonHighlight, SkeletonBase),
        start = Offset(shimmerOffset, 0f),
        end = Offset(shimmerOffset + 200f, 0f),
    )

    Box(
        modifier = modifier
            .then(if (width != Dp.Unspecified) Modifier.width(width) else Modifier)
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush)
    )
}

/**
 * Dashboard header skeleton — NAS name + status + version.
 *
 * Layout:
 * ┌────────────────────────────────────┐
 * │ [========= 60% =========]         │  ← title (24dp)
 * │ [=== 30% ===]                      │  ← subtitle (14dp)
 * │ [==== 40% ====] ● [== 20% ==]     │  ← status row
 * └────────────────────────────────────┘
 */
@Composable
fun SkeletonHeader(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(AppSpacing.XL),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM),
    ) {
        SkeletonBlock(width = 180.dp, height = 24.dp, cornerRadius = 4.dp)
        SkeletonBlock(width = 100.dp, height = 14.dp, cornerRadius = 4.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
            SkeletonBlock(width = 60.dp, height = 14.dp, cornerRadius = 4.dp)
            SkeletonBlock(width = 80.dp, height = 14.dp, cornerRadius = 4.dp)
        }
    }
}

/**
 * Metric card skeleton — icon + label + value.
 *
 * Layout:
 * ┌───────────────────────────┐
 * │ [■]  [=== 40% ===]       │
 * │      [==== 60% ====]     │
 * └───────────────────────────┘
 */
@Composable
fun SkeletonMetricCard(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.LG, vertical = AppSpacing.MD),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.MD),
    ) {
        SkeletonBlock(width = 20.dp, height = 20.dp, cornerRadius = 4.dp)
        Column(
            verticalArrangement = Arrangement.spacedBy(AppSpacing.XS),
            modifier = Modifier.weight(1f),
        ) {
            SkeletonBlock(width = 60.dp, height = 10.dp, cornerRadius = 4.dp)
            SkeletonBlock(width = 100.dp, height = 16.dp, cornerRadius = 4.dp)
        }
    }
}

/**
 * Service card skeleton — icon + name + status badge.
 *
 * Layout:
 * ┌─────────────────────────────────────┐
 * │ [■] [==== 40% ====]  [== 20% ==]   │
 * └─────────────────────────────────────┘
 */
@Composable
fun SkeletonServiceRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.LG, vertical = AppSpacing.SM),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.MD),
    ) {
        SkeletonBlock(width = 18.dp, height = 18.dp, cornerRadius = 9.dp)
        SkeletonBlock(width = 80.dp, height = 14.dp, cornerRadius = 4.dp, modifier = Modifier.weight(1f))
        SkeletonBlock(width = 50.dp, height = 14.dp, cornerRadius = 4.dp)
    }
}

/**
 * Storage bar skeleton — full-width segmented bar.
 *
 * Layout:
 * ┌──────────────────────────────────────────┐
 * │ [======== full width =========]          │
 * │ [=== 40% ===]              [=== 30% ===] │
 * └──────────────────────────────────────────┘
 */
@Composable
fun SkeletonStorageBar(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = AppSpacing.LG, vertical = AppSpacing.SM),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.XS),
    ) {
        SkeletonBlock(height = 8.dp, cornerRadius = 4.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM)) {
            SkeletonBlock(width = 80.dp, height = 12.dp, cornerRadius = 4.dp)
            Spacer(Modifier.weight(1f))
            SkeletonBlock(width = 60.dp, height = 12.dp, cornerRadius = 4.dp)
        }
    }
}

/**
 * Full dashboard skeleton — composes all skeleton sections with proper spacing.
 * Shown while initial data loads (first 1-3 seconds).
 *
 * Pattern:
 * SkeletonHeader → SkeletonStorageBar → [SkeletonMetricCard × 2] → [SkeletonServiceRow × 3]
 */
@Composable
fun SkeletonDashboard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = AppSpacing.SM),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.SM),
    ) {
        // Header skeleton
        SkeletonHeader()

        // Storage skeleton
        SkeletonStorageBar()

        // Metrics grid skeleton (2 columns)
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.XL),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.SM),
        ) {
            SkeletonMetricCard(modifier = Modifier.weight(1f))
            SkeletonMetricCard(modifier = Modifier.weight(1f))
        }

        // Services skeleton
        NasSectionHeader(title = "••••••••••••", titleColor = TextTertiary)
        repeat(3) {
            SkeletonServiceRow()
        }
    }
}
