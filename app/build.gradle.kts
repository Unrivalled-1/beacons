plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.beacons"
    compileSdk = 34

    defaultConfig {
        // Placeholder id: the product name is not chosen yet.
        applicationId = "com.example.beacons"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Phase 1 stays dependency-light. Compose and Play Services location arrive with the steps that need them.
}
