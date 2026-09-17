package com.nas.naswebdav.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nas.naswebdav.ui.screens.AccentCyan
import com.nas.naswebdav.ui.screens.DarkElevated
import com.nas.naswebdav.ui.screens.DarkSurface
import com.nas.naswebdav.ui.screens.TextPrimary
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme

/** AMOLED-consistent snackbar host for transient operation feedback. */
@Composable
fun NasSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) { data: SnackbarData ->
        Snackbar(
            snackbarData = data,
            containerColor = DarkElevated,
            contentColor = TextPrimary,
            actionColor = AccentCyan,
            dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(8.dp),
        )
    }
}
