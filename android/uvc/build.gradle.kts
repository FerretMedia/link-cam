plugins {
    alias(libs.plugins.android.library)
}

// The USB Video Class driver: libusb + libuvc + libjpeg-turbo behind a small
// Kotlin API. It knows nothing about the UI; the app opens a device through
// Android's UsbManager and hands this module the file descriptor.
android {
    namespace = "cool.linker.cam.uvc"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29
        ndk {
            // arm64 for phones and tablets; x86_64 so the app still installs
            // on an emulator, where only the test pattern can run.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
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
}
