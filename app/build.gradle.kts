plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kimiterrace.tvbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kimiterrace.tvbridge"
        minSdk = 26  // Android 8.0 (大半の Google TV をカバー)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // BLE scanning + foreground service runs on a TV. No phone/tablet specific features.
        // BuildConfig fields are referenced from BuildConfig (Kotlin).
        buildConfigField("String", "DEFAULT_TARGET_MAC", "\"DC:A5:B3:C2:98:D7\"")
        buildConfigField("String", "DEFAULT_WEBHOOK_URL", "\"https://www.school-signage.net/api/switchbot-webhook\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // PoC 用：デバッグ署名で release ビルドを許容（CI ビルドの簡素化）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
