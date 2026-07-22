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

# ─── Compose (Jetpack Compose) ───────────────────────────────────────────────
-dontwarn androidx.compose.**

# ─── Kotlinx Coroutines ──────────────────────────────────────────────────────
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ─── ExoPlayer / Media3 ──────────────────────────────────────────────────────
-dontwarn androidx.media3.**

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
# P1-18: SmartNetworkManager deleted — dead rule removed

# ─── Application & ViewModels ────────────────────────────────────────────────
-keep class com.nas.naswebdav.NasApplication { *; }
# P1-18: WebDavViewModel and SmartNetworkManager have been removed from the codebase
# (Strangler Fig refactor). Dead keep-rules removed to avoid false confidence.
-keep class com.nas.naswebdav.**.*ViewModel { *; }
-keep class com.nas.naswebdav.**.*Entity { *; }
-keep class com.nas.naswebdav.data.** { *; }

# ─── SMB Library (smbj) & Sardine missing classes ────────────────────────────
# P2-9: Add missing dontwarn rules for smbj/sardine-android to suppress R8 warnings
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.smbj.**
-dontwarn com.thegrizzlylabs.sardineandroid.**
-dontwarn org.apache.http.**
-dontwarn android.net.http.**