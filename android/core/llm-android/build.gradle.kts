// Builds libcuenote_llm (llama.cpp, for the card writer that runs on the
// phone) with the NDK, and packages it with :core:pipeline's Kotlin side
// (com.cuenote.pipeline.cards.LlamaNative). Apart from :core:ml-android so
// whisper.cpp's and llama.cpp's copies of ggml never meet.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.cuenote.llm.android"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 30
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
                System.getenv("LLAMA_SOURCE_DIR")?.let { arguments += "-DLLAMA_SOURCE_DIR=$it" }
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // A model is unusably slow unoptimised, so native code is a release build in debug too.
    buildTypes { debug { externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } } } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":core:pipeline"))
}
