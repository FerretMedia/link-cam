plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// The shared module holds what both apps must agree on: models, the tools'
// reference math, the .cube parser and the design tokens. It never sees a pixel
// at frame rate.
//
// The Android app consumes the JVM target as a plain library, and the tests
// run on it on any machine without an emulator. The iPad app links the iOS
// targets as a static framework, `LinkCamShared`, which Xcode builds through
// `embedAndSignAppleFrameworkForXcode` (see ios/project.yml).
kotlin {
    jvmToolchain(17)
    jvm()

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "LinkCamShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
