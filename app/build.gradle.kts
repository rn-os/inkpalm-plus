plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.inkpalmplus"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.inkpalmplus"
        // Moaan InkPalm Plus ships Android 11.
        minSdk = 30
        targetSdk = 34
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = System.getenv("IPP_VERSION") ?: "0.1.0-local.$build"
    }

    signingConfigs {
        // Shared test key checked into the repo, so every build installs over the last
        // without losing settings. Not for Play Store releases.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    // Shizuku: on-device ADB, for the writes Moaan keeps from ordinary apps.
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")
    // Moaan's per-app DPI call lives on a hidden PackageManager method.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
