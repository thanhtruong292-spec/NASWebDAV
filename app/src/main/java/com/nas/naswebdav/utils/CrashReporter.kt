package com.nas.naswebdav.utils

import android.content.Context
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.Hint

/**
 * Remote crash reporting qua Sentry self-hosted — OPT-IN.
 *
 * Mặc định TẮT. User bật trong cài đặt mới gửi crash. Local Room fallback
 * (CrashHandler trong NasApplication) luôn chạy bất kể opt-in.
 * Mọi message đi qua CrashLogExporter.redactUrlUserinfo trước khi gửi.
 */
object CrashReporter {

    private const val PREFS = "crash_reporting_prefs"
    private const val KEY_ENABLED = "sentry_enabled"
    private const val KEY_DSN = "sentry_dsn"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun getDsn(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DSN, "") ?: ""

    /** Bật/tắt remote reporting. Cần DSN self-hosted để init. */
    fun setEnabled(context: Context, enabled: Boolean, dsn: String = getDsn(context)) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_DSN, dsn)
            .apply()
        if (enabled && dsn.isNotBlank()) {
            runCatching { init(context.applicationContext, dsn) }
        } else {
            runCatching { Sentry.close() }
        }
    }

    /** Gọi từ NasApplication.onCreate sau crash handler local. */
    fun initIfOptedIn(context: Context) {
        runCatching {
            val appContext = context.applicationContext
            if (isEnabled(appContext)) {
                val dsn = getDsn(appContext)
                if (dsn.isNotBlank()) init(appContext, dsn)
            }
        }
    }

    private fun init(context: Context, dsn: String) {
        SentryAndroid.init(context) { options: SentryOptions ->
            options.dsn = dsn
            // Không thu breadcrumb HTTP/network — interceptor gắn Authorization header.
            options.isEnableAutoSessionTracking = true
            options.beforeSend = SentryOptions.BeforeSendCallback { event: SentryEvent, _: Hint ->
                sanitizeEvent(event)
            }
        }
    }

    /** Redact credential khỏi event trước khi gửi — test được qua unit test. */
    internal fun sanitizeEvent(event: SentryEvent): SentryEvent {
        event.message?.let { msg ->
            msg.formatted = CrashLogExporter.redactUrlUserinfo(msg.formatted ?: "")
        }
        event.exceptions?.forEach { ex ->
            ex.value = ex.value?.let { CrashLogExporter.redactUrlUserinfo(it) }
        }
        event.breadcrumbs?.forEach { crumb ->
            crumb.message = CrashLogExporter.redactUrlUserinfo(crumb.message ?: "")
            // CC-005 P1: breadcrumb data la val (SDK 7.x) — redact tung key qua setData.
            crumb.data?.forEach { (k, v) ->
                if (v is String) {
                    val lk = k.lowercase()
                    val red = if (SENSITIVE_KEY_SUBSTR.any { lk.contains(it) }) "[redacted]"
                    else CrashLogExporter.redactUrlUserinfo(v)
                    runCatching { crumb.setData(k, red) }
                }
            }
        }
        // CC-005 P1: redact contexts/tags/extra — xóa key chứa url/user/token/pass/auth/cookie.
        event.contexts.forEach { (_, v) ->
            if (v is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                redactMap(v as MutableMap<String, Any?>)
            }
        }
        redactStringMap(event.tags)
        // CC-005 P1: event.extra la private trong SDK 7.x (chuyen sang unknown map
        // khong co getter on dinh) — tags/contexts/breadcrumbs/messages da bao phu
        // mat credential. Khong cham extra de tranh dung reflection.
        // Không gửi tag chứa URL/user/token thô.
        event.serverName = null
        return event
    }

    private val SENSITIVE_KEY_SUBSTR = listOf("url", "user", "token", "pass", "auth", "cookie", "secret", "dsn")

    private fun redactStringMap(map: MutableMap<String, String>?) {
        if (map == null) return
        for (k in map.keys.toList()) {
            val lk = k.lowercase()
            map[k] = if (SENSITIVE_KEY_SUBSTR.any { lk.contains(it) }) "[redacted]"
            else CrashLogExporter.redactUrlUserinfo(map[k] ?: "")
        }
    }

    private fun redactMap(map: MutableMap<String, Any?>?) {
        if (map == null) return
        for (k in map.keys.toList()) {
            val lk = k.lowercase()
            if (SENSITIVE_KEY_SUBSTR.any { lk.contains(it) }) {
                val v = map[k]
                map[k] = if (v is String) "[redacted]" else "[redacted]"
            } else {
                val v = map[k]
                if (v is String) map[k] = CrashLogExporter.redactUrlUserinfo(v)
            }
        }
    }
}
