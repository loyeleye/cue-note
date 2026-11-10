import java.io.File
import java.net.URI
import java.security.MessageDigest

// Posture tracking: the camera service that runs MediaPipe Pose on the phone
// while the student sings, and the pieces the framing check shares with it.
// The rules themselves (framing, flags, back-off) are pure Kotlin in :core:signal.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.cuenote.posture"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

/**
 * Fetches MediaPipe's "lite" pose model (5.8 MB) at build time and packs it
 * into the app, checked against a fixed hash, so nothing downloads on the phone.
 */
abstract class DownloadPoseModel : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun download() {
        val out = outputDir.get().file("pose_landmarker_lite.task").asFile
        fun hash(f: File) = MessageDigest.getInstance("SHA-256")
            .digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (out.isFile && hash(out) == sha256.get()) return
        out.parentFile.mkdirs()
        var last: Exception? = null
        for (attempt in 1..4) {
            try {
                URI(url.get()).toURL().openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
                break
            } catch (e: Exception) { last = e; Thread.sleep(2_000L * attempt) }
        }
        check(out.isFile) { "could not download the pose model: ${last?.message}" }
        check(hash(out) == sha256.get()) { "the pose model's hash doesn't match; delete ${out.path} and retry" }
    }
}

val poseModel = tasks.register<DownloadPoseModel>("downloadPoseModel") {
    url.set("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task")
    sha256.set("59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a")
    outputDir.set(layout.buildDirectory.dir("generated/pose-model"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(poseModel, DownloadPoseModel::outputDir)
    }
}

dependencies {
    api(project(":core:signal"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    api(libs.androidx.camera.lifecycle)
    implementation(libs.mediapipe.tasks.vision)
}
