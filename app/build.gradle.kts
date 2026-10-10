import java.util.Properties

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
        versionCode = 3
        versionName = "0.3.0"
    }

    // Release signing key lives OUTSIDE the repo: ~/.beacons-signing/signing.properties
    // (storeFile, storePassword, keyAlias, keyPassword). Without it, the release build is unsigned.
    val signingProps = Properties().apply {
        val f = File(System.getProperty("user.home"), ".beacons-signing/signing.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (signingProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
