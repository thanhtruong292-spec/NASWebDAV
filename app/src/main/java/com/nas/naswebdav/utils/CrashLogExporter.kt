package com.nas.naswebdav.utils

import android.content.Context
import android.os.Build
import com.nas.naswebdav.AppDatabase
import com.nas.naswebdav.BuildConfig
import com.nas.naswebdav.SystemLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exports recent local crash and error logs for support diagnostics. */
object CrashLogExporter {
    private const val MAX_LOGS = 500

    /**
     * Queries recent crash/error logs and writes a shareable text file in the app cache.
     * Suspend; runs DB + file I/O on Dispatchers.IO.
     */
    suspend fun exportToFile(context: Context, db: AppDatabase): String? = withContext(Dispatchers.IO) {
        try {
            val logs = db.logDao().getRecentCrashes(MAX_LOGS)
            val fileName = "crash-export-${System.currentTimeMillis()}.txt"
            val outputFile = File(context.cacheDir, fileName)
            outputFile.writeText(buildCrashLogText(context, logs), Charsets.UTF_8)
            outputFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun buildCrashLogText(context: Context, logs: List<SystemLog>): String {
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val appVersion = BuildConfig.VERSION_NAME
        val buildSha = appVersion.substringAfterLast('(').substringBefore('·').trim()
            .ifBlank { "unknown" }
        val header = buildString {
            appendLine("NAS WebDAV crash and error log")
            appendLine("App version: $appVersion")
            appendLine("Build SHA: $buildSha")
            appendLine("Android version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device model: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Exported: ${timestampFormat.format(Date())}")
            appendLine("Log count: ${logs.size}")
            appendLine()
        }
        val body = logs.joinToString(separator = "\n") { log ->
            "${timestampFormat.format(Date(log.timestamp))} [${log.type}] ${log.module}: ${log.message}"
        }
        return header + body + if (body.isNotEmpty()) "\n" else ""
    }
}
