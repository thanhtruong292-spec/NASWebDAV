package com.nas.naswebdav

import androidx.room.*

@Entity(
    tableName = "files_cache",
    indices = [
        Index(value = ["parentPath"]),
        Index(value = ["contentLength"]), // Phục vụ Stage 1 Hashing siêu tốc
        Index(value = ["isDirectory"])
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
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface FileDao {
    @Query("SELECT * FROM files_cache WHERE parentPath = :path")
    fun getFiles(path: String): List<CachedFile>

    // KIẾN TRÚC MỚI: Paging 3 cho hàng trăm ngàn tệp tin
    @Query("SELECT * FROM files_cache WHERE parentPath = :path ORDER BY isDirectory DESC, name ASC")
    fun getFilesPaged(path: String): androidx.paging.PagingSource<Int, CachedFile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFiles(files: List<CachedFile>): List<Long>

    @Query("DELETE FROM files_cache WHERE parentPath = :path")
    fun deleteByParentPath(path: String): Int

    @Query("SELECT path FROM files_cache WHERE isDirectory = 0")
    fun getAllCachedFilePaths(): List<String>

    // TỐI ƯU OOM: Tránh load toàn bộ List<String> vào RAM
    @Query("SELECT EXISTS(SELECT 1 FROM files_cache WHERE path = :path LIMIT 1)")
    fun exists(path: String): Boolean

    // TỐI ƯU SQL: Loại trừ thư mục .trash để ảnh/video đã xóa không xuất hiện
    @Query("""
    SELECT * FROM files_cache 
    WHERE isDirectory = 0 
    AND parentPath NOT LIKE '%/.trash/%' 
    AND (name LIKE '%.jpg' OR name LIKE '%.jpeg' OR name LIKE '%.png' OR name LIKE '%.webp' OR name LIKE '%.heic') 
    ORDER BY lastModified DESC LIMIT 100
""")
    fun getLatestPhotos(): List<CachedFile>

    @Query("""
    SELECT * FROM files_cache 
    WHERE isDirectory = 0 
    AND parentPath NOT LIKE '%/.trash/%' 
    AND (name LIKE '%.mp4' OR name LIKE '%.mkv' OR name LIKE '%.mov' OR name LIKE '%.avi') 
    ORDER BY lastModified DESC LIMIT 100
""")
    fun getRecentVideos(): List<CachedFile>

    // TÍNH NĂNG TÌM KIẾM TOÀN CẦU (GLOBAL SEARCH)
    @Query("SELECT * FROM files_cache WHERE name LIKE '%' || :keyword || '%' ORDER BY isDirectory DESC, name ASC LIMIT 200")
    fun searchFiles(keyword: String): List<CachedFile>

    @Query("""
    SELECT * FROM files_cache 
    WHERE isDirectory = 0 AND contentLength > 0 AND (contentLength || '_' || lastModified) IN (
        SELECT (contentLength || '_' || lastModified) FROM files_cache 
        WHERE isDirectory = 0 AND contentLength > 0
        GROUP BY contentLength, lastModified 
        HAVING COUNT(*) > 1
    )
""")
    fun getDuplicateFiles(): List<CachedFile>

    // HASH STAGE 1: Tìm các file có cùng dung lượng byte (Cực nhanh)
    @Query("""
    SELECT * FROM files_cache 
    WHERE isDirectory = 0 AND contentLength > 0 
    AND contentLength IN (
        SELECT contentLength FROM files_cache 
        WHERE isDirectory = 0 AND contentLength > 0
        GROUP BY contentLength HAVING COUNT(*) > 1
    ) ORDER BY contentLength DESC
""")
    fun getStage1Duplicates(): List<CachedFile>

    @Query("UPDATE files_cache SET partialHash = :hash WHERE path = :path")
    fun updatePartialHash(path: String, hash: String)

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

// NÂNG CẤP VERSION 6: Tích hợp đầy đủ Paging 3 và Checkpoint Recovery
@Database(
    entities = [CachedFile::class, SystemLog::class, ScanCheckpoint::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun logDao(): LogDao
    abstract fun checkpointDao(): CheckpointDao
}