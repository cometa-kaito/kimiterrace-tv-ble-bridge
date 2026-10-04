plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
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
        // v2 バックエンド（GCP）の TV ポーリングエンドポイント（LP 互換レスポンス）。
        // 秘密鍵（key）はソースに焼かない。プロビジョニング時に `?key=<V2_TV_POLL_SECRET>` を付与する。
        buildConfigField("String", "DEFAULT_CONFIG_ENDPOINT", "\"https://app.school-signage.net/api/tv/lp-config\"")
    }

    // 署名鍵の固定（2026-10-04）
    // このアプリは Device Owner なのでアンインストールできない＝現場の端末は「最初に入れた鍵」でしか更新できない。
    // CI の使い捨て debug 鍵で署名すると INSTALL_FAILED_UPDATE_INCOMPATIBLE になる。
    // 環境変数 TV_SIGNING_STORE_FILE が在ればその keystore（現場と同じ鍵 SHA-256 0951ef53…）で debug/release とも署名する。
    // 無ければ従来どおりマシンごとの debug 鍵（＝現場には入れられない）。
    val pinnedStoreFile = System.getenv("TV_SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }
    val pinnedSigning = if (pinnedStoreFile != null && pinnedStoreFile.exists()) {
        signingConfigs.create("pinned") {
            storeFile = pinnedStoreFile
            storePassword = System.getenv("TV_SIGNING_STORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("TV_SIGNING_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("TV_SIGNING_KEY_PASSWORD") ?: "android"
        }
    } else null

    buildTypes {
        debug {
            isMinifyEnabled = false
            // debuggable のまま（run-as で prefs を読み書きする運用のため）。鍵だけ差し替える。
            if (pinnedSigning != null) signingConfig = pinnedSigning
        }
        release {
            isMinifyEnabled = false
            // PoC 用：デバッグ署名で release ビルドを許容（CI ビルドの簡素化）
            signingConfig = pinnedSigning ?: signingConfigs.getByName("debug")
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

    // FCM: v2 が 🔴 検知時に端末を遠隔起動するための高優先度プッシュ受信（Doze 貫通）。
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging")

    // JVM 単体テスト（NavigationPolicy 等の純関数）
    testImplementation("junit:junit:4.13.2")
}
