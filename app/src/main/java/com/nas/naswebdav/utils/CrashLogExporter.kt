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
    const val MAX_CRASH_EXPORTS = 3
    private const val MAX_LOGS = 500
    private const val CRASH_EXPORT_PREFIX = "crash-export-"
    private const val CRASH_EXPORT_SUFFIX = ".txt"
    // Combined: captures scheme + optional username; password (or bare user) replaced with ***.
    // Group 1 = scheme, group 2 = username when "user:pass@" form is present, group 3 = full authority.
    private val URL_USERINFO_REGEX = Regex(
        "(?i)(https?://)(?:([^\\s/@:]+):[^\\s/@]+@|([^\\s/@]+)@)"
    )
    private val SCHEMELESS_URL_USERINFO_REGEX = Regex(
        "(?i)(?<!//)\\b[^\\s/:@]+:[^\\s/@]+@(?=[a-z0-9.-]+(?::\\d+)?(?:[/\\s]|$))"
    )
    private val LABELED_SECRET_REGEX = Regex(
        "(?i)\\b(password|passwd|pass|pwd|token|access[_-]?token|refresh[_-]?token|api[_-]?key|secret)\\b(\\s*[:=]\\s*)([^\\s,;&]+)"
    )
    private val AUTHORIZATION_SECRET_REGEX = Regex(
        "(?i)\\b(authorization)(\\s*[:=]\\s*)(basic|bearer)\\s+([^\\s,;&]+)"
    )

    /**
     * Queries recent crash/error logs and writes a shareable text file in the app cache.
     * Suspend; runs DB + file I/O on Dispatchers.IO.
     */
    suspend fun exportToFile(context: Context, db: AppDatabase): String? = withContext(Dispatchers.IO) {
        try {
            val logs = db.logDao().getRecentCrashes(MAX_LOGS)
            // Leave room for the file about to be written, keeping the total at three.
            pruneOldExports(context.cacheDir, MAX_CRASH_EXPORTS - 1)
            val fileName = "$CRASH_EXPORT_PREFIX${System.currentTimeMillis()}$CRASH_EXPORT_SUFFIX"
            val outputFile = File(context.cacheDir, fileName)
            outputFile.writeText(buildCrashLogText(context, logs), Charsets.UTF_8)
            outputFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Deletes the oldest crash-export files until at most [maxFiles] remain.
     * Only files with the exporter prefix and suffix are eligible for deletion.
     */
    fun pruneOldExports(directory: File, maxFiles: Int = MAX_CRASH_EXPORTS) {
        if (maxFiles < 0) return
        // Newest first so drop(maxFiles) leaves the newest maxFiles and deletes the rest.
        val exports = directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(CRASH_EXPORT_PREFIX) && it.name.endsWith(CRASH_EXPORT_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        exports.drop(maxFiles).forEach { it.delete() }
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
            val safeMessage = redactUrlUserinfo(log.message)
            "${timestampFormat.format(Date(log.timestamp))} [${log.type}] ${log.module}: $safeMessage"
        }
        return header + body + if (body.isNotEmpty()) "\n" else ""
    }

    /** Removes usernames, passwords, tokens, and other credentials from log messages. */
    internal fun redactUrlUserinfo(message: String): String =
        message
            .replace(URL_USERINFO_REGEX) { match ->
                "${match.groupValues[1]}***@"
            }
            .replace(SCHEMELESS_URL_USERINFO_REGEX) {
                "***@"
            }
            .replace(LABELED_SECRET_REGEX) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}***"
            }
            .replace(AUTHORIZATION_SECRET_REGEX) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[3]} ***"
            }
}
