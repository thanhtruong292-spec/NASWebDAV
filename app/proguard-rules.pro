# ═══════════════════════════════════════════════════════
# ProGuard / R8 Rules — NAS WebDAV
# FIX C1: File này trước đây trống hoàn toàn → bản release crash vì R8 xóa class quan trọng
# ═══════════════════════════════════════════════════════

# ─── Debugging: Giữ SourceFile và LineNumber để crash log còn đọc được ───────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ─── OkHttp & Okio ───────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ─── Room Database ───────────────────────────────────────────────────────────
# Giữ tất cả Entity, DAO, và Database class để Room reflection hoạt động
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
-keepclassmembers @androidx.room.Entity class * { *; }
-keep class **_Impl { *; }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }

# ─── WorkManager Workers ─────────────────────────────────────────────────────
# R8 sẽ xóa Worker class nếu không có rules này → WorkManager crash khi enqueue
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ─── WebSocket Callback ──────────────────────────────────────────────────────
-keep class * extends okhttp3.WebSocketListener { *; }
-keepclassmembers class * extends okhttp3.WebSocketListener { *; }

# ─── Coil Image Loader ───────────────────────────────────────────────────────
-dontwarn coil.**
-keep class coil.** { *; }

# ─── Compose (Jetpack Compose) ───────────────────────────────────────────────
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ─── Kotlinx Coroutines ──────────────────────────────────────────────────────
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ─── ExoPlayer / Media3 ──────────────────────────────────────────────────────
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# ─── Serialization & JSON ────────────────────────────────────────────────────
# org.json không cần keep (built-in) nhưng các callback class JSON cần giữ
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ─── AndroidX Extensions ─────────────────────────────────────────────────────
-dontwarn androidx.**
-keep class androidx.core.app.** { *; }
-keep class androidx.documentfile.** { *; }

# ─── DocumentsProvider ───────────────────────────────────────────────────────
-keep class com.nas.naswebdav.NasDocumentProvider { *; }

# ─── SecurePrefsHelper & AppConfig (truy cập reflection) ────────────────────
-keep class com.nas.naswebdav.SecurePrefsHelper { *; }
-keep class com.nas.naswebdav.AppConfig { *; }
-keep class com.nas.naswebdav.SmartNetworkManager { *; }

# ─── Application & ViewModel tổng quan ───────────────────────────────────────
-keep class com.nas.naswebdav.NasApplication { *; }
-keep class com.nas.naswebdav.WebDavViewModel { *; }
-keep class com.nas.naswebdav.WebDavManager { *; }
-keepclassmembers class com.nas.naswebdav.** {
    public *;
    internal *;
}

# ─── SMB Library (smbj) missing classes ─────────────────────────────────────
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**