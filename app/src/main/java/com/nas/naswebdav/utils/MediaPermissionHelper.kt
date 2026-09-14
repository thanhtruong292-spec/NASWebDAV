package com.nas.naswebdav.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Centralized media permission checks for AutoBackup.
 *
 * Android 14+ (API 34) introduces READ_MEDIA_VISUAL_USER_SELECTED which grants
 * partial access — only user-selected photos/videos. MediaStore queries still
 * return ALL media items, but openInputStream() fails for non-selected ones,
 * producing the "has no access to content://media/..." errors in system logs.
 */
object MediaPermissionHelper {

    /**
     * True when the app has FULL access to images via READ_MEDIA_IMAGES or
     * READ_EXTERNAL_STORAGE (API < 33).
     */
    fun hasFullImageAccess(context: Context): Boolean {
        val sdk = Build.VERSION.SDK_INT
        return when {
            sdk >= Build.VERSION_CODES.TIRAMISU -> {
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
            }
            else -> context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * True when the app has FULL access to videos via READ_MEDIA_VIDEO or
     * READ_EXTERNAL_STORAGE (API < 33).
     */
    fun hasFullVideoAccess(context: Context): Boolean {
        val sdk = Build.VERSION.SDK_INT
        return when {
            sdk >= Build.VERSION_CODES.TIRAMISU -> {
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) ==
                    PackageManager.PERMISSION_GRANTED
            }
            else -> context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * True when at least READ_MEDIA_VISUAL_USER_SELECTED is granted on Android 14+,
     * meaning partial photo access is available but NOT full access.
     */
    fun hasPartialVisualAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * True when the app has NO media access at all.
     */
    fun hasNoMediaAccess(context: Context): Boolean {
        return !hasFullImageAccess(context) && !hasFullVideoAccess(context) &&
            !hasPartialVisualAccess(context)
    }

    /**
     * Check whether periodic auto-backup can run safely.
     * Periodic backup must have FULL access — partial access causes mass "has no access"
     * errors because MediaStore returns all items but openInputStream() rejects non-selected ones.
     */
    fun canRunPeriodicBackup(context: Context): Boolean {
        return hasFullImageAccess(context) || hasFullVideoAccess(context)
    }
}
