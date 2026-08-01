package com.nas.naswebdav.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Width categories used to keep layouts readable without stretching components. */
enum class WindowSize {
    Compact,
    Medium,
    Expanded,
}

/**
 * Returns the current window width category.
 *
 * [Configuration.screenWidthDp] is already expressed in density-independent pixels;
 * do not convert it through [android.util.DisplayMetrics.density] a second time.
 */
@Composable
@ReadOnlyComposable
fun currentWindowSize(): WindowSize {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> WindowSize.Compact
        widthDp < 840 -> WindowSize.Medium
        else -> WindowSize.Expanded
    }
}

/** Keeps forms and dense cards usable on wide screens while preserving phone padding. */
@Composable
@ReadOnlyComposable
fun contentMaxWidth(): Dp = when (currentWindowSize()) {
    WindowSize.Compact -> Dp.Unspecified
    WindowSize.Medium -> 560.dp
    WindowSize.Expanded -> 720.dp
}

/** Stable file-grid counts that avoid both cramped phone cells and oversized tablet cells. */
@Composable
@ReadOnlyComposable
fun adaptiveGridColumns(): Int = when (currentWindowSize()) {
    WindowSize.Compact -> 3
    WindowSize.Medium -> 4
    WindowSize.Expanded -> 5
}

@Composable
@ReadOnlyComposable
fun gaugeSize(): Dp = when (currentWindowSize()) {
    WindowSize.Compact -> 56.dp
    WindowSize.Medium -> 64.dp
    WindowSize.Expanded -> 72.dp
}

@Composable
@ReadOnlyComposable
fun chartHeight(
    compact: Dp = 80.dp,
    medium: Dp = 120.dp,
    expanded: Dp = 160.dp,
): Dp = when (currentWindowSize()) {
    WindowSize.Compact -> compact
    WindowSize.Medium -> medium
    WindowSize.Expanded -> expanded
}
