plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dbos.pilot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dbos.pilot"
        minSdk = 26
        targetSdk = 28
    }

    signingConfigs {
        create("pilot") {
            // The checked-in keystore keeps upgrades conflict-free across
            // locally built and repo-shipped APKs.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("pilot")
        }
        debug {
            signingConfig = signingConfigs.getByName("pilot")
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
        compose = true
    }
}

dependencies {
    // Compose for TV — the Android TV docs route (leanback is deprecated).
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    // Compose runtime/UI/foundation arrive transitively via tv-material.
}
