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
        // CI run number, so each downloaded build installs as an upgrade over the last.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        // CI sets IPP_VERSION: "1.0.0" for a stable release (a v1.0.0 tag), "1.0.0-dev.N" for main pre-releases
        // (matching the release tag, for Obtainium's version check), "1.0.0-branch.N" for branch test builds.
        versionName = System.getenv("IPP_VERSION") ?: "0.1.0-local.$build"
    }

    signingConfigs {
        // Debug builds: a shared test key checked into the repo, so every debug build installs over the last
        // without losing settings.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    signingConfigs {
        // Release builds: the private release key, never in the repo. CI decodes it from GitHub secrets;
        // locally, point these at your copy. Without them the release APK comes out unsigned.
        val keystore = System.getenv("IPP_KEYSTORE")
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("IPP_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("IPP_KEY_ALIAS") ?: "inkpalm-plus"
            keyPassword = System.getenv("IPP_KEY_PASSWORD") ?: System.getenv("IPP_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // The device checks open the E-ink screen directly; release builds keep it private.
            manifestPlaceholders["sectionExported"] = "true"
        }
        release {
            // Off: Shizuku and HiddenApiBypass are reached by reflection.
            isMinifyEnabled = false
            manifestPlaceholders["sectionExported"] = "false"
            signingConfig = signingConfigs.findByName("release")
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
