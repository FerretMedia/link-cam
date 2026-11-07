import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// One version for both apps, in /VERSION. 1.2.3 is versionCode 10203, as
// scripts/version-code.sh works it out for the iPad.
val appVersion = rootProject.file("VERSION").readText().trim()
val appVersionCode = appVersion.split(".").map(String::toInt).let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "VERSION: minor and patch stay below 100" }
    major * 10000 + minor * 100 + patch
}
// Dev builds from CI number themselves by the dev branch's commit count, so
// each one installs over the one before (see .github/workflows/dev-build.yml).
val buildVersionCode = System.getenv("LINKCAM_VERSION_CODE")?.toInt() ?: appVersionCode
val buildVersionName = System.getenv("LINKCAM_VERSION_NAME") ?: appVersion

// Release signing comes from outside the repository: environment variables in
// CI, or keystore.properties (gitignored) on a developer's machine. Without
// either, a release build comes out unsigned.
val signingProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun signing(env: String, prop: String): String? = System.getenv(env) ?: signingProps.getProperty(prop)
val releaseStoreFile = signing("LINKCAM_KEYSTORE_FILE", "storeFile")

android {
    namespace = "cool.linker.cam"
    compileSdk = 37

    defaultConfig {
        applicationId = "cool.linker.cam"
        minSdk = 29
        targetSdk = 36
        versionCode = buildVersionCode
        versionName = buildVersionName
        manifestPlaceholders["appLabel"] = "@string/app_name"
        // The USB driver is built for these two only (android/uvc); without the
        // filter, ML Kit would add 32-bit libraries that can't run without it.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signing("LINKCAM_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signing("LINKCAM_KEY_ALIAS", "keyAlias")
                keyPassword = signing("LINKCAM_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    sourceSets {
        getByName("main") {
            // Link Cam's name, glyph and launcher icon live in /brand, outside
            // the Apache licence (see TRADEMARKS.md). A fork replaces that
            // folder with its own art.
            res.directories.add(rootProject.file("brand/android/res").path)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // A release build of the dev branch, for testers. Its own app ID lets it
        // sit next to the stable app, with its own settings and paired cameras.
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "@string/app_name_dev"
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":android:uvc"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // On-device, model bundled: reads the camera's Wi-Fi screen with no internet, as on the camera's own Wi-Fi.
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
}
