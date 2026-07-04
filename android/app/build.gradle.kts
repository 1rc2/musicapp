plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.musicflow.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.musicflow.app"
        minSdk = 21
        targetSdk = 36
        versionCode = 10309
        versionName = "1.3.9"
    }

    signingConfigs {
        create("release") {
            storeFile = file("debug.keystore")
            storePassword = System.getenv("STORE_PASSWORD") ?: "musicflow123"
            keyAlias = "musicflow"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "musicflow123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Media3 — 播放内核
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // WebView
    implementation("androidx.webkit:webkit:1.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}