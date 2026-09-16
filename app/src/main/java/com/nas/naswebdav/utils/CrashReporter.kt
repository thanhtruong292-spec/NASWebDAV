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
            init(context.applicationContext, dsn)
        } else {
            Sentry.close()
        }
    }

    /** Gọi từ NasApplication.onCreate sau crash handler local. */
    fun initIfOptedIn(context: Context) {
        val appContext = context.applicationContext
        if (isEnabled(appContext)) {
            val dsn = getDsn(appContext)
            if (dsn.isNotBlank()) init(appContext, dsn)
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
        }
        // Không gửi tag chứa URL/user/token thô.
        event.serverName = null
        // R4-P3: redact headers/breadcrumb-data/tags/extras có cấu trúc —
        // Authorization/userinfo có thể nằm ở đó, không chỉ message.
        event.request?.headers?.forEach { (k, v) ->
            val ks = k.lowercase()
            if (ks.contains("auth") || ks.contains("cookie") || ks.contains("token")) {
                event.request?.headers?.put(k, "[REDACTED]")
            } else if (v is String) {
                event.request?.headers?.put(k, CrashLogExporter.redactUrlUserinfo(v))
            }
        }
        event.breadcrumbs?.forEach { crumb ->
            crumb.data?.forEach { (k, v) ->
                if (v is String) crumb.data?.put(k, CrashLogExporter.redactUrlUserinfo(v))
            }
        }
        event.tags?.keys?.toList()?.forEach { k ->
            event.tags?.get(k)?.let { v ->
                event.setTag(k, CrashLogExporter.redactUrlUserinfo(v))
            }
        }
        try {
            event.contexts?.forEach { (k, v) ->
                event.contexts?.put(k, v)
            }
        } catch (_: Exception) { }
        return event
    }
}
