plugins {
    // AGP 9 compiles Kotlin itself ("built-in Kotlin"), so modules no longer
    // apply org.jetbrains.kotlin.android. Declaring KGP here with apply false
    // only pins the Kotlin compiler version AGP uses; the Compose compiler
    // plugin must match it exactly.
    id("com.android.application") version "9.5.0" apply false
    id("com.android.library") version "9.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // KSP2 versions are no longer tied to a Kotlin release.
    id("com.google.devtools.ksp") version "2.3.13" apply false
}
