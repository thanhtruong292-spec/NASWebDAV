package com.nas.naswebdav.utils

import android.os.Environment
import android.os.StatFs

/**
 * Thông tin bộ nhớ điện thoại (dung lượng còn trống / tổng dung lượng).
 */
object StorageUtils {

    /** Trả về cặp (availableBytes, totalBytes) của bộ nhớ trong điện thoại. */
    fun getPhoneStorageBytes(): Pair<Long, Long> {
        return runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            Pair(stat.availableBytes, stat.totalBytes)
        }.getOrDefault(Pair(0L, 0L))
    }
}
