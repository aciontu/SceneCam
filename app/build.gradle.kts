plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.scenacam.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.scenacam.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
        ndk {
            // Galaxy A56 este arm64; fără celelalte arhitecturi APK-ul e mult mai mic.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // Cheie de test fixă, inclusă în proiect, ca fiecare build nou să se instaleze peste cel vechi.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
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
}

dependencies {
    // Modelele AI sunt incluse în aplicație: funcționează fără internet.
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:face-detection:16.1.7")
}
