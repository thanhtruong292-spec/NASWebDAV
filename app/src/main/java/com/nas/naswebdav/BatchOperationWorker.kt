package com.nas.naswebdav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asContextElement
import org.json.JSONObject
import java.net.URL
import java.io.File

/**
 * BatchOperationWorker ï¿½?? Foreground Worker cháº¡y ngáº§m cho cÃ¡c tÃ¡c vá»¥ Copy/Move/Delete/Restore hÃ ng loáº¡t.
 *
 * Æ¯u ï¿½?iï¿½?m so vï¿½?i viewModelScope.launch:
 * - Tiáº¿n trÃ¬nh KHï¿½?NG Bï¿½? Há»¦Y khi ngÆ°á»i dÃ¹ng táº¯t App hoáº·c thu nhá» á»©ng dá»¥ng.
 * - Hiï¿½?n thï¿½? thanh tiáº¿n trÃ¬nh trÃªn Notification Bar (Thanh thÃ´ng bÃ¡o) theo thá»i gian thá»±c.
 * - Hï¿½? ï¿½?iá»u hÃ nh Android cáº¥p phÃ¡t Æ°u tiÃªn cao (Foreground Service) ï¿½?? TrÃ¡nh bï¿½? OOM Killer xÃ³a sï¿½?.
 *
 * Input Data:
 *   - "operation" : "COPY" | "MOVE" | "DELETE" | "RESTORE"
 *   - "filePaths" : String[] ï¿½?? danh sÃ¡ch ï¿½?Æ°á»ng dáº«n WebDAV ï¿½?áº§y ï¿½?á»§ (source)
 *   - "fileNames" : String[] ï¿½?? tÃªn hiï¿½?n thï¿½? tÆ°Æ¡ng á»©ng
 *   - "destUrl"   : String   ï¿½?? thÆ° má»¥c ï¿½?Ã­ch (cho COPY/MOVE, khÃ´ng cáº§n cho DELETE)
 *   - "baseUrl"   : String   ï¿½?? WebDAV base URL hiï¿½?n táº¡i (ï¿½?ï¿½? tÃ­nh trash path)
 */
class BatchOperationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "batch_operation_channel"
        const val NOTIFICATION_ID = 9010
        private const val TAG = "BatchOp"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID, "TÃ¡c vá»¥ hÃ ng loáº¡t",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiá»‡n thá»‹ tiáº¿n trÃ¬nh Copy/Move/Delete file trÃªn NAS"
                setShowBadge(false)
            }
            (context.getSystemService(NotificationManager::class.java))
                ?.createNotificationChannel(channel)
        }
    }

    private fun safeDataText(value: String, maxChars: Int = 180): String {
        return if (value.length <= maxChars) value else value.take(maxChars) + "..."
    }

    private fun resolveBatchWebDavPath(rawPath: String, activeBaseUrl: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val active = runCatching { URL(activeBaseUrl) }.getOrNull() ?: return trimmed
            val raw = runCatching { URL(trimmed) }.getOrNull() ?: return trimmed
            return "${active.protocol}://${active.authority}${raw.path}" + (raw.query?.let { "?$it" } ?: "") + (raw.ref?.let { "#$it" } ?: "")
        }
        val base = activeBaseUrl.trimEnd('/')
        return if (trimmed.startsWith('/')) base + trimmed else "$base/$trimmed"
    }

    private fun loadBatchFiles(): Pair<Array<String>, Array<String>> {
        val payloadFile = inputData.getString("payloadFile") ?: ""
        if (payloadFile.isNotEmpty()) {
            try {
                val file = File(payloadFile)
                if (file.exists()) {
                    val items = JSONObject(file.readText()).optJSONArray("files")
                    if (items != null) {
                        val paths = ArrayList<String>(items.length())
                        val names = ArrayList<String>(items.length())
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val path = item.optString("path", "")
                            if (path.isEmpty()) continue
                            paths.add(path)
                            names.add(item.optString("name", path.substringAfterLast("/")))
                        }
                        return paths.toTypedArray() to names.toTypedArray()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Cannot read batch payload", e)
            }
        }
        val filePaths = inputData.getStringArray("filePaths") ?: emptyArray()
        val fileNames = inputData.getStringArray("fileNames") ?: emptyArray()
        return filePaths to fileNames
    }

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val operation = inputData.getString("operation") ?: return Result.failure()
        val (filePaths, fileNames) = loadBatchFiles()
        val destUrl = inputData.getString("destUrl") ?: ""
        if (filePaths.isEmpty()) return Result.success()

        // Káº¿t nï¿½?i WebDAV ï¿½?? sá»­ dá»¥ng SmartNetworkManager ï¿½?ï¿½? chá»n URL ï¿½?ang hoáº¡t ï¿½?ï¿½?ng (LAN hoáº·c Tailscale)
        val user = SecurePrefsHelper.getUser(applicationContext)
        val pass = SecurePrefsHelper.getPass(applicationContext)
        // FIX D3: ÄÃ£ trong withContext(IO) ï¿½?? gá»i suspend fun trá»±c tiáº¿p, khÃ´ng cáº§n runBlocking
        val savedUrl = SmartNetworkManager.getActiveBaseUrl(applicationContext)
            .ifEmpty { SecurePrefsHelper.getUrl(applicationContext) }
        if (savedUrl.isEmpty() || user.isEmpty() || pass.isEmpty()) return Result.failure()

        return withContext(Dispatchers.IO + WebDavManager.threadLocalAuth.asContextElement(WebDavManager.AuthState(savedUrl, user, pass))) {
            val webDavManager = WebDavManager
            val activeBaseUrl = savedUrl
        val db = NasApplication.instance.database
        val trashMetaDao = db.trashMetaDao()

        // Táº¡o Foreground Notification
        createChannel(applicationContext)
        val operationLabel = when (operation) {
            "COPY" -> "Sao chÃ©p"
            "MOVE" -> "Di chuyï¿½?n"
            "DELETE" -> "XÃ³a"
            "RESTORE" -> "KhÃ´i phá»¥c"
            else -> "Xá»­ lÃ½"
        }

        val notificationBuilder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("$operationLabel ${filePaths.size} tï¿½?p")
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        try {
            // Tá»« Android 10 (Q) trï¿½? lÃªn báº¯t buï¿½?c khai bÃ¡o foregroundServiceType
            // khï¿½?p manifest, náº¿u khÃ´ng sáº½ nÃ©m MissingForegroundServiceTypeException.
            setForeground(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    ForegroundInfo(
                        NOTIFICATION_ID, notificationBuilder.build(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    ForegroundInfo(NOTIFICATION_ID, notificationBuilder.build())
                }
            )
        } catch (e: Exception) {
            // FIX CRITICAL: KhÃ´ng fallback sang NotificationManagerCompat.notify()
            // Android 14+ Worker sáº½ bá»‹ kill náº¿u khÃ´ng Ä‘Æ°á»£c setForeground Ä‘Ãºng cÃ¡ch.
            val isFatal = when {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    e.javaClass.name.contains("ForegroundService") || e.javaClass.name.contains("ForegroundServiceType")
                else -> false
            }
            if (isFatal) {
                android.util.Log.e(TAG, "setForeground failed (fatal)", e)
                return@withContext Result.failure()
            }
        }

        val total = filePaths.size
        var successCount = 0
        var failCount = 0
        val trashFolderName = ".trash/"

        // Bá» viï¿½?c chuáº©n bï¿½? ThÃ¹ng rÃ¡c dÃ¹ng chung ï¿½? ï¿½?Ã¢y vÃ¬ Trash giá» phá»¥ thuï¿½?c tá»«ng ï¿½? ï¿½?Ä©a
        
        var lastNotifyUpdate = 0L

        for ((index, filePath) in filePaths.withIndex()) {
            if (isStopped) break

            val fileName = fileNames.getOrElse(index) { filePath.substringAfterLast("/") }
            val displayName = safeDataText(fileName)
            val percentDone = ((index.toFloat() / total) * 100).toInt()

            val now = System.currentTimeMillis()
            if (now - lastNotifyUpdate > 200 || index == 0 || index == total - 1) {
                lastNotifyUpdate = now

                // Cáº­p nháº­t Notification Bar
                notificationBuilder
                    .setContentTitle("$operationLabel (${ index + 1 }/$total)")
                    .setContentText(displayName)
                    .setProgress(100, percentDone, false)
                try {
                    NotificationManagerCompat.from(applicationContext)
                        .notify(NOTIFICATION_ID, notificationBuilder.build())
                } catch (_: SecurityException) {}

                // BÃ¡o cÃ¡o tiáº¿n trÃ¬nh cho UI (náº¿u App ï¿½?ang mï¿½?)
                setProgress(workDataOf(
                    "completed" to index,
                    "total" to total,
                    "currentFile" to displayName,
                    "operation" to operation,
                    "percent" to percentDone
                ))
            }

            try {
                val sourceUrl = resolveBatchWebDavPath(filePath, activeBaseUrl)
                val normalizedDestUrl = if (destUrl.isNotBlank()) resolveBatchWebDavPath(destUrl, activeBaseUrl) else ""
                val isDirectory = sourceUrl.endsWith("/")
                val isInTrash = sourceUrl.contains(trashFolderName)
                when (operation) {
                    "COPY" -> {
                        val safeDestUrl = if (normalizedDestUrl.endsWith("/")) normalizedDestUrl else "${normalizedDestUrl}/"
                        val encodedName = encodeWebDavSegment(fileName)
                        var targetUrl = safeDestUrl + encodedName
                        if (isDirectory && !targetUrl.endsWith("/")) targetUrl += "/"
                        webDavManager.copyFile(sourceUrl, targetUrl)
                        successCount++
                    }
                    "MOVE" -> {
                        val safeDestUrl = if (normalizedDestUrl.endsWith("/")) normalizedDestUrl else "${normalizedDestUrl}/"
                        val encodedName = encodeWebDavSegment(fileName)
                        var targetUrl = safeDestUrl + encodedName
                        if (isDirectory && !targetUrl.endsWith("/")) targetUrl += "/"
                        webDavManager.renameFile(sourceUrl, targetUrl)
                        // DB write riÃªng â€” náº¿u WebDAV thÃ nh cÃ´ng mÃ  DB fail,
                        // váº«n count success (NAS file Ä‘Ã£ di chuyá»ƒn).
                        try {
                            if (sourceUrl.contains(trashFolderName) && !targetUrl.contains(trashFolderName)) {
                                trashMetaDao.deleteByTrashPath(sourceUrl)
                            } else if (!sourceUrl.contains(trashFolderName) && targetUrl.contains(trashFolderName)) {
                                trashMetaDao.insert(TrashMeta(trashPath = targetUrl, originalPath = sourceUrl))
                            }
                        } catch (dbEx: Exception) {
                            android.util.Log.w(TAG, "DB sync failed after MOVE $fileName (NAS OK)", dbEx)
                        }
                        successCount++
                    }
                    "DELETE" -> {
                        if (!isInTrash) {
                            val trashFolderUrl = buildWebDavTrashTargetUrl(activeBaseUrl, sourceUrl, "", false)
                            val targetUrl = buildWebDavTrashTargetUrl(activeBaseUrl, sourceUrl, fileName, isDirectory)
                            try { webDavManager.createFolder(trashFolderUrl) } catch (_: Exception) {}
                            webDavManager.renameFile(sourceUrl, targetUrl)
                            try {
                                trashMetaDao.insert(TrashMeta(trashPath = targetUrl, originalPath = sourceUrl))
                            } catch (dbEx: Exception) {
                                android.util.Log.w(TAG, "DB sync failed after DELETE $fileName (NAS OK)", dbEx)
                            }
                        } else {
                            webDavManager.deleteFile(sourceUrl, isDirectory)
                            try {
                                trashMetaDao.deleteByTrashPath(sourceUrl)
                            } catch (dbEx: Exception) {
                                android.util.Log.w(TAG, "DB sync failed after DELETE (permanent) $fileName", dbEx)
                            }
                        }
                        successCount++
                    }
                    "RESTORE" -> {
                        val targetUrl = try {
                            trashMetaDao.findByTrashPath(sourceUrl)?.originalPath
                        } catch (_: Exception) {
                            null
                        } ?: buildWebDavRestoreTargetUrl(activeBaseUrl, sourceUrl, fileName, isDirectory)
                        webDavManager.renameFile(sourceUrl, targetUrl)
                        try {
                            trashMetaDao.deleteByTrashPath(sourceUrl)
                        } catch (dbEx: Exception) {
                            android.util.Log.w(TAG, "DB sync failed after RESTORE $fileName (NAS OK)", dbEx)
                        }
                        successCount++
                    }
                    else -> {
                        // Unknown operation ? DO NOT increment successCount here.
                        // Only the 4 valid branches above (COPY/MOVE/DELETE/RESTORE) count as success.
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Lï¿½?i $operation file: $fileName", e)
                failCount++
            }
            
            // UX-01: Delay 100ms to prevent NAS WebDAV daemon from hanging during mass I/O
            kotlinx.coroutines.delay(100L)
        }

        // BÃ¡o cÃ¡o káº¿t quáº£ cuï¿½?i cÃ¹ng cho UI
        setProgress(workDataOf(
            "completed" to total,
            "total" to total,
            "currentFile" to "HoÃ n táº¥t",
            "operation" to operation,
            "percent" to 100,
            "successCount" to successCount,
            "failCount" to failCount
        ))

        // Ghi log hï¿½? thï¿½?ng
        try {
            val db = NasApplication.instance.database
            val logType = if (failCount == 0) "SUCCESS" else "WARNING"
            val logMsg = if (failCount == 0) {
                "$operationLabel thÃ nh cÃ´ng $successCount/$total tï¿½?p."
            } else {
                "$operationLabel: $successCount thÃ nh cÃ´ng, $failCount tháº¥t báº¡i."
            }
            db.logDao().insertLog(SystemLog(type = logType, module = "HÃ ng loáº¡t", message = logMsg))
        } catch (_: Exception) {}

        // Hiï¿½?n thï¿½? thÃ´ng bÃ¡o hoÃ n táº¥t (khÃ´ng cÃ²n ongoing)
        val resultText = if (failCount == 0) {
            "HoÃ n táº¥t $operationLabel $successCount tï¿½?p ï¿½??"
        } else {
            "$operationLabel: $successCount thÃ nh cÃ´ng, $failCount lï¿½?i"
        }
        val doneNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(resultText)
            .setContentText(if (failCount > 0) "Mï¿½?t sï¿½? tï¿½?p khÃ´ng thï¿½? xá»­ lÃ½." else "Táº¥t cáº£ tï¿½?p ï¿½?Ã£ ï¿½?Æ°á»£c xá»­ lÃ½ thÃ nh cÃ´ng!")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFICATION_ID, doneNotification.build())
        } catch (_: SecurityException) {}

        try {
            inputData.getString("payloadFile")?.let { File(it).delete() }
        } catch (_: Exception) {}

        // FIX: Náº¿u cÃ³ file tháº¥t báº¡i, return Result.failure(workData) Ä‘á»ƒ WorkManager biáº¿t
        // operation khÃ´ng hoÃ n táº¥t. UI sáº½ nháº­n Ä‘Æ°á»£c `failCount > 0` qua setProgress á»Ÿ trÃªn.
        // LÆ°u Ã½: failure chá»‰ retry khi worker cÃ³ retry policy; vá»›i batch Ä‘Ã£ cháº¡y gáº§n háº¿t
        // thÃ¬ retry sáº½ duplicate work â€” caller nÃªn check failCount.
        return@withContext if (failCount > 0) {
            Result.failure(workDataOf(
                "completed" to total,
                "successCount" to successCount,
                "failCount" to failCount
            ))
        } else {
            Result.success(workDataOf(
                "completed" to total,
                "successCount" to successCount,
                "failCount" to 0
            ))
        }
    }
}
}
