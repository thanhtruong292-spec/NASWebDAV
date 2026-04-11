plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nas.naswebdav"
    compileSdk = 35 // Dùng 35 để ổn định nhất với Room hiện tại

    defaultConfig {
        applicationId = "com.nas.naswebdav"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
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
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

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

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Bảo mật & mã hóa SharedPreferences
    implementation("androidx.security:security-crypto:1.0.0")

    // WorkManager để chạy ngầm
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("ksp.incremental", "false")
}