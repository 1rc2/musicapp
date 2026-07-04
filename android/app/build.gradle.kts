plugins {
    id("com.android.application")
}

android {
    namespace = "com.musicflow.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.musicflow.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 10502
        versionName = "1.5.2"
    }

    signingConfigs {
        create("release") {
            storeFile = file("debug.keystore")
            storePassword = "musicflow123"
            keyAlias = "musicflow"
            keyPassword = "musicflow123"
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

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity:1.9.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
