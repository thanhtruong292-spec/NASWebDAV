import java.util.Properties
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localReleaseSigningPropsFile = rootProject.file(".secrets/release-signing.properties")
val localReleaseSigningProps = Properties().apply {
    if (localReleaseSigningPropsFile.isFile) {
        localReleaseSigningPropsFile.inputStream().use { stream -> load(stream) }
    }
}

fun releaseSigningValue(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: System.getenv(name)
        ?: localReleaseSigningProps.getProperty(name)

val releaseStoreFilePath: String? = releaseSigningValue("NAS_RELEASE_STORE_FILE")
val releaseStorePassword: String? = releaseSigningValue("NAS_RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? = releaseSigningValue("NAS_RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = releaseSigningValue("NAS_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

gradle.taskGraph.whenReady {
    if (allTasks.any { it.name.contains("Release") } && !hasReleaseSigning) {
        throw org.gradle.api.GradleException(
            "Release signing is required. Set NAS_RELEASE_STORE_FILE, NAS_RELEASE_STORE_PASSWORD, NAS_RELEASE_KEY_ALIAS, and NAS_RELEASE_KEY_PASSWORD."
        )
    }
}

// ─── Auto-versioning: mỗi lần build tự đánh số theo git + thời gian ───────────
// versionCode = số commit (tăng đều mỗi commit); versionName = 1.0.<count> (<sha> · <ngày giờ build>)
// Nhờ vậy nhìn nhãn trong app là biết chính xác build nào, tránh nhầm lẫn.
fun runGit(vararg args: String): String = try {
    val p = ProcessBuilder(listOf("git", *args))
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor()
    if (p.exitValue() == 0) out else ""
} catch (e: Exception) { "" }

val gitCommitCount: Int = runGit("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
val gitShortSha: String = runGit("rev-parse", "--short", "HEAD").ifBlank { "nogit" }
val buildStamp: String = SimpleDateFormat("yyMMdd.HHmm").format(Date())
// Đánh dấu build từ code CHƯA COMMIT (bản test thủ công) để không nhầm với bản chính thức.
// Chỉ tính thay đổi đã track (bỏ qua file chưa track như .apk build, .codex...) để
// bản release chính thức không bị gắn "+test" oan vì artifact rác trong thư mục.
val gitDirtySuffix: String = if (runGit("status", "--porcelain", "--untracked-files=no").isNotBlank()) "+test" else ""
val baseVersionName = "2.7"

android {
    namespace = "com.nas.naswebdav"
    compileSdk = 35 // Dùng 35 để ổn định nhất với Room hiện tại

    defaultConfig {
        applicationId = "com.nas.naswebdav"
        minSdk = 26
        targetSdk = 35
        versionCode = gitCommitCount
        versionName = "$baseVersionName.$gitCommitCount$gitDirtySuffix ($gitShortSha · $buildStamp)"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true  // để app đọc BuildConfig.VERSION_NAME hiển thị nhãn phiên bản
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Cờ chẩn đoán tạm thời: -PdiagBuild=true -> tắt R8 + bật debuggable để
            // đọc log WorkManager rõ ràng (vẫn release-signed nên cập nhật đè được, giữ login).
            val diagBuild = providers.gradleProperty("diagBuild").orNull == "true"
            isMinifyEnabled = !diagBuild
            isShrinkResources = !diagBuild
            isDebuggable = diagBuild
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    sourceSets {
        getByName("androidTest") {
            assets.setSrcDirs(listOf("$projectDir/schemas"))
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Jetpack Paging 3
    implementation("androidx.paging:paging-runtime-ktx:3.3.0")
    implementation("androidx.paging:paging-compose:3.3.0")

    // Sinh trắc học & Fragment hỗ trợ
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // ExoPlayer (Jetpack Media3) — khai báo duy nhất 1 lần
    val media3Version = "1.2.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")

    // Duyệt cây thư mục an toàn cho tính năng Đồng bộ
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.github.thegrizzlylabs:sardine-android:0.8")

    // Coil - Tải ảnh & video thumbnail
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Jetpack Compose UI
    implementation("androidx.compose.material:material-icons-extended:1.6.2")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.ui:ui:1.6.2")

    // Room Database
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    implementation("androidx.room:room-paging:$room_version")
    ksp("androidx.room:room-compiler:$room_version")
    androidTestImplementation("androidx.room:room-testing:$room_version")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Bảo mật & mã hóa SharedPreferences
    implementation("androidx.security:security-crypto:1.0.0")

    // WorkManager để chạy ngầm
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Timber for better logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Chucker for in-app network inspection
    debugImplementation("com.github.chuckerteam.chucker:library:4.0.0")
    releaseImplementation("com.github.chuckerteam.chucker:library-no-op:4.0.0")

    // Unit tests (JVM, không cần device)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("androidx.room:room-testing:$room_version")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("ksp.incremental", "false")
}
configurations.all {
    exclude(group = "xpp3", module = "xpp3")
}

