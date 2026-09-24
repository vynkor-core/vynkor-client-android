// Top-level build config. The app module owns the Android plugin; the Rust
// core is built by cargo-ndk through a Gradle task (see app/build.gradle.kts).
// Versions live in gradle/libs.versions.toml (R-32).

plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin); declaring KGP here only
    // pins the compiler version it uses.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.legacy.kapt) apply false
}
