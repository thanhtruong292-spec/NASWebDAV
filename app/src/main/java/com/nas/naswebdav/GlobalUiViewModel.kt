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
        internal set

    var commonDialogType by mutableStateOf(DialogType.SUCCESS)
        internal set

    var commonDialogMessage by mutableStateOf("")
        internal set

    fun show(type: DialogType, message: String) {
        commonDialogType = type
        commonDialogMessage = message
        showCommonDialog = true
    }

    fun dismiss() {
        showCommonDialog = false
    }
}
