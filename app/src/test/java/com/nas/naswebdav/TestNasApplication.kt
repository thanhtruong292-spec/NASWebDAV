package com.nas.naswebdav

/**
 * Application nhẹ cho unit test cần NasApplication.instance
 * (WebDavManager.optimizedClient) mà không chạy onCreate đầy đủ
 * (schedule worker cần WorkManager init, DB, Sentry...).
 */
class TestNasApplication : NasApplication() {
    private val inMemoryDb by lazy {
        androidx.room.Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    override val database: AppDatabase
        get() = inMemoryDb

    override fun onCreate() {
        // Không gọi super — chỉ gán instance để lazy clients dùng được.
        NasApplication.setInstanceForTest(this)
        // WorkManager test init để worker schedule (nếu có) không crash.
        runCatching {
            androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(this)
        }
    }
}
