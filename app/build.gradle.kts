plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.englus.app"
    compileSdk = 37

    // Required even though this module has no native code of its own. Without an
    // NDK the app-level strip task silently passes native libs through
    // unstripped, and the .so arriving from :asr stays at 6.8 MB instead of 1.1 MB.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.englus.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Must mirror :asr, or third-party native libs drag in other ABIs.
        val abis = (project.findProperty("abis") as String?)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("arm64-v8a")
        ndk { abiFilters += abis }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            // Debug key: this app is sideloaded onto one personal phone.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    // Exported schemas make migrations testable: MigrationTestHelper builds an
    // old database from these JSON files and runs the real migration against it.
    // Without them a broken migration is only discovered when the app fails to
    // open on a phone that already holds transcribed episodes.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(project(":asr"))

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("androidx.work:work-runtime-ktx:2.12.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    // Lock-screen and headphone controls, and playback that survives leaving the
    // app. Without a session the player dies with the ViewModel, which rules out
    // every minute the phone is in a pocket - the majority of listening time.
    implementation("androidx.media3:media3-session:1.11.1")

    // On-device en->ko translation. Free, offline, unlimited — which is what
    // bulk-translating 500 sentences per episode needs. Cloud models read
    // better but would put a quota and a network round trip in front of
    // every episode.
    implementation("com.google.mlkit:translate:17.0.3")
    // On-device OCR: free, offline, and fast enough to run on a photo the
    // moment it is taken.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Stores the user's Gemini API key behind the Android keystore.
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    // The only third-party HTTP dependency, and it is here for one reason: the
    // Live API is a bidirectional WebSocket, and there is no WebSocket client in
    // the Android platform. Every other network call in this app is a plain
    // HttpURLConnection in feed/Http.kt.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Pure-logic tests (word alignment) run on the JVM in seconds; only things
    // that need Android or a whisper model go in androidTest.
    testImplementation("junit:junit:4.13.2")
    // org.json ships with Android as stubs that throw in local unit tests;
    // this puts a real implementation on the JVM test classpath.
    testImplementation("org.json:json:20260814")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    // Runs a worker directly so its constraints (unmetered, charging) do not
    // have to be satisfied to test what it actually does.
    androidTestImplementation("androidx.work:work-testing:2.12.0")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
