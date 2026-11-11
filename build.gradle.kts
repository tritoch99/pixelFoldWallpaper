// Set up for Android Studio Otter 3 Feature Drop (2025.2.3), which supports AGP up to 9.0.0.
// AGP 9.0 needs Gradle 9.1+ and compiles against API 36. The app still runs normally on Android 17.
plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself ("built-in Kotlin"); this line only pins the Kotlin version it uses.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
