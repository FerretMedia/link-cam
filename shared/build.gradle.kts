plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// The shared module holds what both apps must agree on: models, the tools'
// reference math, the .cube parser and the design tokens. It never sees a pixel
// at frame rate.
//
// Only a JVM target for now. The Android app consumes it as a plain JVM
// library, and its tests run on any machine without an emulator. The iPad port
// adds `iosArm64()` and `iosSimulatorArm64()` here; nothing in commonMain
// depends on the JVM.
kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
