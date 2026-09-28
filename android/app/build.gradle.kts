plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release key: outside the repo, its password in the macOS Keychain (D24). Back up the file.
val releaseKeystore = file("${System.getProperty("user.home")}/.android/ridge-release.jks")

// The one version number (SemVer). Releases are tagged v<appVersion>; the version code is
// derived so it always grows: MAJOR * 10000 + MINOR * 100 + PATCH (0.1.0 -> 100).
val appVersion = "0.2.1"
val appVersionCode = appVersion.split('.').map(String::toInt).let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "minor and patch must stay below 100" }
    major * 10000 + minor * 100 + patch
}

fun releasePassword(): String = providers.exec {
    commandLine("security", "find-generic-password", "-s", "ridge-release-keystore", "-w")
}.standardOutput.asText.get().trim()

android {
    namespace = "app.strap"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.thecommishdeuce.ridge" // D24; the code namespace keeps the codename
        minSdk = 31 // Android 12: runtime BLUETOOTH_CONNECT, no location permission for BLE
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                val password = releasePassword()
                storeFile = releaseKeystore
                storePassword = password
                keyAlias = "ridge"
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            // Compose is only smooth optimised: debug builds skip R8 and run debuggable.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the key (another machine, CI) the release APK comes out unsigned and won't install.
            if (releaseKeystore.exists()) signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "LOCAL_HTTP", "false")
        }
        debug {
            buildConfigField("boolean", "LOCAL_HTTP", "false")
        }
        // The release build against a local demo server with synthetic data (tools/demo-data),
        // for screenshots and UI work without a strap. Installs beside the real app; it alone
        // may use plain HTTP, and only to 127.0.0.1 (reached through `adb reverse`).
        create("demo") {
            initWith(getByName("release"))
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("boolean", "LOCAL_HTTP", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":strap-protocol"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
}
