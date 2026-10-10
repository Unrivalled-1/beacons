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
    // Fused location (on-device; works offline). Compose arrives with the sheets in Step 3.
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
