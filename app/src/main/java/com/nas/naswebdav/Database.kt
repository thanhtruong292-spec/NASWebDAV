package com.nas.naswebdav

import androidx.room.*

@Entity(
    tableName = "files_cache",
    indices = [
        Index(value = ["parentPath"]),
        Index(value = ["contentLength"]), // Phục vụ Stage 1 Hashing nhanh chóng
        Index(value = ["isDirectory"]),
        Index(value = ["name"]) // FIX SEARCH: Index cho tìm kiếm toàn cục nhanh hơn
    ]
)
data class CachedFile(
    @PrimaryKey val path: String,
    val name: String,
    val isDirectory: Boolean,
    val contentType: String?,
    val parentPath: String,
    val contentLength: Long,
    val lastModified: Long,
    val partialHash: String? = null, // Stage 2: Hash 1MB đầu tiên
    val fullHash: String? = null,    // Stage 3: SHA-256 toàn bộ
    val imageFingerprint: String? = null, // PHASE 5: pHash để tìm file mềm cực nhanh
    val timestamp: Long = System.currentTimeMillis(),
    // P2-8 (lop 2): dinh danh phien (user@host:port/root). Query doc loc theo
    // key nay de phien sau khong doc du lieu phien truoc. Row cu (key rong)
    // thuoc phien hien tai cho den khi connect() doi phien xoa sach.
    val accountKey: String = "",
)

@Dao
interface FileDao {
    // P2-8: loc theo accountKey (phien hien tai). Row cu key rong van doc duoc
    // trong phien hien tai (chua doi phien); doi phien -> connect() xoa sach.
    @Query("SELECT * FROM files_cache WHERE parentPath = :path AND (accountKey = :key OR accountKey = '') ORDER BY isDirectory DESC, name COLLATE NOCASE ASC")
    fun getFiles(path: String, key: String = ""): List<CachedFile>

    // KIẾN TRÚC MỚI: Paging 3 cho hàng trăm ngàn tệp tin
    @Query("SELECT * FROM files_cache WHERE parentPath = :path AND (accountKey = :key OR accountKey = '') ORDER BY isDirectory DESC, name COLLATE NOCASE ASC")
    fun getFilesPaged(path: String, key: String = ""): androidx.paging.PagingSource<Int, CachedFile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFiles(files: List<CachedFile>): List<Long>

    @Query("DELETE FROM files_cache WHERE parentPath = :path AND (accountKey = :key OR accountKey = '')")
    fun deleteByParentPath(path: String, key: String = ""): Int

    @Query("DELETE FROM files_cache")
    fun clearAllFiles()

    @Query("SELECT path FROM files_cache WHERE isDirectory = 0 LIMIT 5000")
    fun getAllCachedFilePaths(): List<String>

    // TỐI ƯU OOM: Tránh load toàn bộ List<String> vào RAM
    @Query("SELECT EXISTS(SELECT 1 FROM files_cache WHERE path = :path LIMIT 1)")
    fun exists(path: String): Boolean

    // Dialog Thuoc tinh file dung de tra cuu hash da scan
    @Query("SELECT * FROM files_cache WHERE path = :path LIMIT 1")
    fun getFileByPath(path: String): CachedFile?

    // TỐI ƯU SQL: Loại trừ thư mục .trash để ảnh/video đã xóa không xuất hiện
    @Query("""
    SELECT * FROM files_cache
    WHERE isDirectory = 0
    AND (accountKey = :key OR accountKey = '')
    AND parentPath NOT LIKE '%.trash%' AND parentPath NOT LIKE '%#recycle%' AND parentPath NOT LIKE '%@eaDir%'
    AND (name LIKE '%.jpg' OR name LIKE '%.jpeg' OR name LIKE '%.png' OR name LIKE '%.webp' OR name LIKE '%.heic')
    ORDER BY lastModified DESC LIMIT 100
""")
    fun getLatestPhotos(key: String = ""): List<CachedFile>

