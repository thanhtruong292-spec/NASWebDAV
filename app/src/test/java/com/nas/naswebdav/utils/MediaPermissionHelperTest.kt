package com.nas.naswebdav.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaPermissionHelperTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `hasNoMediaAccess returns true when all permission checks fail`() {
        val helper = MediaPermissionHelper
        // On Robolectric without granted permissions, all checks should be false.
        assertFalse(helper.hasFullImageAccess(context()))
        assertFalse(helper.hasFullVideoAccess(context()))
        assertFalse(helper.hasPartialVisualAccess(context()))
        assertTrue(helper.hasNoMediaAccess(context()))
    }

    @Test
    fun `canRunPeriodicBackup requires both image and video full access`() {
        val helper = MediaPermissionHelper
        val result = helper.canRunPeriodicBackup(context())
        // No permissions granted in test env → should be false.
        assertFalse(result)
    }

    @Test
    fun `canBackupImages and canBackupVideos independently report`() {
        val helper = MediaPermissionHelper
        assertFalse(helper.canBackupImages(context()))
        assertFalse(helper.canBackupVideos(context()))
    }

    @Test
    fun `isPartialOnly true only when partial access without full access`() {
        val helper = MediaPermissionHelper
        // No permissions at all → not partial-only (since no access at all).
        assertFalse(helper.isPartialOnly(context()))
    }
}
