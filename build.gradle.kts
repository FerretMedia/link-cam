// Plugins are declared here once, without applying them, so every module
// resolves the same versions from `gradle/libs.versions.toml`.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
