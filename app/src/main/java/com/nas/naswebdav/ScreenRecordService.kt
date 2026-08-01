package com.nas.naswebdav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.nas.naswebdav.utils.SystemLogger
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
    private var startedAtMs = 0L
    private var overlayView: TextView? = null
    private var overlayContainer: LinearLayout? = null
    private var overlayAdded = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var width = 1280
    private var height = 720
    private var density = 320
    private var bitrate = 2_500_000
    private val segmentMs = 10_000L
    private val maxSpoolBytes = 64L * 1024L * 1024L
    private var uploadedSegments = 0
    private var lastProgressLogSegment = -1

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
        if (!Settings.canDrawOverlays(this)) {
            logWarn("Không có quyền hiển thị trên cùng: chip REC sẽ không hiện, quay vẫn tiếp tục")
        }
        isRecordingState.value = true
        elapsedSecondsState.value = 0L
        segmentIndexState.value = 0
        uploadedSegmentsState.value = 0
        pendingSegmentsState.value = 0
        networkModeState.value = "..."
        showRecordingOverlay()
        logInfo("Bắt đầu khởi động quay màn hình")
        val fgTypes = if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Đang khởi động quay màn hình"),
            fgTypes
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }
        apiBase = intent.getStringExtra(EXTRA_API_BASE).orEmpty()
            .ifEmpty { SecurePrefsHelper.getUrl(this).toApiBaseUrl() }
        val user = SecurePrefsHelper.getUser(this)
        val pass = SecurePrefsHelper.getPass(this)
        authHeader = okhttp3.Credentials.basic(user, pass)
        sessionId = "screen_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        spoolDir = File(cacheDir, "screen_record_spool/$sessionId")
        spoolDir.mkdirs()

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        density = metrics.densityDpi
        val rawWidth = metrics.widthPixels
        val rawHeight = metrics.heightPixels
        val longestSide = maxOf(rawWidth, rawHeight)
        val scale = minOf(1.0, 1920.0 / longestSide.toDouble())
        width = ((rawWidth * scale).toInt() / 2) * 2
        height = ((rawHeight * scale).toInt() / 2) * 2
        val onTailscale = isTailscaleUrl(apiBase)
        networkModeState.value = if (onTailscale) "Tailscale" else "LAN"
        bitrate = when {
            onTailscale && maxOf(width, height) >= 1920 -> 4_500_000
            onTailscale -> 3_000_000
            maxOf(width, height) >= 1920 -> 8_000_000
            else -> 5_000_000
        }
        Log.i(TAG, "Start screen recording session=$sessionId raw=${rawWidth}x$rawHeight output=${width}x$height bitrate=$bitrate api=$apiBase")
        logInfo("Tạo phiên quay $sessionId, raw=${rawWidth}x$rawHeight, output=${width}x$height, bitrate=$bitrate, mode=${if (onTailscale) "tailscale" else "lan"}, segment=${segmentMs / 1000}s")

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = if (data != null) manager.getMediaProjection(resultCode, data) else null
        if (projection == null || apiBase.isBlank() || user.isBlank() || pass.isBlank()) {
            Log.e(TAG, "Cannot start screen recording: projection=${projection != null}, apiBaseBlank=${apiBase.isBlank()}, authBlank=${user.isBlank() || pass.isBlank()}")
            logError("Không thể bắt đầu quay: thiếu quyền MediaProjection, API base hoặc thông tin đăng nhập")
            hideRecordingOverlay()
            stopSelf()
            return
        }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopRecording()
            }
        }, null)

        scope.launch {
            try {
                startNasSession()
                // Khởi tạo VirtualDisplay một lần duy nhất cho toàn session
                // Android 14+ cấm gọi createVirtualDisplay() nhiều lần trên cùng projection
                val initRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this@ScreenRecordService)
                    else @Suppress("DEPRECATION") MediaRecorder()
                val firstFile = File(spoolDir, "part_%06d.ts".format(0))
                initRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                initRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)
                initRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                initRecorder.setVideoSize(width, height)
                initRecorder.setVideoFrameRate(30)
                initRecorder.setVideoEncodingBitRate(bitrate)
                initRecorder.setOutputFile(firstFile.absolutePath)
                initRecorder.prepare()
                virtualDisplay = projection?.createVirtualDisplay(
                    "NAS Screen Record",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    initRecorder.surface, null, null
                )
                if (virtualDisplay == null) {
                    initRecorder.release()
                    throw IllegalStateException("Không thể tạo VirtualDisplay")
                }
                initRecorder.start()
                recorder = initRecorder
                currentSegmentFile = firstFile
                Log.i(TAG, "VirtualDisplay created, first segment started file=${firstFile.name}")
                logInfo("Đã tạo VirtualDisplay, bắt đầu ghi segment đầu tiên")
                startedAtMs = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    isRecordingState.value = true
                    elapsedSecondsState.value = 0L
                    segmentIndexState.value = 0
                    uploadedSegmentsState.value = 0
                    pendingSegmentsState.value = 0
                }
                uploadJob = launch { uploadLoop() }
                launch { tickerLoop() }
                segmentJob = launch { segmentLoop() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failRecording("Không thể tạo phiên quay trên NAS", e)
            }
        }
    }

    private suspend fun segmentLoop() {
        try {
            // Segment 0 đã được khởi động bởi startRecording() — bắt đầu từ delay ngay
            while (!stopping.get()) {
                delay(segmentMs)
                if (stopping.get()) break
                val doneFile = currentSegmentFile
                // Tạo recorder mới cho segment tiếp theo trước khi stop recorder cũ
                val nextIndex = segmentIndex + 1
                val nextFile = File(spoolDir, "part_%06d.ts".format(nextIndex))
                val nextRecorder = startNextSegment(nextFile)
                // Stop recorder cũ sau khi recorder mới đã bắt đầu
                stopCurrentRecorder()
                recorder = nextRecorder
                currentSegmentFile = nextFile
                // Đánh dấu segment cũ là sẵn sàng upload
                if (doneFile != null) {
                    if (!doneFile.exists() || doneFile.length() <= 0L) {
                        throw IllegalStateException("MediaRecorder tạo đoạn rỗng: ${doneFile.name}")
                    }
                    File(spoolDir, "part_%06d.ready".format(segmentIndex)).writeText(doneFile.name)
                    Log.i(TAG, "Segment ready index=$segmentIndex bytes=${doneFile.length()}")
                    refreshUploadCounters()
                    if (segmentIndex == 0 || segmentIndex - lastProgressLogSegment >= 5) {
                        logInfo("Segment $segmentIndex sẵn sàng upload, ${doneFile.length() / 1024} KB")
                        lastProgressLogSegment = segmentIndex
                    }
                }
                segmentIndex = nextIndex
                enforceSpoolLimit()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failRecording("Lỗi ghi đoạn quay màn hình", e)
        }
    }

    // Tạo recorder mới và swap surface vào VirtualDisplay hiện có (không tạo lại VirtualDisplay)
    private fun startNextSegment(file: File): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(width, height)
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(bitrate)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        // Swap surface: VirtualDisplay giữ nguyên, chỉ đổi surface đích
        virtualDisplay?.surface = r.surface
        r.start()
        Log.i(TAG, "Next segment started index=${segmentIndex + 1} file=${file.name}")
        return r
    }

    private fun stopCurrentRecorder() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    private fun stopSegment() {
        stopCurrentRecorder()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
    }

    private suspend fun uploadLoop() {
        var consecutiveFailures = 0
        while (!stopping.get() || spoolDir.listFiles()?.any { it.name.endsWith(".ready") } == true) {
            val result = uploadReadySegmentsOnce()
            if (result.failed) {
                consecutiveFailures++
                val exponent = (consecutiveFailures - 1).coerceAtMost(5)
                val backoffMs = minOf(1000L shl exponent, 30_000L)
                delay(backoffMs)
            } else if (!result.progressed) {
                consecutiveFailures = 0
                delay(1000)
            } else {
                consecutiveFailures = 0
            }
        }
    }

    private data class UploadPassResult(val progressed: Boolean, val failed: Boolean)

    private fun uploadReadySegmentsOnce(): UploadPassResult {
        val ready = spoolDir.listFiles()
            ?.filter { it.name.endsWith(".ready") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (ready.isEmpty()) return UploadPassResult(false, false)
        var progressed = false
        var failed = false
        for (marker in ready) {
            val mediaFileName = marker.readText().trim()
            if (mediaFileName.isBlank()) {
                logWarn("Bỏ marker screen-record trống: " + marker.name)
                marker.delete()
                refreshUploadCounters()
                progressed = true
                continue
            }
            val mediaFile = File(spoolDir, mediaFileName)
            val idx = mediaFile.name.substringAfter("part_").substringBefore(".").toIntOrNull()
            if (idx == null) {
                logWarn("Bỏ marker screen-record không hợp lệ: " + marker.name + " -> " + mediaFile.name)
                marker.delete()
                if (mediaFile.exists()) mediaFile.delete()
                refreshUploadCounters()
                progressed = true
                continue
            }
            if (!mediaFile.exists() || mediaFile.length() == 0L) {
                logWarn("Bỏ marker screen-record mồ côi: " + marker.name + " -> " + mediaFile.name)
                marker.delete()
                if (mediaFile.exists()) mediaFile.delete()
                refreshUploadCounters()
                progressed = true
                continue
            }
            if (uploadSegment(idx, mediaFile)) {
                marker.delete()
                mediaFile.delete()
                uploadedSegments = maxOf(uploadedSegments, idx + 1)
                refreshUploadCounters()
                progressed = true
            } else {
                failed = true
            }
        }
        return UploadPassResult(progressed, failed)
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
            if (!response.isSuccessful) throw IllegalStateException("NAS từ chối tạo phiên quay: HTTP ${response.code}")
        }
        Log.i(TAG, "NAS session started session=$sessionId")
        logInfo("NAS đã nhận phiên quay $sessionId")
    }

    private fun uploadSegment(index: Int, file: File): Boolean {
        return try {
            val sha = sha256(file)
            val req = Request.Builder()
                .url("$apiBase/api/screen_record/segment?session_id=$sessionId&index=$index&sha256=$sha&duration_ms=$segmentMs")
                .header("Authorization", authHeader)
                .post(file.asRequestBody("video/MP2T".toMediaType()))
                .build()
            NasApplication.instance.longRunningApiClient.newCall(req).execute().use {
                if (!it.isSuccessful) {
                    Log.w(TAG, "Upload segment failed index=$index http=${it.code}")
                    false
                } else {
                    Log.i(TAG, "Uploaded segment index=$index bytes=${file.length()}")
                    if (index == 0 || index % 5 == 0) {
                        logInfo("Đã upload segment $index, ${file.length() / 1024} KB")
                    }
                    true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Upload segment exception index=$index file=${file.name}", e)
            false
        }
    }

    private fun finishNasSession(): Boolean {
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
        NasApplication.instance.longRunningApiClient.newCall(req).execute().use { response ->
            val ok = response.isSuccessful
            if (ok) {
                Log.i(TAG, "Finish NAS session session=$sessionId totalSegments=$segmentIndex")
                logInfo("Hoàn tất phiên quay $sessionId, tổng $segmentIndex segment")
            } else {
                Log.w(TAG, "Finish NAS session failed session=$sessionId http=${response.code}")
                logWarn("NAS từ chối hoàn tất phiên $sessionId: HTTP ${response.code}")
            }
            return ok
        }
    }

    private fun failRecording(message: String, throwable: Throwable) {
        Log.e(TAG, "$message session=$sessionId segment=$segmentIndex", throwable)
        logError("$message: ${throwable.message ?: throwable.javaClass.simpleName}")
        stopping.set(true)
        scope.launch(Dispatchers.Main) {
            isRecordingState.value = false
        }
        hideRecordingOverlay()
        updateNotification(message)
        scope.launch {
            try {
                cancelNasSession()
            } catch (e: Exception) {
                Log.w(TAG, "Cancel NAS session failed session=$sessionId", e)
            }
            try {
                stopSegment()
            } catch (e: Exception) {
                Log.w(TAG, "stopSegment failed in failRecording", e)
            } finally {
                try { projection?.stop() } catch (_: Exception) {}
                projection = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelNasSession() {
        logWarn("Hủy phiên quay trên NAS: $sessionId")
        val req = Request.Builder()
            .url("$apiBase/api/screen_record/cancel?session_id=$sessionId")
            .header("Authorization", authHeader)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        NasApplication.instance.longRunningApiClient.newCall(req).execute().use { _ -> }
    }
    private fun enforceSpoolLimit() {
        val files = spoolDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.filter { it.isFile }.sumOf { it.length() }
        if (total <= maxSpoolBytes) return
        var dropped = 0
        for (file in files) {
            if (!file.name.endsWith(".ts")) continue
            val marker = File(spoolDir, file.name.replace(".ts", ".ready"))
            total -= file.length()
            if (file.delete()) {
                dropped++
            }
            if (marker.exists()) {
                marker.delete()
            }
            if (total <= maxSpoolBytes) break
        }
        refreshUploadCounters()
        if (dropped > 0) {
            logWarn("Đã xóa $dropped segment cũ do spool vượt giới hạn ${maxSpoolBytes / 1024 / 1024} MB")
        }
    }

    private fun stopRecording() {
        if (!stopping.compareAndSet(false, true)) return
        updateNotification("Đang hoàn tất và đồng bộ lên NAS")
        logInfo("Người dùng dừng quay, đang đồng bộ các segment còn lại")
        scope.launch(Dispatchers.Main) {
            isRecordingState.value = false
        }
        hideRecordingOverlay()
        scope.launch {
            try {
                stopSegment()
                currentSegmentFile?.let { file ->
                    if (file.exists() && file.length() > 0L) {
                        File(spoolDir, "part_%06d.ready".format(segmentIndex)).writeText(file.name)
                        segmentIndex++
                    }
                    currentSegmentFile = null
                    refreshUploadCounters()
                }
                var waitCount = 0
                while (spoolDir.listFiles()?.any { it.name.endsWith(".ready") } == true && waitCount < 30) {
                    val r = uploadReadySegmentsOnce()
                    delay(if (r.failed) 1500L else 1000L)
                    waitCount++
                }
                if (spoolDir.listFiles()?.any { it.name.endsWith(".ready") } == true) {
                    Log.w(TAG, "Timed out waiting for pending screen-record uploads; cancelling NAS session=$sessionId")
                    logWarn("Quá 30 giây vẫn còn segment chưa upload, hủy phiên để dọn tài nguyên")
                    try {
                        cancelNasSession()
                    } catch (e: Exception) {
                        Log.w(TAG, "Cancel NAS session after upload timeout failed session=$sessionId", e)
                    }
                    return@launch
                }
                try {
                    if (!finishNasSession()) {
                        cancelNasSession()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Gặp lỗi khi thông báo hoàn tất phiên lên NAS", e)
                    try {
                        cancelNasSession()
                    } catch (cancelError: Exception) {
                        Log.w(TAG, "Cancel NAS session after finish failure failed session=$sessionId", cancelError)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi dừng quay màn hình", e)
            } finally {
                try { projection?.stop() } catch (_: Exception) {}
                projection = null
                scope.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        isRecordingState.value = false
        hideRecordingOverlay()
        try { stopSegment() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        scope.cancel()
        try { if (::spoolDir.isInitialized) spoolDir.deleteRecursively() } catch (_: Exception) {}
        super.onDestroy()
    }

    private suspend fun tickerLoop() {
        while (!stopping.get()) {
            val elapsed = ((System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)) / 1000L
            val minutes = elapsed / 60L
            val seconds = elapsed % 60L
            updateNotification("Đang quay %02d:%02d - đoạn %d".format(minutes, seconds, segmentIndex + 1))
            withContext(Dispatchers.Main) {
                elapsedSecondsState.value = elapsed
                segmentIndexState.value = segmentIndex
            }
            updateRecordingOverlay(elapsed)
            delay(1000L)
        }
    }

    private fun showRecordingOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showRecordingOverlay() }
            return
        }
        if (overlayAdded) return
        if (!Settings.canDrawOverlays(this)) {
            logWarn("Chưa có quyền hiển thị trên cùng, không thể hiện chip REC toàn màn hình")
            return
        }
        try {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(255, 23, 68))
                }
            }
            val dotSize = (10 * resources.displayMetrics.density).toInt()
            val label = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                text = getString(R.string.recording_overlay_initial)
            }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.argb(220, 20, 20, 20))
                    setStroke(dp(1), Color.rgb(255, 23, 68))
                }
                addView(dot, LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = dp(8)
                })
                addView(label)
            }
            val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(12)
                y = dp(32)
            }
            val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            manager.addView(container, params)
            overlayContainer = container
            overlayView = label
            overlayAdded = true
            logInfo("Đã hiện chip REC toàn màn hình")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot show recording overlay", e)
            logWarn("Không thể hiện chip REC: ${e.message ?: e.javaClass.simpleName}")
            overlayAdded = false
            overlayContainer = null
            overlayView = null
        }
    }

    private fun updateRecordingOverlay(elapsedSeconds: Long) {
        val label = overlayView ?: return
        val minutes = elapsedSeconds / 60L
        val seconds = elapsedSeconds % 60L
        label.post {
            label.text = getString(R.string.recording_overlay_format, minutes, seconds)
        }
    }

    private fun hideRecordingOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hideRecordingOverlay() }
            return
        }
        val container = overlayContainer ?: return
        try {
            val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            manager.removeView(container)
        } catch (_: Exception) {
        } finally {
            overlayAdded = false
            overlayContainer = null
            overlayView = null
        }
    }

    private fun refreshUploadCounters() {
        val pending = spoolDir.listFiles()?.count { it.name.endsWith(".ready") } ?: 0
        mainHandler.post {
            pendingSegmentsState.value = pending
            uploadedSegmentsState.value = uploadedSegments
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun logInfo(message: String) {
        Log.i(TAG, message)
        SystemLogger.log("INFO", TAG, message)
    }

    private fun logWarn(message: String) {
        Log.w(TAG, message)
        SystemLogger.log("WARN", TAG, message)
    }

    private fun logError(message: String) {
        Log.e(TAG, message)
        SystemLogger.log("ERROR", TAG, message)
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
        val channel = NotificationChannel(CHANNEL_ID, "Quay màn hình NAS", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
        private const val CHANNEL_ID = "screen_record_nas"
        private const val NOTIFICATION_ID = 2219
        private const val TAG = "ScreenRecordService"

        // Trạng thái live chia sẻ với UI
        val isRecordingState = androidx.compose.runtime.mutableStateOf(false)
        val elapsedSecondsState = androidx.compose.runtime.mutableStateOf(0L)
        val segmentIndexState = androidx.compose.runtime.mutableStateOf(0)
        val uploadedSegmentsState = androidx.compose.runtime.mutableStateOf(0)
        val pendingSegmentsState = androidx.compose.runtime.mutableStateOf(0)
        val networkModeState = androidx.compose.runtime.mutableStateOf("")
    }
}
