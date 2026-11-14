package com.nas.naswebdav

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Explicit device probe: creates only uniquely named benchmark files, then removes them. */
@RunWith(AndroidJUnit4::class)
class LanPerformanceProbeTest {
    @Test(timeout = 240_000L)
    fun measureAuthenticatedLanConnectionAndWebDavTransfers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val user = SecurePrefsHelper.getUser(context)
        val pass = SecurePrefsHelper.getPass(context)
        assertTrue("Log into the NAS app before running the LAN probe", user.isNotBlank() && pass.isNotBlank())

        var baseUrl = ""
        repeat(3) { run ->
            SmartNetworkManager.invalidateCache()
            val start = SystemClock.elapsedRealtimeNanos()
            baseUrl = SmartNetworkManager.getVerifiedBaseUrl(context)
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            assertTrue("No authenticated NAS route", baseUrl.isNotBlank())
            assertFalse("This probe measures LAN, not Tailscale", isTailscaleUrl(baseUrl))
            emit("connect", run, JSONObject().put("milliseconds", ms))
        }
        WebDavManager.connect(baseUrl, user, pass)

        val source = File.createTempFile("nas-lan-source-", ".bin", context.cacheDir)
        val downloaded = File.createTempFile("nas-lan-download-", ".bin", context.cacheDir)
        try {
            val block = ByteArray(65_536).also { java.util.Random(20261114L).nextBytes(it) }
            source.outputStream().use { output -> repeat(256) { output.write(block) } }
            val expectedHash = digest(source)
            repeat(3) { run ->
                val remoteUrl = baseUrl.trimEnd('/') + "/NAS-LAN-Probe-" + UUID.randomUUID() + ".bin"
                var reserved = false
                try {
                    WebDavManager.uploadStreamIfAbsent(
                        remoteUrl, ByteArrayInputStream(byteArrayOf()), 0L,
                        "application/octet-stream", { _, _ -> }
                    )
                    reserved = true

                    var start = SystemClock.elapsedRealtimeNanos()
                    WebDavManager.uploadFile(remoteUrl, source, "application/octet-stream")
                    val uploadSeconds = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000_000.0
                    start = SystemClock.elapsedRealtimeNanos()
                    WebDavManager.downloadFile(remoteUrl, downloaded)
                    val downloadSeconds = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000_000.0
                    assertEquals(source.length(), downloaded.length())
                    assertTrue("Downloaded bytes differ", expectedHash.contentEquals(digest(downloaded)))
                    emit("transfer", run, JSONObject()
                        .put("bytes", source.length())
                        .put("upload_mib_s", source.length() / 1_048_576.0 / uploadSeconds)
                        .put("download_mib_s", source.length() / 1_048_576.0 / downloadSeconds)
                        .put("configured_upload_limit_bytes_s", AppConfig.UPLOAD_SPEED_LIMIT_BYTES_PER_SEC))
                } finally {
                    if (reserved) {
                        try {
                            WebDavManager.deleteFile(remoteUrl)
                        } catch (error: Exception) {
                            Log.e("NasLanBench", JSONObject().put("phase", "cleanup_failed")
                                .put("path", java.net.URI(remoteUrl).path).toString())
                            throw error
                        }
                    }
                }
            }
        } finally {
            source.delete()
            downloaded.delete()
        }
    }

    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun emit(phase: String, run: Int, values: JSONObject) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        Log.i("NasLanBench", values.put("phase", phase).put("run", run + 1)
            .put("app_version", version).toString())
    }
}
