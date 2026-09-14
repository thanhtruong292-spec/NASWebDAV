package com.nas.naswebdav.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nas.naswebdav.ui.screens.*

/**
 * Unified screen state wrapper — handles loading, error, empty, and content states.
 *
 * Usage:
 *   NasScreenState(
 *       isLoading = viewModel.isLoading,
 *       errorMessage = viewModel.error,
 *       isEmpty = items.isEmpty(),
 *       emptyIcon = Icons.Outlined.Folder,
 *       emptyTitle = "Thư mục trống",
 *       emptyDescription = "Chưa có tệp nào.",
 *       onRetry = { viewModel.refresh() },
 *   ) {
 *       // Content composable
 *       LazyColumn { ... }
 *   }
 *
 * State priority: Loading → Error → Empty → Content
 */
@Composable
fun NasScreenState(
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    isEmpty: Boolean = false,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    emptyTitle: String = "Không có dữ liệu",
    emptyDescription: String? = null,
    emptyActionText: String? = null,
    onEmptyAction: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            // Loading state — show skeleton
            isLoading -> {
                SkeletonDashboard()
            }

            // Error state — show error with retry
            errorMessage != null -> {
                NasErrorState(
                    message = errorMessage,
                    onRetry = onRetry,
                    details = null,
                )
            }

            // Empty state — show empty with optional CTA
            isEmpty && emptyIcon != null -> {
                NasEmptyState(
                    icon = emptyIcon,
                    title = emptyTitle,
                    description = emptyDescription,
                    actionText = emptyActionText,
                    onAction = onEmptyAction,
                )
            }

            // Content state
            else -> {
                content()
            }
        }
    }
}
