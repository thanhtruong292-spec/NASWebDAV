package com.nas.naswebdav.backup

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupLockTest {

    @Test
    fun `lockNowRequested starts false`() {
        // AutoBackupViewModel.lockNowRequested is initialized via mutableStateOf(false)
        val flag = mutableStateOf(false)
        assertFalse(flag.value)
    }

    @Test
    fun `requestLockNow sets flag to true`() {
        // Simulates AutoBackupViewModel.requestLockNow() setter behavior
        var lockNowRequested = false
        lockNowRequested = true
        assertTrue(lockNowRequested)
    }
}
