package com.nas.naswebdav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.nas.naswebdav.ui.dialogs.DialogType

/**
 * GlobalUiViewModel — Phase 7d.3
 *
 * Thin VM holding shared UI dialog state that was previously on WebDavViewModel.
 * Used by ~12 UI files for showing common dialog/toast messages.
 *
 * Properties:
 *   - showCommonDialog: whether the dialog is visible
 *   - commonDialogType: SUCCESS / ERROR / WARNING
 *   - commonDialogMessage: the message content
 */
class GlobalUiViewModel : ViewModel() {

    var showCommonDialog by mutableStateOf(false)
        private set

    var commonDialogType by mutableStateOf(DialogType.SUCCESS)
        private set

    var commonDialogMessage by mutableStateOf("")
        private set

    fun show(type: DialogType, message: String) {
        commonDialogType = type
        commonDialogMessage = message
        showCommonDialog = true
    }

    fun dismiss() {
        showCommonDialog = false
        commonDialogMessage = ""
    }

    // CC-005 P1: Snackbar consume-once — thay Toast rời rạc. post() tăng
    // snackbarNonce để UI LaunchedEffect(nonce) show 1 lần rồi consume.
    // Dùng GlobalUiVM đã có (single dialog host ở MainActivity) — không tạo
    // SnackbarHostState thứ hai.
    var snackbarNonce by mutableStateOf(0L)
        private set

    var snackbarMessage by mutableStateOf("")
        private set

    var snackbarActionLabel by mutableStateOf<String?>(null)
        private set

    fun postSnackbar(message: String, actionLabel: String? = null) {
        if (message.isBlank()) return
        snackbarMessage = message
        snackbarActionLabel = actionLabel
        snackbarNonce = System.currentTimeMillis()
    }

    fun consumeSnackbar() {
        snackbarNonce = 0L
        snackbarMessage = ""
        snackbarActionLabel = null
    }
}