    @Query("""
    SELECT * FROM files_cache
    WHERE isDirectory = 0
    AND (accountKey = :key OR accountKey = '')
    AND parentPath NOT LIKE '%.trash%' AND parentPath NOT LIKE '%#recycle%' AND parentPath NOT LIKE '%@eaDir%'
    AND (name LIKE '%.mp4' OR name LIKE '%.mkv' OR name LIKE '%.mov' OR name LIKE '%.avi' OR name LIKE '%.mpg' OR name LIKE '%.mpeg' OR name LIKE '%.wmv' OR name LIKE '%.flv')
    ORDER BY lastModified DESC LIMIT 100
""")
    fun getRecentVideos(key: String = ""): List<CachedFile>

    // TÍNH NĂNG TÌM KIẾM TOÀN CẦU (GLOBAL SEARCH)
    @Query("SELECT * FROM files_cache WHERE (accountKey = :key OR accountKey = '') AND name LIKE '%' || :keyword || '%' ORDER BY isDirectory DESC, name ASC LIMIT 200")
    fun searchFiles(keyword: String, key: String = ""): List<CachedFile>

    // Tìm kiếm giới hạn trong một root path — thay thế pattern load
    // getAllFilesForMap() rồi lọc prefix trong RAM (25k rows → OOM).
    @Query("SELECT * FROM files_cache WHERE (accountKey = :key OR accountKey = '') AND path LIKE :rootPrefix || '%' AND name LIKE '%' || :keyword || '%' ORDER BY isDirectory DESC, name ASC LIMIT 200")
    fun searchFilesUnder(rootPrefix: String, keyword: String, key: String = ""): List<CachedFile>

    // DEAD QUERY (không còn caller): giữ để tương thích, không dùng cho flow mới.
    // Flow mới dùng searchFilesUnder() với LIMIT thay vì load full-table.
    @Deprecated("Dùng searchFilesUnder() hoặc Paging thay vì load full-table")
    @Query("SELECT * FROM files_cache LIMIT 25000")
    fun getAllFilesForMap(): List<CachedFile>

    // P2-8 (lop 2, tiep): scope duplicate-scan theo phien. Row key rong van
    // doc duoc trong phien hien tai; doi phien -> connect() xoa sach.
    @Query("""
    WITH duplicate_sizes AS (
        SELECT contentLength FROM files_cache
        WHERE isDirectory = 0 AND contentLength >= 4096
        AND (accountKey = :key OR accountKey = '')
        GROUP BY contentLength
        HAVING COUNT(*) > 1
    )
    SELECT f.* FROM files_cache f
    INNER JOIN duplicate_sizes ds ON f.contentLength = ds.contentLength
    WHERE f.isDirectory = 0 AND (f.accountKey = :key OR f.accountKey = '')
    ORDER BY f.contentLength DESC, f.name ASC
    LIMIT 1500
""")
    fun getDuplicateFiles(key: String = ""): List<CachedFile>

    // ĐẾM SỐ FILE TRÙNG LẶP (chỉ trả về Int, không load object vào RAM)
    @Query("""
    SELECT COUNT(*) FROM files_cache
    WHERE isDirectory = 0
    AND (accountKey = :key OR accountKey = '')
    AND contentLength IN (
        SELECT contentLength FROM files_cache
        WHERE isDirectory = 0 AND contentLength >= 4096
        AND (accountKey = :key OR accountKey = '')
        GROUP BY contentLength
        HAVING COUNT(*) > 1
    )
    """)
    fun countDuplicateFiles(key: String = ""): Int

    // LẤY DANH SÁCH CÁC KÍCH THƯỚC FILE BỊ TRÙNG (chỉ trả về Long, không load CachedFile)
    @Query("""
    SELECT contentLength FROM files_cache
    WHERE isDirectory = 0 AND contentLength >= 4096
    AND (accountKey = :key OR accountKey = '')
    GROUP BY contentLength
    HAVING COUNT(*) > 1
    ORDER BY contentLength DESC
    """)
    fun getDuplicateSizes(key: String = ""): List<Long>

