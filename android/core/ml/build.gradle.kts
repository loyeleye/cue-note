// Model wrappers behind small interfaces: voice activity (Silero) and speech
// to text (whisper.cpp). Plain Kotlin on the JVM; the native library comes
// from :core:ml-android on the phone and from host-build.sh on a laptop.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
}

// Tests that need the real models run only when the native library and model
// files are present (see README "Pipeline"). Otherwise they are skipped.
tasks.test {
    val host = layout.projectDirectory.dir("build/host").asFile
    systemProperty("cuenote.nativeDir", host.absolutePath)
    System.getenv("CUENOTE_MODELS")?.let { systemProperty("cuenote.models", it) }
}
