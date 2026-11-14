// The after-lesson pipeline: segment, pitch, transcribe, link, debug timeline.
// Pure Kotlin with no Android imports, so the code the phone runs is the code
// the JVM tests and the laptop runner run.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:signal"))
    api(project(":core:ml"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

// Laptop runner: ./gradlew :core:pipeline:run --args="process ~/Downloads/CueNote/<id>"
application {
    mainClass.set("com.cuenote.pipeline.CliKt")
    applicationDefaultJvmArgs = listOf(
        "-Xmx3g",
        "-Dcuenote.nativeDir=${project(":core:ml").layout.projectDirectory.dir("build/host").asFile.absolutePath}",
        "-Dcuenote.llmDir=${rootProject.layout.projectDirectory.dir("core/llm-android/build/host").asFile.absolutePath}",
    )
}
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir.parentFile
    System.getenv("CUENOTE_MODELS")?.let { systemProperty("cuenote.models", it) }
}

tasks.test {
    maxHeapSize = "2g"
    systemProperty("cuenote.nativeDir", project(":core:ml").layout.projectDirectory.dir("build/host").asFile.absolutePath)
    System.getenv("CUENOTE_MODELS")?.let { systemProperty("cuenote.models", it) }
    // A folder of labelled real lessons (each with labels.json); see README.
    System.getenv("CUENOTE_GOLDEN")?.let { systemProperty("cuenote.golden", it) }
    systemProperty("cuenote.reportDir", layout.buildDirectory.dir("golden-report").get().asFile.absolutePath)
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
