// Builds libcuenote_ml (whisper.cpp plus the Silero voice detector) for the
// phone with the NDK, from the same CMakeLists the laptop build uses, and
// packages it with :core:ml's Kotlin wrappers.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.cuenote.ml.android"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 30
        // The Pixel is arm64; x86_64 is for the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
                System.getenv("WHISPER_SOURCE_DIR")?.let { arguments += "-DWHISPER_SOURCE_DIR=$it" }
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../ml/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // whisper.cpp is slow unoptimised, so build native code in release mode for debug builds too.
    buildTypes { debug { externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } } } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":core:ml"))
}
