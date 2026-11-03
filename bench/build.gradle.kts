plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.englus.spike"
    compileSdk = 37

    // Required even though this module has no native code of its own. Without an
    // NDK the app-level strip task silently passes native libs through
    // unstripped, and the .so arriving from :asr stays at 6.8 MB instead of 1.1 MB.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.englus.spike"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Must mirror :asr. Without it, third-party dependencies (Compose ships
        // a native lib) contribute armeabi-v7a/x86/x86_64 copies and the APK
        // more than doubles, even though :asr only builds arm64.
        val abis = (project.findProperty("abis") as String?)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("arm64-v8a")
        ndk { abiFilters += abis }
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

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    // whisper.cpp JNI + audio decoding. ABI selection lives there.
    implementation(project(":asr"))

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