    // LẤY FILES THEO TỪNG NHÓM SIZE (batch nhỏ, an toàn RAM)
    @Query("SELECT * FROM files_cache WHERE isDirectory = 0 AND contentLength = :size AND (accountKey = :key OR accountKey = '') ORDER BY name ASC")
    fun getFilesBySize(size: Long, key: String = ""): List<CachedFile>

    // LẤY FILES THEO NHIỀU NHÓM SIZE MỘT LÚC (Tối ưu hóa cực độ tốc độ tải Bước 2)
    @Query("SELECT * FROM files_cache WHERE isDirectory = 0 AND contentLength IN (:sizes) AND (accountKey = :key OR accountKey = '') ORDER BY name ASC")
    fun getFilesBySizes(sizes: List<Long>, key: String = ""): List<CachedFile>

    // HASH STAGE 1: Tìm các file có cùng dung lượng byte (Cực nhanh)
    // TỐI ƯU PHASE 8: Loại bỏ file rác cỏn con < 4KB
    @Query("SELECT * FROM files_cache WHERE isDirectory = 0 AND contentLength >= 4096 AND (accountKey = :key OR accountKey = '') LIMIT 1500")
    fun getAllLargeFiles(key: String = ""): List<CachedFile>

    // TỐI ƯU HÓA: Dùng SQLite Native (Sử dụng CTE) thay cho Group By trên RAM
    // vì nếu có 500,000 file thì getAllLargeFiles() sẽ nổ tung bộ nhớ RAM (OutOfMemory) gây treo toàn bộ ứng dụng ở Bước 2.
    fun getStage1Duplicates(key: String = ""): List<CachedFile> {
        return getDuplicateFiles(key)
    }


    @Query("UPDATE files_cache SET partialHash = :hash WHERE path = :path")
    fun updatePartialHash(path: String, hash: String)

    // PHASE 5: Cập nhật Image Fingerprint
    @Query("UPDATE files_cache SET imageFingerprint = :fingerprint WHERE path = :path")
    fun updateImageFingerprint(path: String, fingerprint: String)

    // Lấy các file chưa có Fingerprint để cho Worker chạy ngầm băm (chỉ lấy file ảnh và video nhẹ)
    @Query("""
        SELECT * FROM files_cache 
        WHERE isDirectory = 0 
        AND imageFingerprint IS NULL 
        AND parentPath NOT LIKE '%/.trash/%'
        AND (name LIKE '%.jpg' OR name LIKE '%.jpeg' OR name LIKE '%.png' OR name LIKE '%.webp' OR name LIKE '%.heic')
        ORDER BY lastModified DESC LIMIT 500
    """)
    fun getFilesWithoutFingerprint(): List<CachedFile>

    @Query("DELETE FROM files_cache WHERE path = :path")
    fun deleteFileByPath(path: String)
}

@Entity(tableName = "system_logs")
data class SystemLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String, // "INFO", "SUCCESS", "ERROR", "WARNING"
    val module: String, // "AutoBackup", "DuplicateScan", "SyncWorker"
    val message: String
)

@Dao
interface LogDao {
    @Query("SELECT * FROM system_logs ORDER BY timestamp DESC LIMIT 200")
    fun getRecentLogs(): List<SystemLog>

    @Query("SELECT * FROM system_logs WHERE module = :module ORDER BY timestamp DESC LIMIT :limit")
    fun getLogsByModule(module: String, limit: Int): List<SystemLog>

