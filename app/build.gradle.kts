import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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

// ─── Auto-versioning: mỗi lần build tự đánh số theo git + thời gian ───
// versionCode = số commit (tăng đều mỗi commit); versionName = 1.0.<count> (<sha> · ngày giờ build)
// Nhờ vậy nhìn nhãn trong app là biết chính xác build nào, tránh nhầm lẫn.
fun runGit(vararg args: String): String = try {
    val p = ProcessBuilder(listOf("git", *args))
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor()
    if (p.exitValue() == 0) out else ""
} catch (e: Exception) { "" }

val gitCommitCount: Int = runGit("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
val gitShortSha: String = runGit("rev-parse", "--short", "HEAD").ifBlank { "nogit" }
val buildStamp: String = SimpleDateFormat("yyMMdd.HHmm").format(Date())
// Đã bỏ nhãn +test theo yêu cầu người dùng
val gitDirtySuffix: String = ""
val baseVersionName = "1.0"

android {
    namespace = "com.nas.naswebdav"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nas.naswebdav"
        minSdk = 26
        targetSdk = 35
        versionCode = gitCommitCount
        versionName = "$baseVersionName.$gitCommitCount$gitDirtySuffix ($gitShortSha · $buildStamp)"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        buildConfig = true
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Đảm bảo Java compile đọc source là UTF-8 — tránh mojibake tiếng Việt khi build
        // (PowerShell Set-Content mặc định ghi BOM UTF-8, có thể gây parse sai encoding).
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
        }
    }
    kotlin {
        jvmToolchain(17)
    }
    sourceSets {
        getByName("androidTest") {
            assets.setSrcDirs(listOf("$projectDir/schemas"))
        }
    }
}

dependencies {
    // Jetpack Paging 3
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.paging.compose)

    // SMB/Samba client — upload/download files to NAS via native SMB3 protocol
    implementation(libs.smbj)

    // Sinh trắc học & Fragment hỗ trợ
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.appcompat)

    // ExoPlayer (Jetpack Media3) — khai báo duy nhất 1 lần
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)

    // Duyệt cây thư mục an toàn cho tính năng Đồng bộ
    implementation(libs.androidx.documentfile)
    implementation(libs.sardine.android)

    // Coil - Tải ảnh & video thumbnail
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // Navigation Compose
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Jetpack Compose UI
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Test dependencies
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)

    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Bảo mật & mã hóa SharedPreferences
    implementation(libs.androidx.security.crypto)

    // WorkManager để chạy ngầm
    implementation(libs.androidx.work.runtime.ktx)

    // Timber for better logging
    implementation(libs.timber)

    // Sentry self-hosted crash reporting (opt-in, xem CrashReporter.kt)
    implementation(libs.sentry.android)

    // Chucker for in-app network inspection
    debugImplementation(libs.chucker)
    releaseImplementation(libs.chucker.no.op)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("ksp.incremental", "false")
}
configurations.all {
    exclude(group = "xpp3", module = "xpp3")
}

