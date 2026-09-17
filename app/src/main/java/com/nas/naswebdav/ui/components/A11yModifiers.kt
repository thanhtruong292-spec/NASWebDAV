package com.nas.naswebdav.ui.components

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Accessibility touch-target helpers.
 * Material recommends at least 48.dp for Android touch targets.
 */
fun Modifier.minTouchTarget(): Modifier = this.defaultMinSize(
    minWidth = 48.dp,
    minHeight = 48.dp
)
