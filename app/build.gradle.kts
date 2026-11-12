plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.harkharaj.rawgeocam"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.harkharaj.rawgeocam"
        minSdk = 29          // Android 10: MediaStore Downloads, LENS_DISTORTION, SessionConfiguration
        targetSdk = 34       // keep 34: at 35 Android 15 forces edge-to-edge and the UI would need inset handling
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

// No third-party dependencies: only the Android framework (Camera2, SensorManager, LocationManager,
// DngCreator, MediaStore, org.json) and the Kotlin standard library.
