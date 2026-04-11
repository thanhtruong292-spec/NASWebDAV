package com.nas.naswebdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WebDavRepository(
    private val webDavManager: WebDavManager,
    private val database: AppDatabase
) {
    suspend fun getCachedFiles(url: String): List<CachedFile> = withContext(Dispatchers.IO) {
        database.fileDao().getFiles(url)
    }

    // KIẾN TRÚC MỚI: Trả về luồng dữ liệu Paging 3 thay vì List thông thường
    fun getFilesStream(url: String): Flow<PagingData<NasFile>> {
        return Pager(
            config = PagingConfig(
                pageSize = 50, // Nạp 50 tệp mỗi lần cuộn (Cân bằng giữa tốc độ UI và SQL)
                enablePlaceholders = false,
                prefetchDistance = 20, // Chuẩn bị sẵn 20 tệp trước khi người dùng kịp cuộn tới
                initialLoadSize = 150
            ),
            pagingSourceFactory = { database.fileDao().getFilesPaged(url) }
        ).flow.map { pagingData ->
            pagingData.map {
                NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified)
            }
        }
    }

    suspend fun getRemoteFilesAndCache(url: String): List<NasFile> = withContext(Dispatchers.IO) {
        // 1. Lấy dữ liệu từ NAS
        val remoteFiles = webDavManager.listFiles(url)

        // 2. Cập nhật Cache: Xóa các mục cũ của thư mục này và chèn mục mới
        database.withTransaction {
            database.fileDao().deleteByParentPath(url)
            database.fileDao().insertFiles(remoteFiles.map {
                CachedFile(
                    path = it.path,
                    name = it.name,
                    isDirectory = it.isDirectory,
                    contentType = it.contentType,
                    parentPath = url,
                    contentLength = it.contentLength,
                    lastModified = it.lastModified
                )
            })
        }

        remoteFiles
    }

    suspend fun getDuplicateFiles(): List<NasFile> = withContext(Dispatchers.IO) {
        // Sử dụng query Stage 1 đã được tối ưu trong FileDao
        database.fileDao().getDuplicateFiles().map {
            NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified)
        }
    }

    suspend fun getLatestPhotos(): List<NasFile> = withContext(Dispatchers.IO) {
        database.fileDao().getLatestPhotos().map {
            NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified)
        }
    }

    suspend fun getRecentVideos(): List<NasFile> = withContext(Dispatchers.IO) {
        database.fileDao().getRecentVideos().map {
            NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified)
        }
    }

    suspend fun searchGlobal(keyword: String): List<NasFile> = withContext(Dispatchers.IO) {
        database.fileDao().searchFiles(keyword).map {
            NasFile(it.name, it.path, it.isDirectory, it.contentType, it.contentLength, it.lastModified)
        }
    }

    suspend fun getSystemLogs(): List<SystemLog> = withContext(Dispatchers.IO) {
        database.logDao().getRecentLogs()
    }
    suspend fun clearAllLogs() = withContext(Dispatchers.IO) {
        database.logDao().clearAllLogs()
    }
    suspend fun clearSystemLogs() = withContext(Dispatchers.IO) {
        database.logDao().clearAllLogs()
    }

    suspend fun removeDuplicateFromDb(path: String) = withContext(Dispatchers.IO) {
        database.fileDao().deleteFileByPath(path)
    }
}