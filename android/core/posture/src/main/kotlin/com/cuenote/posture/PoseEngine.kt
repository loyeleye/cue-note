package com.cuenote.posture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.cuenote.signal.posture.Landmark
import com.cuenote.signal.posture.Pose
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * MediaPipe Pose, on the phone. Finds up to two people per frame (the
 * student, and maybe the teacher at the piano) and returns their 33 body
 * points each. Nothing leaves the phone.
 */
class PoseEngine(context: Context) : Closeable {
    private val landmarker: PoseLandmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(2)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build(),
    )
    private var lastTs = -1L

    /** Body points of everyone in [upright] (already rotated the right way up). */
    fun detect(upright: Bitmap, timestampMs: Long): List<Pose> {
        val ts = maxOf(timestampMs, lastTs + 1) // the model needs strictly rising times
        lastTs = ts
        val result = landmarker.detectForVideo(BitmapImageBuilder(upright).build(), ts)
        val aspect = upright.width.toFloat() / upright.height
        return result.landmarks().map { person ->
            Pose(person.map { Landmark(it.x(), it.y(), it.z(), it.visibility().orElse(0f)) }, aspect)
        }
    }

    override fun close() = landmarker.close()

    companion object { const val MODEL = "pose_landmarker_lite.task" }
}

/**
 * Camera frames for the pose model: small (about 640 by 480), at most
 * [fps] a second, each turned upright. Frames go straight to [onFrame]
 * and are dropped after; nothing is saved here, and never any video.
 */
class PoseFrames(
    private val fps: Int = 5,
    private val onFrame: (upright: Bitmap, elapsedMs: Long) -> Unit,
) : Closeable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "cuenote-pose") }
    private var lastMs = 0L

    /** The analysis use case to bind to a camera, at the display rotation the phone is mounted in. */
    fun useCase(targetRotation: Int): UseCase = ImageAnalysis.Builder()
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setTargetRotation(targetRotation)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build(),
        )
        .build()
        .apply { setAnalyzer(executor, ::analyze) }

    private fun analyze(image: ImageProxy) {
        image.use {
            val now = SystemClock.elapsedRealtime()
            if (now - lastMs < 1000L / fps) return
            lastMs = now
            val raw = it.toBitmap()
            val rotation = it.imageInfo.rotationDegrees
            val upright = if (rotation == 0) raw else Bitmap.createBitmap(
                raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true,
            ).also { raw.recycle() }
            onFrame(upright, now)
        }
    }

    override fun close() = executor.shutdown()

    companion object {
        fun selector(front: Boolean): CameraSelector =
            if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }
}

/** Hands over CameraX's camera provider on the main thread once it's ready. */
fun withCameraProvider(context: Context, block: (androidx.camera.lifecycle.ProcessCameraProvider) -> Unit) {
    val future = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context)
    future.addListener({ runCatching { future.get() }.getOrNull()?.let(block) }, androidx.core.content.ContextCompat.getMainExecutor(context))
}
