import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.aivpn.connect"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aivpn.connect"
        minSdk = 28  // ChaCha20-Poly1305 requires API 28+
        targetSdk = 34
        versionCode = 10
        versionName = "1.1.5"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Disabled for the first distributable build — Compose/crypto need full
            // ProGuard rules before minification can be re-enabled safely.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Encrypted storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WebRTC transport (DataChannel-based fallback when UDP is blocked by corporate DPI)
    // Using webrtc-sdk fork of Google libwebrtc — last maintained version on Maven Central.
    implementation("io.github.webrtc-sdk:android:125.6422.07")

    // OkHttp for signaling WebSocket client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON (already used via org.json in Android, but add for consistency)
}