    // LOCAL CRASH EXPORT: Truy vấn các log CRASH/ERROR mới nhất để chia sẻ.
    // Chạy đồng bộ trên background thread khi user bấm "Chia sẻ log lỗi".
    // Lưu ý: không suspend — caller chịu trách nhiệm dispatch IO.
    @Query("SELECT * FROM system_logs WHERE type IN ('CRASH','ERROR') ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentCrashes(limit: Int): List<SystemLog>

    @Insert
    fun insertLog(log: SystemLog)

    @Query("DELETE FROM system_logs")
    fun clearAllLogs()
}

// KIẾN TRÚC MỚI: Bảng lưu trữ Checkpoint để resume các Worker bị gián đoạn (Chống mất dữ liệu quét)
@Entity(tableName = "scan_checkpoints")
data class ScanCheckpoint(
    @PrimaryKey val workerName: String,
    val lastProcessedFolder: String,
    val scannedCount: Int,
    val foundCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM scan_checkpoints WHERE workerName = :name LIMIT 1")
    fun getCheckpoint(name: String): ScanCheckpoint?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveCheckpoint(checkpoint: ScanCheckpoint)

    @Query("DELETE FROM scan_checkpoints WHERE workerName = :name")
    fun clearCheckpoint(name: String)
}

// BỘ NHỚ ĐỆM THUMBNAIL ẢNH/VIDEO (LƯU VÀO DB ĐỂ TẢI CỰC NHANH LẦN SAU)
@Entity(tableName = "thumbnail_cache")
data class ThumbnailCache(
    @PrimaryKey val url: String,
    val localFilePath: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface ThumbnailDao {
    @Query("SELECT * FROM thumbnail_cache WHERE url = :url LIMIT 1")
    fun getThumbnail(url: String): ThumbnailCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveThumbnail(thumbnail: ThumbnailCache)

    @Query("DELETE FROM thumbnail_cache WHERE url = :url")
    fun deleteThumbnail(url: String)

    // FIX DISK STORAGE: Xóa thumbnail cũ hơn 30 ngày để giải phóng dung lượng
    @Query("DELETE FROM thumbnail_cache WHERE timestamp < :beforeTimestamp")
    fun clearOldThumbnails(beforeTimestamp: Long)
}

// ============ VÂN TAY ẢNH (IMAGE FINGERPRINT) ============
@Entity(
    tableName = "file_fingerprints",
    indices = [
        Index(value = ["hash"]),       // Tìm trùng lặp chính xác siêu nhanh
        Index(value = ["fileName"]),   // Tìm theo tên file
        Index(value = ["accountKey"]), // P2-muc3: tach NAS/tai khoan
        Index(value = ["sourceKey"])   // P2-muc4: tim theo nguon (ke ca khi khong aHash)
    ]
)
data class FileFingerprint(
    @PrimaryKey val filePath: String,  // Đường dẫn WebDAV trên NAS
    val hash: String,                  // aHash 64-bit (hex 16 ký tự, "" neu khong tinh duoc)
    val fileName: String,              // Tên file gốc (hiển thị cho user)
    val fileSize: Long,                // Dung lượng file (bytes)
    val timestamp: Long = System.currentTimeMillis(),
    // P2-muc3: dinh danh NAS/tai khoan — khong dung ban NAS A cho NAS B.
    val accountKey: String = "",
    // P2-muc4: danh tinh nguon on dinh ("media:<id>") + full SHA-256 sau verify.
    // Cho phep nhan dien "nguon khong doi" ke ca khi khong tinh duoc aHash.
    val sourceKey: String = "",
    val contentHash: String = "",
)

@Dao
interface FingerprintDao {
    // Lưu vân tay mới (upsert)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFingerprint(fingerprint: FileFingerprint)

    // Tìm file có hash CHÍNH XÁC trùng khớp
    @Query("SELECT * FROM file_fingerprints WHERE hash = :hash LIMIT 1")
    fun findByExactHash(hash: String): FileFingerprint?

    // Tim theo hash + size (phien ban nguon) — aHash va cham duoc.
    @Query("SELECT * FROM file_fingerprints WHERE hash = :hash AND fileSize = :size LIMIT 5")
    fun findByHashAndSize(hash: String, size: Long): List<FileFingerprint>

    // P2-muc3: tim theo hash + size + TAI KHOAN — khong dung ban NAS A cho NAS B.
    @Query("SELECT * FROM file_fingerprints WHERE hash = :hash AND fileSize = :size AND (accountKey = :key OR accountKey = '') LIMIT 5")
    fun findByHashSizeAccount(hash: String, size: Long, key: String): List<FileFingerprint>

    // P2-muc4: tim theo NGUON (on dinh ke ca khi khong aHash) + tai khoan.
    @Query("SELECT * FROM file_fingerprints WHERE sourceKey = :source AND (accountKey = :key OR accountKey = '') ORDER BY timestamp DESC LIMIT 5")
    fun findBySource(source: String, key: String): List<FileFingerprint>

    // Lấy TẤT CẢ fingerprint để so sánh Hamming Distance (dùng cho aHash gần giống), có LIMIT chống OOM Worker
    @Query("SELECT * FROM file_fingerprints LIMIT 10000")
    fun getAllFingerprints(): List<FileFingerprint>

    // Xóa fingerprint theo đường dẫn
    @Query("DELETE FROM file_fingerprints WHERE filePath = :path")
    fun deleteByPath(path: String)

    // Đếm tổng số vân tay đã lưu
    @Query("SELECT COUNT(*) FROM file_fingerprints")
    fun countFingerprints(): Int

    // BUG FIX P1#7: Batch insert — giảm DB lock khi backup/sync nhiều file
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(fingerprints: List<FileFingerprint>)
}

// ================= THỰC THỂ: LƯU TRỮ VÂN TAY (HASH CACHE) VĨNH VIỄN =================
@Entity(tableName = "hash_cache")
data class HashCache(
    @PrimaryKey val path: String,
    val contentLength: Long,
    val lastModified: Long,
    val partialHash: String
)

@Dao
interface HashCacheDao {
    @Query("SELECT partialHash FROM hash_cache WHERE path = :path AND contentLength = :contentLength AND lastModified = :lastModified")
    fun getHash(path: String, contentLength: Long, lastModified: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertHash(hashCache: HashCache)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertHashes(hashes: List<HashCache>)
}

// ================= THỰC THỂ: NHÀ KHO OFFLINE SYNC QUEUE (PHASE 2) =================
@Entity(tableName = "sync_queue")
data class SyncAction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val actionType: String, // "UPLOAD", "DELETE", "RENAME", "MOVE", "CREATE_FOLDER", "UPLOAD_FAILED"
    val sourcePath: String, // Đường dẫn nguồn (Local URI hoặc NAS Path)
    val destPath: String? = null, // Cho hành động MOVE/RENAME
    val status: String = "PENDING", // PENDING, FAILED, PARKED, COMPLETED
    val timestamp: Long = System.currentTimeMillis(),
    // FIX-AUDIT-D4..D7: ràng buộc NAS/user để không phát lại thao tác của NAS
    // khác khi đổi endpoint (R4-P1), và đếm lỗi / trạng thái chạy để park + retry.
    val nasHost: String = "", // host NAS mà action này thuộc về (rỗng = legacy, tương thích ngược)
    val nasUser: String = "", // user NAS (rỗng = legacy)
    // FIX-REVIEW-24/09-#6: dinh danh endpoint DAY DU (port + root). Ban cu chi
    // luu host: doi :8080/davA sang :8081/davB cung host/user van qua scope,
    // tac vu cu co the ap len root moi. Legacy rong cho tac vu pha huy phai
    // cho xac nhan, khong tu rebind.
    val nasPort: Int = -1, // port endpoint (-1 = legacy/chua xac dinh)
    val nasRoot: String = "", // root path endpoint (vd /davA, /webdav; rong = legacy)
    val failCount: Int = 0, // số lần thất bại liên tiếp (park sau ngưỡng)
    val lastFailAt: Long = 0L, // timestamp lỗi gần nhất (dùng pushBack)
    val runState: String = "PENDING" // trạng thái chạy: PENDING/PARKED/COMPLETED
)

@Dao
interface SyncActionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(action: SyncAction)

    // FIX-REVIEW-193369e-#7: wildcard port/root CHI khi host/user cung legacy.
    // Ban cu: hang migration16 co host/user nhung port=-1/root='' van wildcard
    // -> tu replay destructive khi doi port/root cung host/user. Quy tac moi:
    // identity thieu bat ky thanh phan nao (port/root thieu ma host/user co)
    // thi KHONG du dieu kien tu replay — worker park cho xac nhan.
    @Query("""
        SELECT * FROM sync_queue
        WHERE runState = 'PENDING'
          AND (
            (nasHost = :activeHost AND nasUser = :activeUser
             AND nasPort = :activePort AND nasRoot = :activeRoot)
            OR (nasHost = '' AND nasUser = '' AND nasPort = -1 AND nasRoot = '')
          )
        ORDER BY actionType != 'UPLOAD_FAILED' DESC, timestamp ASC
        LIMIT :limit
    """)
    fun getAllPendingActionsScoped(activeHost: String, activeUser: String, activePort: Int, activeRoot: String, limit: Int): List<SyncAction>

    // UPLOAD_FAILED xếp cuối để action bình thường không bị starve khi queue đầy.
    @Query("SELECT * FROM sync_queue WHERE runState = 'PENDING' ORDER BY actionType != 'UPLOAD_FAILED' DESC, timestamp ASC LIMIT 200")
    fun getAllPendingActions(): List<SyncAction>

    // Tổng số action còn lại trong queue (dùng để quyết định continuation work khi > 200)
    @Query("SELECT COUNT(*) FROM sync_queue WHERE runState = 'PENDING'")
    fun countAll(): Int

    // So action PENDING dung scope account+endpoint — dung cho continuation.
    // FIX-REVIEW-193369e-#7: cung quy tac chat nhu selection (khong wildcard
    // port/root khi host/user co gia tri).
    @Query("""
        SELECT COUNT(*) FROM sync_queue
        WHERE runState = 'PENDING'
          AND (
            (nasHost = :activeHost AND nasUser = :activeUser
             AND nasPort = :activePort AND nasRoot = :activeRoot)
            OR (nasHost = '' AND nasUser = '' AND nasPort = -1 AND nasRoot = '')
          )
    """)
    fun countLocal(activeHost: String, activeUser: String, activePort: Int, activeRoot: String): Int

    // Liệt kê các UPLOAD_FAILED cho UI (mới nhất trước)
    @Query("SELECT * FROM sync_queue WHERE actionType = 'UPLOAD_FAILED' ORDER BY timestamp DESC")
    fun getAllUploadFailed(): List<SyncAction>

    @Query("DELETE FROM sync_queue WHERE id = :id")
    fun deleteById(id: Int)

    // Retry theo ID truc tiep — khong tim trong top 200.
    @Query("SELECT * FROM sync_queue WHERE id = :id LIMIT 1")
    fun getById(id: Int): SyncAction?

    // FIX-AUDIT-D5/D6: đếm + tăng lỗi, park row sau ngưỡng để không chặn queue.
    @Query("UPDATE sync_queue SET failCount = failCount + 1, lastFailAt = :now WHERE id = :id")
    fun bumpFail(id: Int, now: Long)

    @Query("SELECT failCount FROM sync_queue WHERE id = :id LIMIT 1")
    fun getFailCount(id: Int): Int?

    @Query("UPDATE sync_queue SET runState = 'PARKED', status = 'PARKED' WHERE id = :id")
    fun park(id: Int)

    // Đẩy mục lỗi xuống cuối queue (timestamp mới) để batch sau vét mục khác trước.
    @Query("UPDATE sync_queue SET timestamp = :now WHERE id = :id")
    fun pushBack(id: Int, now: Long)
}

// ================= TRASH META — Lưu path gốc để restore đúng vị trí =================
@Entity(
    tableName = "trash_meta",
    indices = [Index(value = ["originalPath"])]
)
data class TrashMeta(
    @PrimaryKey val trashPath: String,
    val originalPath: String,
    val trashTime: Long = System.currentTimeMillis()
)

@Dao
interface TrashMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(meta: TrashMeta)

