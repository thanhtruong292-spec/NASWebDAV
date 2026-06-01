package com.nas.naswebdav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenRecordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var segmentJob: Job? = null
    private var uploadJob: Job? = null
    private val stopping = AtomicBoolean(false)

    private lateinit var sessionId: String
    private lateinit var apiBase: String
    private lateinit var authHeader: String
    private lateinit var spoolDir: File
    private var segmentIndex = 0
    private var currentSegmentFile: File? = null
    private var width = 1280
    private var height = 720
    private var density = 320
    private var bitrate = 4_000_000
    private val segmentMs = 5_000L
    private val maxSpoolBytes = 2L * 1024L * 1024L * 1024L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }
            ACTION_START -> startRecording(intent)
        }
        return START_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (projection != null) return
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Đang khởi động quay màn hình"),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        apiBase = intent.getStringExtra(EXTRA_API_BASE).orEmpty()
        val user = intent.getStringExtra(EXTRA_USER).orEmpty()
        val pass = intent.getStringExtra(EXTRA_PASS).orEmpty()
        authHeader = okhttp3.Credentials.basic(user, pass)
        sessionId = "screen_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        spoolDir = File(cacheDir, "screen_record_spool/$sessionId")
        spoolDir.mkdirs()

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        density = metrics.densityDpi
        width = metrics.widthPixels.coerceAtMost(1920)
        height = metrics.heightPixels.coerceAtMost(1080)
        if (width % 2 != 0) width--
        if (height % 2 != 0) height--
        bitrate = if (width >= 1800) 6_000_000 else 4_000_000

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = if (data != null) manager.getMediaProjection(resultCode, data) else null
        if (projection == null || apiBase.isBlank()) {
            stopSelf()
            return
        }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopRecording()
            }
        }, null)

        scope.launch {
            startNasSession()
            uploadJob = launch { uploadLoop() }
            segmentJob = launch { segmentLoop() }
        }
    }

    private suspend fun segmentLoop() {
        while (!stopping.get()) {
            val file = File(spoolDir, "part_%06d.ts".format(segmentIndex))
            currentSegmentFile = file
            withContext(Dispatchers.Main.immediate) {
                startSegment(file)
            }
            updateNotification("Đang quay: đoạn ${segmentIndex + 1}")
            delay(segmentMs)
            withContext(Dispatchers.Main.immediate) {
                stopSegment()
            }
            File(spoolDir, "part_%06d.ready".format(segmentIndex)).writeText(file.name)
            currentSegmentFile = null
            segmentIndex++
            enforceSpoolLimit()
        }
    }

    private fun startSegment(file: File) {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(width, height)
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(bitrate)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        virtualDisplay = projection?.createVirtualDisplay(
            "NAS Screen Record",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface,
            null,
            null
        )
        r.start()
        recorder = r
    }

    private fun stopSegment() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
    }

    private suspend fun uploadLoop() {
        while (!stopping.get() || spoolDir.listFiles()?.any { it.name.endsWith(".ready") } == true) {
            val ready = spoolDir.listFiles()
                ?.filter { it.name.endsWith(".ready") }
                ?.sortedBy { it.name }
                ?: emptyList()
            if (ready.isEmpty()) {
                delay(1000)
                continue
            }
            for (marker in ready) {
                val mediaFile = File(spoolDir, marker.readText().trim())
                val idx = mediaFile.name.substringAfter("part_").substringBefore(".").toIntOrNull() ?: continue
                if (mediaFile.exists() && uploadSegment(idx, mediaFile)) {
                    marker.delete()
                    mediaFile.delete()
                } else {
                    delay(2000)
                    break
                }
            }
        }
    }

    private suspend fun startNasSession() {
        val body = JSONObject()
            .put("session_id", sessionId)
            .put("segment_duration_ms", segmentMs)
            .put("width", width)
            .put("height", height)
            .put("bitrate", bitrate)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$apiBase/api/screen_record/start")
            .header("Authorization", authHeader)
            .post(body)
            .build()
        NasApplication.instance.longRunningApiClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("NAS từ chối tạo phiên quay")
        }
    }

    private fun uploadSegment(index: Int, file: File): Boolean {
        return try {
            val sha = sha256(file)
            val req = Request.Builder()
                .url("$apiBase/api/screen_record/segment?session_id=$sessionId&index=$index&sha256=$sha&duration_ms=$segmentMs")
                .header("Authorization", authHeader)
                .post(file.asRequestBody("video/MP2T".toMediaType()))
                .build()
            NasApplication.instance.longRunningApiClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun finishNasSession() {
        val body = JSONObject()
            .put("session_id", sessionId)
            .put("total_segments", segmentIndex)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$apiBase/api/screen_record/finish")
            .header("Authorization", authHeader)
            .post(body)
            .build()
        NasApplication.instance.longRunningApiClient.newCall(req).execute().close()
    }

    private fun enforceSpoolLimit() {
        val files = spoolDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.filter { it.isFile }.sumOf { it.length() }
        if (total <= maxSpoolBytes) return
        for (file in files) {
            if (!file.name.endsWith(".ts")) continue
            val marker = File(spoolDir, file.name.replace(".ts", ".ready"))
            if (marker.exists()) continue
            total -= file.length()
            file.delete()
            if (total <= maxSpoolBytes) break
        }
    }

    private fun stopRecording() {
        if (!stopping.compareAndSet(false, true)) return
        updateNotification("Đang hoàn tất và đồng bộ lên NAS")
        scope.launch {
            withContext(Dispatchers.Main.immediate) { stopSegment() }
            currentSegmentFile?.let { file ->
                if (file.exists() && file.length() > 0L) {
                    File(spoolDir, "part_%06d.ready".format(segmentIndex)).writeText(file.name)
                    segmentIndex++
                }
                currentSegmentFile = null
            }
            while (spoolDir.listFiles()?.any { it.name.endsWith(".ready") } == true) {
                delay(1000)
            }
            finishNasSession()
            try { projection?.stop() } catch (_: Exception) {}
            projection = null
            scope.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        try { stopSegment() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Quay màn hình NAS", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): android.app.Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 20, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Đang quay màn hình vào NAS")
            .setContentText(text)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Dừng", stopPending)
            .build()
    }

    companion object {
        const val ACTION_START = "com.nas.naswebdav.screen.START"
        const val ACTION_STOP = "com.nas.naswebdav.screen.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_API_BASE = "api_base"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        private const val CHANNEL_ID = "screen_record_nas"
        private const val NOTIFICATION_ID = 2219
    }
}
