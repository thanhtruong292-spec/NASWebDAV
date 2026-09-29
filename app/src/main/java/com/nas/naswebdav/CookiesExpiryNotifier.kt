package com.nas.naswebdav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * CookiesExpiryNotifier — cảnh báo cookies TikTok sắp hết / đã hết.
 *
 * Được gọi từ 2 nơi:
 *  - LivestreamViewModel.fetchTikTokLiveWatch() mỗi lần mở app / foreground refresh.
 *  - CookiesExpiryWorker (WorkManager, mỗi 60 phút) kể cả khi app đóng.
 *
 * Chống spam: mỗi trạng thái xấu chỉ báo 1 lần / 24h (lưu SharedPreferences).
 */
object CookiesExpiryNotifier {
    const val CHANNEL_ID = "cookies_expiry_channel"
    const val NOTIFICATION_ID = 9030
    private const val PREFS = "cookies_expiry_notif"
    private const val KEY_LAST = "last_notified_key"
    private const val KEY_LAST_TS = "last_notified_ts"
    private const val COOLDOWN_MS = 24L * 60 * 60 * 1000

    private val BAD_STATUSES = setOf("missing", "expired", "expiring_soon", "revoked")

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cookies TikTok",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Cảnh báo cookies TikTok sắp hết hạn hoặc đã hết (watcher mù)"
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /**
     * @param status cookies_status từ backend: valid / expiring_soon / expired / revoked / missing / unknown
     * @param message cookies_message từ backend
     * @param expiryStr cookies_expiry_str (dd/MM/yyyy HH:mm), có thể rỗng
     * @param daysLeft cookies_days_left, âm = không rõ
     * @return true nếu đã bắn notification
     */
    fun checkAndNotify(
        context: Context,
        status: String,
        message: String,
        expiryStr: String,
        daysLeft: Double
    ): Boolean {
        if (status !in BAD_STATUSES) return false
        val appContext = context.applicationContext ?: context

        val (title, body) = when (status) {
            "expiring_soon" -> {
                val when_ = if (expiryStr.isNotBlank()) " ($expiryStr)" else ""
                val left = if (daysLeft >= 0) " — còn ~${formatDays(daysLeft)}" else ""
                "Cookies TikTok sắp hết hạn$when_$left" to
                    "Xuất lại cookies.txt trước khi watcher mù, kẻo hụt live. $message".trim()
            }
            "expired" -> "Cookies TikTok đã hết hạn" to
                "Watcher đang mù, không ghi được live. Xuất lại cookies.txt ngay. $message".trim()
            "revoked" -> "Cookies TikTok bị thu hồi" to
                "TikTok đã logout session — watcher mù. Xuất lại cookies.txt. $message".trim()
            else -> "Chưa có cookies.txt" to
                "Watcher không phát hiện được live. Đặt cookies.txt vào WebDAV root. $message".trim()
        }

        // Chống spam: cùng 1 key trong 24h thì bỏ qua.
        val key = "$status|$expiryStr|$message"
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastKey = prefs.getString(KEY_LAST, "")
        val lastTs = prefs.getLong(KEY_LAST_TS, 0L)
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastTs < COOLDOWN_MS) return false

        createChannel(appContext)
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            val notif = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return false
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notif)
            prefs.edit().putString(KEY_LAST, key).putLong(KEY_LAST_TS, now).apply()
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun formatDays(days: Double): String {
        return if (days < 1) "dưới 1 ngày" else "${"%.1f".format(days)} ngày"
    }

    /** Gọi khi backend báo valid trở lại để lần sau vẫn báo nếu hết tiếp. */
    fun clearLatch(context: Context) {
        try {
            context.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()?.remove(KEY_LAST)?.remove(KEY_LAST_TS)?.apply()
        } catch (_: Exception) {}
    }
}