    @Query("SELECT * FROM trash_meta WHERE trashPath = :trashPath LIMIT 1")
    fun findByTrashPath(trashPath: String): TrashMeta?

    @Query("SELECT * FROM trash_meta WHERE originalPath = :originalPath LIMIT 1")
    fun findByOriginalPath(originalPath: String): TrashMeta?

    @Query("DELETE FROM trash_meta WHERE trashPath = :trashPath")
    fun deleteByTrashPath(trashPath: String)
}

/**
 * DATABASE CHANGELOG:
 * v1: files_cache cơ bản (path, name, isDirectory, contentType, parentPath)
 * v2: Thêm contentLength, lastModified cho file metadata
 * v3: Thêm system_logs cho logging module
 * v4: Thêm scan_checkpoints cho Worker resume
 * v5: Thêm partialHash, fullHash cho duplicate detection
 * v6: Thêm thumbnail_cache cho lưu thumbnail persistent
 * v7: Tích hợp Paging 3 (PagingSource), index parentPath/contentLength/isDirectory
 * v8: Sửa SQL duplicate detection, thêm index name, thêm clearOldThumbnails
 * v9: Thêm file_fingerprints cho hệ thống vân tay ảnh (aHash)
 * v10: Thêm sync_queue chuẩn bị bộ giáp Offline-First Sync cho ứng dụng
 * v11: PHASE 5 - Thêm thuộc tính imageFingerprint trực tiếp vào files_cache
 *
 * MIGRATION: explicit migrations only; destructive fallback is disabled.
 * Cache can rebuild, but logs, checkpoints, offline queue, and trash metadata must not be dropped silently.
 */

