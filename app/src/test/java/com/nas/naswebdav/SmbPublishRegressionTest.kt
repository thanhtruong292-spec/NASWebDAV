package com.nas.naswebdav

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Only the SMB transport boundary is mocked; SmbManager publication executes unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestNasApplication::class)
class SmbPublishRegressionTest {
    private val share = mockk<DiskShare>(relaxed = true)
    private val staged = mockk<File>(relaxed = true)

    @Before
    fun setUp() {
        mockkObject(SmbManager)
        every { SmbManager.connectAndOpenShare("host", "user", "pass", "share") } returns share
        every { share.fileExists("final") } returns false
        every { share.openFile("staged", any(), any(), any(), any(), any()) } returns staged
        // The visible destination must never become a streaming copy target.
        every { share.openFile("final", any(), any(), any(), any(), any()) } throws IOException("no direct final writes")
    }

    @After
    fun tearDown() = unmockkObject(SmbManager)

    @Test
    fun `complete staged file is published with no replacement and no partial final writes`() = runTest {
        assertTrue(SmbManager.moveNoOverwrite("host", "user", "pass", "share", "staged", "final"))
        verify { staged.rename("final", false) }
        verify { share.openFile("staged", match { AccessMask.DELETE in it }, any(), any(), SMB2CreateDisposition.FILE_OPEN, any()) }
        verify(exactly = 0) { share.openFile("final", any(), any(), any(), any(), any()) }
        verify(exactly = 0) { share.rm(any()) }
    }

    @Test
    fun `publication failure retains the staged bytes without deleting or opening final`() = runTest {
        every { staged.rename("final", false) } throws IOException("destination collision or connection loss")
        assertFalse(SmbManager.moveNoOverwrite("host", "user", "pass", "share", "staged", "final"))
        verify(exactly = 0) { share.openFile("final", any(), any(), any(), any(), any()) }
        verify(exactly = 0) { share.rm(any()) }
    }
}
