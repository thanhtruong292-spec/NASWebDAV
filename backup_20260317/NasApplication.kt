package com.nas.naswebdav

import android.app.Application
import androidx.room.Room

/**
 * Application class cung cấp singleton Database cho toàn bộ ứng dụng.
 * Tránh tạo nhiều Database instance trong mỗi Worker/Activity.
 */
class NasApplication : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "nas-db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    companion object {
        lateinit var instance: NasApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
