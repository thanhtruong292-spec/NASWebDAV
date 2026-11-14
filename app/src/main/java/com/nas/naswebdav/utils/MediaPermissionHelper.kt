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
     * AutoBackupWorker queries BOTH Images and Videos (see urisToQuery),
     * so periodic backup needs FULL access to both types. Partial access causes
     * mass "has no access" errors because MediaStore returns all items but
     * openInputStream() rejects non-selected ones.
     * CC-005 P2: OR -> AND (worker backs up both types).
     */
    fun canRunPeriodicBackup(context: Context): Boolean {
        return hasFullImageAccess(context) && hasFullVideoAccess(context)
    }

    /** Full access for image-only backup. */
    fun canBackupImages(context: Context): Boolean = hasFullImageAccess(context)

    /** Full access for video-only backup. */
    fun canBackupVideos(context: Context): Boolean = hasFullVideoAccess(context)

    /** True when only partial (user-selected) access is available. */
    fun isPartialOnly(context: Context): Boolean {
        return hasPartialVisualAccess(context) && !canRunPeriodicBackup(context)
    }

    /** Open app Settings so user can upgrade partial -> "Allow all". Call once, not per file. */
    fun openAppSettings(context: Context) {
        runCatching {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            ).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
