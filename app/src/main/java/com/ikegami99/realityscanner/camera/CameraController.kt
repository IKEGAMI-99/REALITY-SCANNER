package com.ikegami99.realityscanner.camera

import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.ikegami99.realityscanner.detection.Detector
import com.ikegami99.realityscanner.logging.AppLogger
import com.ikegami99.realityscanner.tracking.TrackManager
import com.ikegami99.realityscanner.ui.HudOverlayView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class CameraController(
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val detector: Detector,
    private val trackManager: TrackManager,
    private val hud: HudOverlayView,
    private val logger: AppLogger
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    private var frameCounter = 0
    private var fpsWindowStart = System.nanoTime()
    private var cameraFps = 0f

    private var lastInferenceNanos = 0L
    private var lastInferenceMs = 0f
    private var aiFps = 0f
    private var lastLowLight = false
    private var inferenceCount = 0
    private var aiWindowStart = System.nanoTime()

    fun start() {
        val future = ProcessCameraProvider.getInstance(previewView.context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                logger.info("CAMERA", "preview started // analyzer=1280x720 // display=square crop")
            } catch (t: Throwable) {
                logger.error("CAMERA", "start failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(previewView.context))
    }

    private fun analyze(image: ImageProxy) {
        val now = System.nanoTime()
        updateCameraFps(now)
        val luma = calculateLuma(image)
        val lowLight = luma < 48f

        if (lowLight != lastLowLight) {
            lastLowLight = lowLight
            logger.info(
                "LOWLIGHT",
                if (lowLight) "AUTO -> ACTIVE // luma=${"%.1f".format(luma)}"
                else "AUTO -> STANDBY // luma=${"%.1f".format(luma)}"
            )
            applyExposureAssist(lowLight)
        }

        val inferDue = now - lastInferenceNanos >= INFERENCE_INTERVAL_NANOS
        if (!inferDue) {
            publishHud()
            image.close()
            return
        }

        lastInferenceNanos = now
        val started = System.nanoTime()

        try {
            val bitmap = image.toBitmap()
            val detections = detector.detect(
                bitmap = bitmap,
                rotationDegrees = image.imageInfo.rotationDegrees,
                lowLightGain = if (lowLight) 1.45f else 1f
            )
            bitmap.recycle()

            val tracks = trackManager.update(detections, now)
            lastInferenceMs = (System.nanoTime() - started) / 1_000_000f
            updateAiFps(System.nanoTime())

            if (detections.isNotEmpty()) {
                logger.info(
                    "YOLO",
                    "objects=${detections.size} tracks=${tracks.size} infer=${"%.1f".format(lastInferenceMs)}ms"
                )
                tracks.take(4).forEach {
                    logger.info(
                        "VECTOR",
                        "#${it.id} ${it.label} rel=${"%.3f".format(it.relativeSpeed)}/s " +
                            "vx=${"%.3f".format(it.velocityX)} vy=${"%.3f".format(it.velocityY)}"
                    )
                }
            }

            previewView.post {
                hud.setTracks(tracks)
                publishHud()
            }
        } catch (t: Throwable) {
            logger.error("ANALYSIS", "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            image.close()
        }
    }

    private fun updateCameraFps(now: Long) {
        frameCounter++
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1_000_000_000L) {
            cameraFps = frameCounter * 1_000_000_000f / elapsed
            frameCounter = 0
            fpsWindowStart = now
        }
    }

    private fun updateAiFps(now: Long) {
        inferenceCount++
        val elapsed = now - aiWindowStart
        if (elapsed >= 1_000_000_000L) {
            aiFps = inferenceCount * 1_000_000_000f / elapsed
            inferenceCount = 0
            aiWindowStart = now
        }
    }

    private fun publishHud() {
        previewView.post {
            hud.setStats(
                HudOverlayView.Stats(
                    cameraFps = cameraFps,
                    aiFps = aiFps,
                    inferenceMs = lastInferenceMs,
                    lowLight = lastLowLight,
                    backend = detector.backendName
                )
            )
        }
    }

    private fun calculateLuma(image: ImageProxy): Float {
        val plane = image.planes.firstOrNull() ?: return 255f
        val buffer = plane.buffer
        val remaining = buffer.remaining()
        if (remaining <= 0) return 255f

        val step = maxOf(1, remaining / 4096)
        var sum = 0L
        var count = 0
        var index = buffer.position()
        val limit = buffer.limit()

        while (index < limit) {
            sum += buffer.get(index).toInt() and 0xFF
            count++
            index += step
        }
        return if (count == 0) 255f else sum.toFloat() / count
    }

    private fun applyExposureAssist(lowLight: Boolean) {
        val activeCamera = camera ?: return
        val state = activeCamera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return

        val target = if (lowLight) min(2, state.exposureCompensationRange.upper) else 0
        runCatching {
            activeCamera.cameraControl.setExposureCompensationIndex(target)
        }
    }

    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdownNow()
        detector.close()
    }

    companion object {
        private const val INFERENCE_INTERVAL_NANOS = 120_000_000L
    }
}
