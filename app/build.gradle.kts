import java.util.Properties

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

android {
    namespace = "com.nas.naswebdav"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nas.naswebdav"
        minSdk = 26
        targetSdk = 36
        versionCode = 343
        versionName = "1.0.343"
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Bảo mật & mã hóa SharedPreferences
    implementation(libs.androidx.security.crypto)

    // WorkManager để chạy ngầm
    implementation(libs.androidx.work.runtime.ktx)

    // Timber for better logging
    implementation(libs.timber)

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

