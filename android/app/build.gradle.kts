plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Backend base URL. Debug defaults to the host machine as seen from the Android emulator.
// Override per build: ./build.sh assembleDebug -PapiBaseUrl=http://192.168.1.20:8000
val apiBaseUrl: String = (project.findProperty("apiBaseUrl") as String?) ?: "http://10.0.2.2:8000"
val releaseApiBaseUrl: String = (project.findProperty("releaseApiBaseUrl") as String?) ?: "https://api.quatrioai.invalid"

android {
    namespace = "dev.quatrioai.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.quatrioai.app"
        // minSdk 26 (Android 8.0): ~97%+ of active Indian Android devices in 2026, and it gives us
        // adaptive icons (no raster launcher fallbacks), java.time and java.util.Base64 natively.
        // Going lower buys almost no real users: current AndroidX/Compose already require 23+.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            // project-local debug keystore if present (android/build.sh creates it); otherwise AGP's default
            val local = rootProject.file("debug.keystore")
            if (local.exists()) {
                storeFile = local
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            // Dev builds simulate a successful UPI payment via the backend's mock confirm endpoint.
            buildConfigField("boolean", "MOCK_PAYMENTS", "true")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            buildConfigField("boolean", "MOCK_PAYMENTS", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    // On-device text recognition via Google Play services (Play Store-only app, so ML Kit is fine).
    // Unbundled on purpose: the bundled model adds ~10 MB of native code per ABI, which matters on
    // low-storage phones and prepaid data. The model is fetched at install time (see manifest).
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.2")
    implementation("com.google.android.gms:play-services-base:18.5.0") // ModuleInstall: fetch the OCR model on demand

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
