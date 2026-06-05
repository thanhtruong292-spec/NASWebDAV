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
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface FileDao {
    @Query("SELECT * FROM files_cache WHERE parentPath = :path ORDER BY isDirectory DESC, name COLLATE NOCASE ASC")
    fun getFiles(path: String): List<CachedFile>

    // KIẾN TRÚC MỚI: Paging 3 cho hàng trăm ngàn tệp tin
    @Query("SELECT * FROM files_cache WHERE parentPath = :path ORDER BY isDirectory DESC, name COLLATE NOCASE ASC")
    fun getFilesPaged(path: String): androidx.paging.PagingSource<Int, CachedFile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFiles(files: List<CachedFile>): List<Long>

    @Query("DELETE FROM files_cache WHERE parentPath = :path")
    fun deleteByParentPath(path: String): Int

    @Query("DELETE FROM files_cache")
    fun clearAllFiles()

    @Query("SELECT path FROM files_cache WHERE isDirectory = 0")
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
    AND parentPath NOT LIKE '%.trash%' AND parentPath NOT LIKE '%#recycle%' AND parentPath NOT LIKE '%@eaDir%' 
    AND (name LIKE '%.jpg' OR name LIKE '%.jpeg' OR name LIKE '%.png' OR name LIKE '%.webp' OR name LIKE '%.heic') 
    ORDER BY lastModified DESC LIMIT 100
""")
    fun getLatestPhotos(): List<CachedFile>

    @Query("""
    SELECT * FROM files_cache 
    WHERE isDirectory = 0 
    AND parentPath NOT LIKE '%.trash%' AND parentPath NOT LIKE '%#recycle%' AND parentPath NOT LIKE '%@eaDir%'
    AND (name LIKE '%.mp4' OR name LIKE '%.mkv' OR name LIKE '%.mov' OR name LIKE '%.avi' OR name LIKE '%.mpg' OR name LIKE '%.mpeg' OR name LIKE '%.wmv' OR name LIKE '%.flv') 
    ORDER BY lastModified DESC LIMIT 100
""")
    fun getRecentVideos(): List<CachedFile>

    // TÍNH NĂNG TÌM KIẾM TOÀN CẦU (GLOBAL SEARCH)
    @Query("SELECT * FROM files_cache WHERE name LIKE '%' || :keyword || '%' ORDER BY isDirectory DESC, name ASC LIMIT 200")
    fun searchFiles(keyword: String): List<CachedFile>

    // FIX FALSE POSITIVES & PHASE 8: Nhóm theo contentLength VÀ name riêng biệt (tránh collision chuỗi nối)
    // PHASE 8: Loại bỏ thư mục và các file vụn vặt rác < 4KB (4096 bytes) để tăng tốc quét tối đa
    // TỐI ƯU PHASE 15: Dùng CTE (Common Table Expression) thay vì subquery lồng
    // SỬa LỖI: ETag trên NAS là unique/file, không phải content hash. Duy trì truy vấn theo contentLength để tìm nhóm ứng cử viên.
    @Query("""
    WITH duplicate_sizes AS (
        SELECT contentLength FROM files_cache 
        WHERE isDirectory = 0 AND contentLength >= 4096
        GROUP BY contentLength 
        HAVING COUNT(*) > 1
    )
    SELECT f.* FROM files_cache f
    INNER JOIN duplicate_sizes ds ON f.contentLength = ds.contentLength
    WHERE f.isDirectory = 0
    ORDER BY f.contentLength DESC, f.name ASC
    LIMIT 5000
""")
    fun getDuplicateFiles(): List<CachedFile>

    // ĐẾM SỐ FILE TRÙNG LẶP (chỉ trả về Int, không load object vào RAM)
    @Query("""
    SELECT COUNT(*) FROM files_cache 
    WHERE isDirectory = 0 
    AND contentLength IN (
        SELECT contentLength FROM files_cache 
        WHERE isDirectory = 0 AND contentLength >= 4096
        GROUP BY contentLength 
        HAVING COUNT(*) > 1
    )
    """)
    fun countDuplicateFiles(): Int

    // LẤY DANH SÁCH CÁC KÍCH THƯỚC FILE BỊ TRÙNG (chỉ trả về Long, không load CachedFile)
    @Query("""
    SELECT contentLength FROM files_cache 
    WHERE isDirectory = 0 AND contentLength >= 4096
    GROUP BY contentLength 
    HAVING COUNT(*) > 1
    ORDER BY contentLength DESC
    """)
    fun getDuplicateSizes(): List<Long>

    // LẤY FILES THEO TỪNG NHÓM SIZE (batch nhỏ, an toàn RAM)
    @Query("SELECT * FROM files_cache WHERE isDirectory = 0 AND contentLength = :size ORDER BY name ASC")
    fun getFilesBySize(size: Long): List<CachedFile>

    // LẤY FILES THEO NHIỀU NHÓM SIZE MỘT LÚC (Tối ưu hóa cực độ tốc độ tải Bước 2)
    @Query("SELECT * FROM files_cache WHERE isDirectory = 0 AND contentLength IN (:sizes) ORDER BY name ASC")
    fun getFilesBySizes(sizes: List<Long>): List<CachedFile>

    // HASH STAGE 1: Tìm các file có cùng dung lượng byte (Cực nhanh)
    // TỐI ƯU PHASE 8: Lọc bỏ file rác cỏn con < 4KB
    @Query("SELECT * FROM files_cache WHERE isDirectory = 0 AND contentLength >= 4096 LIMIT 200000")
    fun getAllLargeFiles(): List<CachedFile>

    // TỐI ƯU HÓA: Dùng SQLite Native (Sử dụng CTE) thay cho Group By trên RAM 
    // vì nếu có 500,000 file thì getAllLargeFiles() sẽ nổ tung bộ nhớ RAM (OutOfMemory) gây treo toàn bộ ứng dụng ở Bước 2.
    fun getStage1Duplicates(): List<CachedFile> {
        return getDuplicateFiles()
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
        Index(value = ["fileName"])    // Tìm theo tên file
    ]
)
data class FileFingerprint(
    @PrimaryKey val filePath: String,  // Đường dẫn WebDAV trên NAS
    val hash: String,                  // aHash 64-bit (hex 16 ký tự)
    val fileName: String,              // Tên file gốc (hiển thị cho user)
    val fileSize: Long,                // Dung lượng file (bytes)
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface FingerprintDao {
    // Lưu vân tay mới (upsert)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFingerprint(fingerprint: FileFingerprint)

    // Tìm file có hash CHÍNH XÁC trùng khớp
    @Query("SELECT * FROM file_fingerprints WHERE hash = :hash LIMIT 1")
    fun findByExactHash(hash: String): FileFingerprint?

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
    val actionType: String, // "UPLOAD", "DELETE", "RENAME", "MOVE", "CREATE_FOLDER"
    val sourcePath: String, // Đường dẫn nguồn (Local URI hoặc NAS Path)
    val destPath: String? = null, // Cho hành động MOVE/RENAME
    val status: String = "PENDING", // PENDING, FAILED
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface SyncActionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(action: SyncAction)

    @Query("SELECT * FROM sync_queue ORDER BY timestamp ASC")
    fun getAllPendingActions(): List<SyncAction>

    @Query("DELETE FROM sync_queue WHERE id = :id")
    fun deleteById(id: Int)
}

// ================= TRASH META — Lưu path gốc để restore đúng vị trí =================
@Entity(tableName = "trash_meta")
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
 * MIGRATION: fallbackToDestructiveMigration() — chấp nhận mất cache khi upgrade.
 * Dữ liệu cache sẽ tự rebuild khi user duyệt lại thư mục.
 */

// PHASE 5: MIGRATION 10 -> 11 (Bảo vệ dữ liệu không bị xóa khi upgrade db)
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

@Database(
    entities = [CachedFile::class, SystemLog::class, ScanCheckpoint::class, ThumbnailCache::class, FileFingerprint::class, SyncAction::class, HashCache::class, TrashMeta::class],
    version = 13,
    exportSchema = false
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