// PHASE 5: MIGRATION 10 -> 11 (Bảo vệ dữ liệu không bị xóa khi upgrade db)

private fun androidx.sqlite.db.SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}

private fun androidx.sqlite.db.SupportSQLiteDatabase.addColumnIfMissing(
    table: String,
    column: String,
    definition: String
) {
    if (!hasColumn(table, column)) {
        execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
    }
}

private fun migrateLegacyDatabaseTo10(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    db.addColumnIfMissing("files_cache", "contentLength", "INTEGER NOT NULL DEFAULT 0")
    db.addColumnIfMissing("files_cache", "lastModified", "INTEGER NOT NULL DEFAULT 0")
    db.addColumnIfMissing("files_cache", "partialHash", "TEXT")
    db.addColumnIfMissing("files_cache", "fullHash", "TEXT")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_cache_parentPath` ON `files_cache` (`parentPath`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_cache_contentLength` ON `files_cache` (`contentLength`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_cache_isDirectory` ON `files_cache` (`isDirectory`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_cache_name` ON `files_cache` (`name`)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `system_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `type` TEXT NOT NULL, `module` TEXT NOT NULL, `message` TEXT NOT NULL)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `scan_checkpoints` (`workerName` TEXT NOT NULL, `lastProcessedFolder` TEXT NOT NULL, `scannedCount` INTEGER NOT NULL, `foundCount` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`workerName`))")
    db.execSQL("CREATE TABLE IF NOT EXISTS `thumbnail_cache` (`url` TEXT NOT NULL, `localFilePath` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`url`))")
    db.execSQL("CREATE TABLE IF NOT EXISTS `file_fingerprints` (`filePath` TEXT NOT NULL, `hash` TEXT NOT NULL, `fileName` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`filePath`))")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_fingerprints_hash` ON `file_fingerprints` (`hash`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_fingerprints_fileName` ON `file_fingerprints` (`fileName`)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `sync_queue` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `actionType` TEXT NOT NULL, `sourcePath` TEXT NOT NULL, `destPath` TEXT, `status` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
}

private fun legacyTo10Migration(from: Int) = object : androidx.room.migration.Migration(from, 10) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        migrateLegacyDatabaseTo10(db)
    }
}

val MIGRATION_1_10 = legacyTo10Migration(1)
val MIGRATION_2_10 = legacyTo10Migration(2)
val MIGRATION_3_10 = legacyTo10Migration(3)
val MIGRATION_4_10 = legacyTo10Migration(4)
val MIGRATION_5_10 = legacyTo10Migration(5)
val MIGRATION_6_10 = legacyTo10Migration(6)
val MIGRATION_7_10 = legacyTo10Migration(7)
val MIGRATION_8_10 = legacyTo10Migration(8)
val MIGRATION_9_10 = legacyTo10Migration(9)

val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `files_cache` ADD COLUMN `imageFingerprint` TEXT")
    }
}

// PHASE 6: MIGRATION 11 -> 12 (Bảng HashCache tiết kiệm tài nguyên mạng)
val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `hash_cache` (`path` TEXT NOT NULL, `contentLength` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, `partialHash` TEXT NOT NULL, PRIMARY KEY(`path`))")
    }
}

val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `trash_meta` (`trashPath` TEXT NOT NULL, `originalPath` TEXT NOT NULL, `trashTime` INTEGER NOT NULL, PRIMARY KEY(`trashPath`))")
    }
}

// FIX F1 CRITICAL: v13→v14→v15 là no-op (schema không đổi — cùng identityHash).
// Trước đây KHÔNG có migration → Room dùng fallbackToDestructiveMigration() → xóa sạch
// sync_queue, trash_meta, scan_checkpoints, system_logs... của user khi upgrade.
val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        // Schema unchanged (identityHash identical to v13) — no-op migration.
    }
}
val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        // Schema 15 adds index on trash_meta.originalPath for fast lookup during restore.
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trash_meta_originalPath` ON `trash_meta` (`originalPath`)")
    }
}

// FIX-AUDIT-D4..D7: mở rộng sync_queue hỗ trợ ràng buộc NAS/user (R4-P1) và
// đếm lỗi / trạng thái park (D5/D6). Dùng addColumnIfMissing để không phá dữ
// liệu hàng đợi cũ của user khi upgrade.
val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.addColumnIfMissing("sync_queue", "nasHost", "TEXT NOT NULL DEFAULT ''")
        db.addColumnIfMissing("sync_queue", "nasUser", "TEXT NOT NULL DEFAULT ''")
        db.addColumnIfMissing("sync_queue", "failCount", "INTEGER NOT NULL DEFAULT 0")
        db.addColumnIfMissing("sync_queue", "lastFailAt", "INTEGER NOT NULL DEFAULT 0")
        db.addColumnIfMissing("sync_queue", "runState", "TEXT NOT NULL DEFAULT 'PENDING'")
    }
}

