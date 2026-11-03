buildscript {
    dependencies {
        // AGP 9 compiles Kotlin itself (built-in Kotlin) using the Kotlin Gradle
        // plugin it ships with. Pinning KGP here makes the compiler version an
        // explicit choice, and the Compose compiler plugin below must match it.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
}
