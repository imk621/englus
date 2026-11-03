plugins {
    id("com.android.library")
}

android {
    namespace = "com.englus.asr"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26

        // Galaxy S25 only by default. Override for an emulator run:
        //   ./gradlew installDebug -Pabis=x86_64
        // The default must stay arm64-only so a benchmark build can never
        // silently be taken from a non-target architecture.
        val abis = (project.findProperty("abis") as String?)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("arm64-v8a")
        ndk { abiFilters += abis }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
}
