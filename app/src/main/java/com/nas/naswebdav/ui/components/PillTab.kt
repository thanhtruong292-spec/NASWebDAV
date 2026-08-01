package com.nas.naswebdav.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PillTab(
    selected: Boolean,
    label: String,
    emoji: String = "",
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row {
                if (emoji.isNotEmpty()) {
                    Text(emoji)
                    Spacer(Modifier.width(4.dp))
                }
                Text(label)
            }
        },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = accentColor.copy(alpha = 0.2f),
            selectedLabelColor = accentColor,
        ),
    )
}