// FIX-REVIEW-24/09-#6: endpoint day du (port + root) cho scope account.
val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.addColumnIfMissing("sync_queue", "nasPort", "INTEGER NOT NULL DEFAULT -1")
        db.addColumnIfMissing("sync_queue", "nasRoot", "TEXT NOT NULL DEFAULT ''")
    }
}

// P2-8 (lop 2): tach cache file theo phien dang nhap.
val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.addColumnIfMissing("files_cache", "accountKey", "TEXT NOT NULL DEFAULT ''")
    }
}

// P2-muc3+muc4: fingerprint them accountKey/sourceKey/contentHash.
val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.addColumnIfMissing("file_fingerprints", "accountKey", "TEXT NOT NULL DEFAULT ''")
        db.addColumnIfMissing("file_fingerprints", "sourceKey", "TEXT NOT NULL DEFAULT ''")
        db.addColumnIfMissing("file_fingerprints", "contentHash", "TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_fingerprints_accountKey` ON `file_fingerprints` (`accountKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_fingerprints_sourceKey` ON `file_fingerprints` (`sourceKey`)")
    }
}

@Database(
    entities = [CachedFile::class, SystemLog::class, ScanCheckpoint::class, ThumbnailCache::class, FileFingerprint::class, SyncAction::class, HashCache::class, TrashMeta::class],
    version = 19,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun logDao(): LogDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun thumbnailDao(): ThumbnailDao
    abstract fun fingerprintDao(): FingerprintDao
    abstract fun syncActionDao(): SyncActionDao
    abstract fun hashCacheDao(): HashCacheDao
    abstract fun trashMetaDao(): TrashMetaDao
}
