pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "englus"

// Shared on-device ASR: whisper.cpp JNI + audio decoding/resampling.
include(":asr")

// Phase 0 benchmark harness. Kept after the spike concluded because model and
// thread-count comparisons keep coming back (see docs/phase0-results.md).
include(":bench")
