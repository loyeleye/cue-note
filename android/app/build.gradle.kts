plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Reads app/google-services.json (Firebase Cloud Messaging for "card updated" pushes).
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.cuenote.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cuenote.app"
        minSdk = 30
        targetSdk = 35
        // CI sets the build number so every APK it publishes is a newer
        // version Android (and Obtainium) will install as an update.
        val build = System.getenv("CUENOTE_BUILD_NUMBER")?.toIntOrNull()
        versionCode = build ?: 2
        versionName = if (build != null) "0.2.$build" else "0.2.0-dev"
        // The Pixel is arm64; x86_64 is for the emulator. Keeps the pose model's native code from bloating the APK.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // CI signs with one fixed key (from GitHub secrets) so each new APK
    // installs over the last one and keeps the lessons on the phone.
    // Local builds use the usual per-machine debug key.
    val keystore = System.getenv("CUENOTE_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    val stable = keystore?.let {
        signingConfigs.create("stable") {
            storeFile = it
            storePassword = System.getenv("CUENOTE_KEYSTORE_PASSWORD")
            keyAlias = "cuenote"
            keyPassword = System.getenv("CUENOTE_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            if (stable != null) signingConfig = stable
        }
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":core:posture"))
    implementation(project(":feature:record"))
    implementation(project(":feature:lesson"))
    implementation(project(":feature:practice"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.coroutines.play.services)
}
