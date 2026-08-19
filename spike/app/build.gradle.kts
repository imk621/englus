plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.englus.spike"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.englus.spike"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // Galaxy S25 only — arm64 keeps the build fast and the APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Native perf numbers are meaningless in a debug build; benchmark this variant.
            isDebuggable = false
            // Signed with the debug key so `installRelease` works on a personal
            // device without a keystore. This app is never distributed.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
